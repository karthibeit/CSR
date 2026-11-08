package com.xd

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Browser-view help for XD (XDMovies).
 *
 * Two jobs:
 *  1. solve(url) — beat a Cloudflare check on a page the plain
 *     HTTP client cannot fetch (the site detail pages, HubCloud):
 *     first the app's hidden WebViewResolver, then a visible
 *     "tap to verify" session dialog. Returns replay headers
 *     (Cookie + User-Agent), cached per host.
 *  2. walk(url) — the download buttons land on a
 *     latestnewsonline.sbs "Get Your Link" step page that only a
 *     real browser view can pass: load it in the session dialog,
 *     auto-click the Generate Link / Continue steps, and return
 *     the HubCloud / HubDrive URL it finally navigates to.
 *
 * One session dialog + WebView is shared by both jobs and by
 * every version of one title (see XDProvider.loadLinks), and it
 * carries the on-screen TV-remote pointer from the 4KHUB plugin
 * so the verify box can be tapped from a TV remote.
 */
object XdCf {

    private val solvedHeaders = mutableMapOf<String, Map<String, String>>()
    private val sessionMutex = Mutex()
    private val sessionCancelled = AtomicBoolean(false)

    private var sessionDialog: Dialog? = null
    private var sessionWebView: WebView? = null
    private var sessionStatus: TextView? = null
    private var sessionUa: String? = null

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull() ?: url

