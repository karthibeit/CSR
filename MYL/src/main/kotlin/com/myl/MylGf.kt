package com.myl

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
import kotlinx.coroutines.CoroutineScope
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
 * Browser-view GoFile resolution for MyList. GoFile's public
 * API refuses guest accounts ("error-notPremium", verified
 * 2026-11-13 against two shares), so the app's built-in GoFile
 * extractor cannot list a share — but GoFile's own web page
 * works fine as a guest in a real browser: it lists the file
 * and its Download button starts the real file. This opens the
 * share page in a visible browser view (the session dialog
 * pattern from the KM plugin, with the TV-remote pointer),
 * lets the page build its guest session, clicks the file's
 * Download button, and captures the download address through
 * the WebView download listener — the view itself never
 * downloads the file, so the one-use address is not consumed.
 * The captured address is emitted together with the browser
 * session's cookies, which GoFile's file servers expect.
 */
object MylGf {

    data class Capture(
        val url: String,
        val filename: String,
        val cookie: String
    )

    private val sessionMutex = Mutex()
    private val sessionCancelled = AtomicBoolean(false)

    private var sessionDialog: Dialog? = null
    private var sessionWebView: WebView? = null
    private var sessionStatus: TextView? = null

    @Volatile
    private var lastDownloadUrl: String? = null

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull() ?: url

    private fun currentActivity(): Activity? {
        return try {
            val thread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
                ?: return null
            val field = thread.javaClass.getDeclaredField("mActivities")
            field.isAccessible = true
            val activities = field.get(thread) as? Map<*, *> ?: return null
            for (record in activities.values) {
                if (record == null) continue
                val pausedField = record.javaClass
                    .getDeclaredField("paused")
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
            .replace("\\u0026", "&")
    }

    /** Probe: page title + the first video filename on the page. */
    private const val PROBE_JS = """
        (function(){
          var t = document.body ? (document.body.innerText || '') : '';
          var m = t.match(/[^\n]*\.(mkv|mp4|avi|mov|webm)[^\n]*/i);
          return (document.title || '') + '|' +
            (m ? m[0].trim() : '');
        })()
    """

    /** Click the Download button of the (first) video file row. */
    private const val CLICK_JS = """
        (function(){
          function isVideo(t){
            return /\.(mkv|mp4|avi|mov|webm)/i.test(t || '');
          }
          function findDownload(scope){
            var bs = scope.querySelectorAll(
              'a,button,[role="button"]');
            for (var j = 0; j < bs.length; j++) {
              var bt = (bs[j].textContent || '')
                .trim().toLowerCase();
              if (bt === 'download' || bt.indexOf('download') === 0) {
                return bs[j];
              }
            }
            return null;
          }
          var els = document.querySelectorAll('*');
          var row = null;
          for (var i = 0; i < els.length; i++) {
            var t = els[i].textContent || '';
            if (isVideo(t) && t.length < 300) row = els[i];
          }
          var scope = row;
          for (var up = 0; up < 8 && scope; up++) {
            var b = findDownload(scope);
            if (b) { b.click(); return 'CLICKED_ROW'; }
            scope = scope.parentElement;
          }
          var any = findDownload(document);
          if (any) { any.click(); return 'CLICKED_ANY'; }
          return 'NONE';
        })()
    """

    private fun cookiesFor(url: String): String =
        try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (e: Exception) {
            ""
        }

    private val filenameRegex =
        Regex("""[^\s/]+\.(?:mkv|mp4|avi|mov|webm)""", RegexOption.IGNORE_CASE)

    /**
     * Open the GoFile share in the session browser view and
     * capture its file download. Null on timeout or cancel;
     * stage notes are appended to diag.
     */
    suspend fun capture(
        pageUrl: String,
        diag: MutableList<String>,
        timeoutMs: Long = 100_000L
    ): Capture? = sessionMutex.withLock {
        if (!ensureSession()) {
            diag.add("gf: no activity for the browser view")
            return null
        }
        val wv = sessionWebView ?: return null
        sessionCancelled.set(false)
        lastDownloadUrl = null
        setStatus("Opening GoFile — the download starts by itself…")
        withContext(Dispatchers.Main) {
            runCatching { wv.loadUrl(pageUrl) }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var filename = ""
        var lastClick = 0L
        var clicks = 0
        while (System.currentTimeMillis() < deadline) {
            if (sessionCancelled.get()) {
                diag.add("gf: cancelled")
                return null
            }
            delay(1000)
            lastDownloadUrl?.let { dl ->
                val cookie = cookiesFor(dl).ifBlank {
                    cookiesFor(pageUrl)
                }
                diag.add("gf: captured download for $filename")
                return Capture(dl, filename, cookie)
            }
            val probe = evalJs(PROBE_JS)
            val found = probe.substringAfter('|', "").trim()
            if (found.isNotBlank() && filename.isBlank()) {
                filename = filenameRegex.find(found)?.value ?: ""
                if (filename.isNotBlank()) {
                    diag.add("gf: page ready, file=$filename")
                    setStatus(
                        "Found $filename — starting its download " +
                            "to capture the link…"
                    )
                }
            }
            if (filename.isNotBlank() &&
                System.currentTimeMillis() - lastClick > 6000
            ) {
                val currentHost = withContext(Dispatchers.Main) {
                    runCatching { wv.url?.let { hostOf(it) } }
                        .getOrNull()
                }
                if (currentHost?.contains("gofile") == true) {
                    lastClick = System.currentTimeMillis()
                    clicks++
                    val res = evalJs(CLICK_JS)
                    diag.add("gf: click $clicks -> $res")
                }
            }
        }
        diag.add(
            "gf: capture timed out (file seen: " +
                filename.ifBlank { "no" } + ", clicks: $clicks)"
        )
        null
    }

    /** Build (once) the shared session dialog + WebView. */
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
                    text = "MyList — GoFile page"
                    textSize = 17f
                }
                val wv = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setDownloadListener { url, _, _, _, _ ->
                        if (url != null) lastDownloadUrl = url
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
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
                            cursorX, cursorY, dp(14).toFloat(),
                            cursorPaint
                        )
                        cursorPaint.style = Paint.Style.FILL
                        cursorPaint.color = Color.rgb(255, 87, 34)
                        canvas.drawCircle(
                            cursorX, cursorY, dp(4).toFloat(),
                            cursorPaint
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
                    text = "Opening the GoFile page… TV remote: " +
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
                    if (!isMove && !isTap) {
                        return@setOnKeyListener false
                    }
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
                                        .coerceAtMost(wv.width.toFloat())
                                KeyEvent.KEYCODE_DPAD_UP ->
                                    cursorY = (cursorY - step)
                                        .coerceAtLeast(0f)
                                KeyEvent.KEYCODE_DPAD_DOWN ->
                                    cursorY = (cursorY + step)
                                        .coerceAtMost(wv.height.toFloat())
                            }
                            pointer.invalidate()
                        }
                        if (isTap && event.repeatCount == 0) {
                            tapPointer()
                        }
                    }
                    true
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        u: String?
                    ) {
                        if (status.text.startsWith("Opening")) {
                            status.text = "GoFile is preparing the " +
                                "file — the download starts by " +
                                "itself, no tap needed."
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
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setStatus(text: String) {
        val status = sessionStatus ?: return
        CoroutineScope(Dispatchers.Main).launch {
            runCatching { status.text = text }
        }
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