    /**
     * The currently resumed Activity, found via ActivityThread
     * reflection (plugins only ever see the application context,
     * which cannot host a dialog).
     */
    private fun currentActivity(): Activity? {
        return try {
            val thread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return null
            val field = thread.javaClass.getDeclaredField("mActivities")
            field.isAccessible = true
            val activities = field.get(thread) as? Map<*, *> ?: return null
            for (record in activities.values) {
                if (record == null) continue
                val pausedField = record.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (pausedField.getBoolean(record)) continue
                val activityField =
                    record.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                (activityField.get(record) as? Activity)?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Replay headers for a Cloudflare-checked page, or an empty
     * map when solving is impossible. Hidden solve first, then
     * the visible session dialog.
     */
    suspend fun solve(url: String): Map<String, String> {
        val host = hostOf(url)
        synchronized(solvedHeaders) {
            solvedHeaders[host]?.let { return it }
        }
        try {
            WebViewResolver(
                interceptUrl = Regex(".^"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex(".")),
                timeout = 20_000L
            ).resolveUsingWebView(url) {
                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(url)
                }.getOrNull()
                cookie?.contains("cf_clearance") == true
            }
            val cookie = runCatching {
                CookieManager.getInstance().getCookie(url)
            }.getOrNull()
            if (!cookie.isNullOrBlank() && cookie.contains("cf_clearance")) {
                val headers = mutableMapOf("Cookie" to cookie)
                WebViewResolver.webViewUserAgent?.let {
                    headers["User-Agent"] = it
                }
                synchronized(solvedHeaders) { solvedHeaders[host] = headers }
                return headers
            }
        } catch (e: Exception) {
            // fall through to the interactive solve
        }
        return sessionMutex.withLock {
            synchronized(solvedHeaders) {
                solvedHeaders[host]?.let { return@withLock it }
            }
            if (!ensureSession()) return@withLock emptyMap()
            val wv = sessionWebView ?: return@withLock emptyMap()
            sessionCancelled.set(false)
            setStatus("Solving the security check…")
            withContext(Dispatchers.Main) {
                runCatching { wv.loadUrl(url) }
            }
            val deadline = System.currentTimeMillis() + 120_000L
            var found: Map<String, String>? = null
            while (System.currentTimeMillis() < deadline) {
                if (sessionCancelled.get()) break
                delay(1000)
                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(url)
                }.getOrNull()
                if (!cookie.isNullOrBlank() &&
                    cookie.contains("cf_clearance")
                ) {
                    val headers = mutableMapOf("Cookie" to cookie)
                    (sessionUa ?: WebViewResolver.webViewUserAgent)?.let {
                        headers["User-Agent"] = it
                    }
                    found = headers
                    break
                }
            }
            val result = found ?: emptyMap()
            if (result.isNotEmpty()) {
                synchronized(solvedHeaders) { solvedHeaders[host] = result }
            }
            result
        }
    }

    /**
     * Build (once) the shared session dialog + WebView, modelled
     * on the 4KHUB plugin's interactive verify dialog, including
     * the D-pad pointer overlay for TV remotes.
     */
    private suspend fun ensureSession(): Boolean {
        if (sessionWebView != null && sessionDialog?.isShowing == true) {
            return true
        }
        val activity = currentActivity() ?: return false
        return try {
            withContext(Dispatchers.Main) {
                fun dp(v: Int): Int =
                    (v * activity.resources.displayMetrics.density).toInt()
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                }
                val title = TextView(activity).apply {
                    text = "XDMovies — verify once, then links load"
                    textSize = 17f
                }
                val wv = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                // TV remote pointer: an overlay ring the D-pad
                // moves over the WebView, because a TV remote cannot
                // move focus into the page's checkbox on its own.
                var cursorX = -1f
                var cursorY = -1f
                val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                val pointer = object : View(activity) {
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        if (cursorX < 0f || cursorY < 0f) return
                        cursorPaint.style = Paint.Style.STROKE
                        cursorPaint.strokeWidth = dp(3).toFloat()
                        cursorPaint.color = Color.WHITE
                        canvas.drawCircle(
                            cursorX, cursorY, dp(14).toFloat(), cursorPaint
                        )
                        cursorPaint.style = Paint.Style.FILL
                        cursorPaint.color = Color.rgb(255, 87, 34)
                        canvas.drawCircle(
                            cursorX, cursorY, dp(4).toFloat(), cursorPaint
                        )
                    }
                }
                pointer.isClickable = false
                pointer.isFocusable = false
                val stage = FrameLayout(activity)
                stage.addView(
                    wv,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                stage.addView(
                    pointer,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                fun tapPointer() {
                    if (cursorX < 0f) return
                    val downTime = android.os.SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN,
                        cursorX, cursorY, 0
                    )
                    down.setSource(
                        android.view.InputDevice.SOURCE_TOUCHSCREEN
                    )
                    wv.dispatchTouchEvent(down)
                    val up = MotionEvent.obtain(
                        downTime, downTime + 90, MotionEvent.ACTION_UP,
                        cursorX, cursorY, 0
                    )
                    up.setSource(
                        android.view.InputDevice.SOURCE_TOUCHSCREEN
                    )
                    wv.postDelayed({
                        wv.dispatchTouchEvent(up)
                        up.recycle()
                    }, 90)
                    down.recycle()
                }
                val status = TextView(activity).apply {
                    text = "Opening the download page… TV remote: " +
                        "arrow keys move the pointer, OK taps. " +
                        "Touch and mouse also work."
                    setPadding(0, dp(12), 0, 0)
                }
                container.addView(title)
                container.addView(
                    stage,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(430)
                    )
                )
                container.addView(status)
                val d = Dialog(activity)
                d.setContentView(container)
                d.setCancelable(true)
                d.setOnCancelListener { sessionCancelled.set(true) }
                d.setOnKeyListener { _, keyCode, event ->
                    val isMove = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    val isTap = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!isMove && !isTap) return@setOnKeyListener false
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (cursorX < 0f && wv.width > 0) {
                            cursorX = wv.width * 0.14f
                            cursorY = wv.height * 0.58f
                        }
                        if (isMove && cursorX >= 0f) {
                            val step = dp(14) +
                                dp(8) * minOf(event.repeatCount, 5)
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT ->
                                    cursorX = (cursorX - step)
                                        .coerceAtLeast(0f)
                                KeyEvent.KEYCODE_DPAD_RIGHT ->
                                    cursorX = (cursorX + step)
                                        .coerceAtMost(
                                            wv.width.toFloat()
                                        )
                                KeyEvent.KEYCODE_DPAD_UP ->
                                    cursorY = (cursorY - step)
                                        .coerceAtLeast(0f)
                                KeyEvent.KEYCODE_DPAD_DOWN ->
                                    cursorY = (cursorY + step)
                                        .coerceAtMost(
                                            wv.height.toFloat()
                                        )
                            }
                            pointer.invalidate()
                        }
                        if (isTap && event.repeatCount == 0) tapPointer()
                    }
                    true
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        if (status.text.startsWith("Opening")) {
                            status.text = "If a “Verify you are human” " +
                                "box appears, tap it (TV: move the " +
                                "pointer onto it and press OK)."
                        }
                    }
                }
                d.show()
                wv.requestFocus()
                wv.post {
                    if (cursorX < 0f && wv.width > 0) {
                        cursorX = wv.width * 0.14f
                        cursorY = wv.height * 0.58f
                        pointer.invalidate()
                    }
                }
                d.window?.setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                sessionDialog = d
                sessionWebView = wv
                sessionStatus = status
                sessionUa = wv.settings.userAgentString
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setStatus(text: String) {
        val status = sessionStatus ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            runCatching { status.text = text }
        }
    }

    /** Run JS in the session WebView and return its string result. */
    private suspend fun evalJs(script: String): String {
        val wv = sessionWebView ?: return ""
        return suspendCancellableCoroutine { cont ->
            try {
                wv.post {
                    try {
                        wv.evaluateJavascript(script) { value ->
                            if (cont.isActive) {
                                cont.resume(cleanJsValue(value))
                            }
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume("")
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("")
            }
        }
    }

    private fun cleanJsValue(value: String?): String {
        if (value == null || value == "null") return ""
        var v = value.trim()
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
        }
        return v.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private const val STATE_JS = """
        (function(){
          var els = document.querySelectorAll(
            'a,button,input[type=button],input[type=submit]');
          for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (!(el.offsetParent !== null ||
                  el.getClientRects().length)) continue;
            var t = (el.innerText || el.value || '').trim();
            if (/generate link|continue/i.test(t)) return 'BTN:' + t;
          }
          if (document.querySelector(
            'iframe[src*="challenges.cloudflare.com"],' +
            '#cf-turnstile,.cf-turnstile')) return 'TURNSTILE';
          return 'PAGE:' + (document.title || '');
        })()
    """

    private const val CLICK_JS = """
        (function(){
          var els = document.querySelectorAll(
            'a,button,input[type=button],input[type=submit]');
          for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (!(el.offsetParent !== null ||
                  el.getClientRects().length)) continue;
            var t = (el.innerText || el.value || '').trim();
            if (/generate link|continue/i.test(t)) {
              el.click();
              return 'CLICKED:' + t;
            }
          }
          return '';
        })()
    """

    /**
     * Walk one step page in the session browser view until it
     * navigates to a HubCloud / HubDrive file page; returns that
     * URL, or null on timeout / cancel.
     */
    suspend fun walk(startUrl: String): String? = sessionMutex.withLock {
        if (!ensureSession()) return null
        val wv = sessionWebView ?: return null
        sessionCancelled.set(false)
        setStatus("Opening the download page…")
        withContext(Dispatchers.Main) {
            runCatching { wv.loadUrl(startUrl) }
        }
        val deadline = System.currentTimeMillis() + 180_000L
        var lastClickAt = 0L
        var sawTurnstile = false
        while (System.currentTimeMillis() < deadline) {
            if (sessionCancelled.get()) return null
            delay(900)
            val current = withContext(Dispatchers.Main) {
                runCatching { wv.url }.getOrNull()
            }
            if (current != null &&
                (current.contains("hubcloud", true) ||
                    current.contains("hubdrive", true))
            ) {
                setStatus("Got the file page — loading servers…")
                return current
            }
            when (val state = evalJs(STATE_JS)) {
                "TURNSTILE" -> {
                    if (!sawTurnstile) {
                        sawTurnstile = true
                        setStatus(
                            "Tap “Verify you are human” in the box " +
                                "below (TV: pointer + OK)."
                        )
                    }
                }
                else -> {
                    if (state.startsWith("BTN:")) {
                        val now = System.currentTimeMillis()
                        if (now - lastClickAt > 2500) {
                            lastClickAt = now
                            setStatus(
                                "Working through the link steps…"
                            )
                            evalJs(CLICK_JS)
                        }
                    }
                }
            }
        }
        null
    }

    /** Session lifecycle hooks used by the provider's loadLinks. */
    fun beginSession() {
        // The session is created lazily by the first walk / solve.
    }

    suspend fun endSession() {
        withContext(Dispatchers.Main) {
            runCatching { sessionDialog?.dismiss() }
            runCatching { sessionWebView?.destroy() }
        }
        sessionDialog = null
        sessionWebView = null
        sessionStatus = null
    }
}
