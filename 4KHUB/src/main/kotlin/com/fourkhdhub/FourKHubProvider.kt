package com.fourkhdhub

import com.fasterxml.jackson.annotation.JsonProperty
import android.app.Activity
import android.app.Dialog
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 4KHUB — CloudStream provider for 4KHDHub (https://4khdhub.one).
 *
 * How the site works (checked live 2026-10-24):
 *  - Listing pages (home, categories, search) render cards as
 *    `a.movie-card` with a TMDB poster, `.movie-card-title` and a
 *    `.movie-card-meta` line ("2026" for movies, "2024 • S01-S03" for
 *    series). Post URLs end in `-movie-<id>/` or `-series-<id>/`.
 *  - Detail pages carry an `h1.page-title` ("Moana (2026)"), an
 *    og:image TMDB poster, an IMDb badge, genre badges, a real plot
 *    paragraph and a metadata list (director / stars / release).
 *  - Movies: one `.download-item` per quality, holding "Download
 *    HubCloud" / "Download HubDrive" buttons. Series: per-episode
 *    `.episode-download-item` blocks (filename carries SxxExx) plus
 *    complete-season `.download-item` packs.
 *  - Every download button points at a redirector
 *    (greenmotors.club/?id=<token>) in front of an interactive
 *    "mediator" page. The chain was reverse-engineered live
 *    (2026-10-24) and is plain HTTP underneath: with the detail page
 *    as referer, greenmotors.club 302s to greenmotors.cc with a
 *    transformed token; the .cc "Redirecting" page carries a script
 *    `s('o','<blob>',...)` whose blob decodes — base64, base64,
 *    ROT13, base64 — to JSON {"w":10,"l":<mediator>,"o":<base64>},
 *    and base64-decoding "o" yields the real HubCloud / HubDrive URL
 *    (verified: Moana's first HubCloud button decodes to the exact
 *    hubcloud.ist/drive/ URL a real browser click reaches).
 *  - v2 (2026-10-24): v1 handed that URL to the app's built-in
 *    extractors and produced "No Links Found" on-device within ~1s
 *    (logcat: MobLand series page, loadLinks ran, zero links), even
 *    though every hop verifies over plain HTTP. v2 therefore
 *    resolves HubCloud itself (drive page -> gamerxyt button page ->
 *    direct file hosts) and only falls back to loadExtractor when
 *    self-resolution emits nothing.
 *  - v3 (2026-10-24): v2 still returned 0 links on-device (the
 *    app's extension tester on the movie "Hope": load() data is
 *    perfect, loadLinks false with 0 links in ~2s, while the same
 *    Hope chain verifies end-to-end from a server). v3 adds
 *    step-by-step diagnostics: every resolve attempt (4 fetch
 *    strategies) and HubCloud stage is logged (tag "4KHUB") and,
 *    when nothing playable is produced, surfaced as "DIAG:" link
 *    names so the exact failing stage is readable on-device.
 *  - v4 (2026-10-24): root cause found — from the user's network,
 *    greenmotors.club serves a Cloudflare "Verify you are human"
 *    challenge (confirmed by his Chrome screenshot), so plain HTTP
 *    requests never receive the mediator page. v4 keeps the v3
 *    diagnostics and adds a WebView Cloudflare solve (the app's
 *    own CloudflareKiller recipe) for the mediator, plus the same
 *    solve-and-retry for the HubCloud drive and gamerxyt pages.
 *    Confirmed on-device the same day: works with the VPN off (his
 *    real IP passes the check silently); with the VPN on, the
 *    distrusted exit IP makes even the hidden solve fail.
 *  - v5 (2026-10-24): when the hidden solve fails, fall back to a
 *    VISIBLE verification dialog (the "verify once — unlocks every
 *    link" flow Karthi showed from another provider): a dialog
 *    WebView on the current Activity (found via ActivityThread
 *    reflection, since plugins only get the application context)
 *    lets the user tap the Cloudflare checkbox; once cf_clearance
 *    exists, the dialog closes and the solved headers are cached
 *    per host and replayed as before.
 *  - Detail pages also embed a Videasy player iframe; its TMDB id is
 *    kept as a "Watch Online" link entry as a fallback.
 */
class FourKHubProvider : MainAPI() {

    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHUB"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Main-page sections, in the site's own menu order (Karthi's
    // screenshot, 2026-10-24): Home, Movies, Web Series, OTT, then
    // Anime / 4K HDR / Top IMDb.
    override val mainPage = mainPageOf(
        "/" to "Home",
        "/category/movies/" to "Latest Movies",
        "/category/hindi-movies/" to "Hindi Movies",
        "/category/english-movies/" to "English Movies",
        "/category/series/" to "Latest Episodes",
        "/category/korean-series/" to "Korean",
        "/category/drama-series/" to "Drama",
        "/category/hindi-series/" to "Hindi Series",
        "/category/english-series/" to "English Series",
        "/category/netflix/" to "Netflix",
        "/category/amazon_prime_video/" to "Amazon Prime Video",
        "/category/jiohotstar/" to "JioHotstar",
        "/category/disney/" to "Disney+",
        "/category/apple_tv/" to "Apple TV+",
        "/category/hbo_max/" to "HBO Max",
        "/category/hulu/" to "Hulu",
        "/category/crave/" to "Crave",
        "/category/anime/" to "Anime",
        "/category/2160p-HDR/" to "4K HDR",
        "/category/imdb/" to "Top IMDb"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = mainUrl + request.data
        val url = if (page <= 1) base else base + "page/$page/"
        val items = app.get(url).document.toCards()
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        return app.get(url).document.toCards()
    }

    private fun Document.toCards(): List<SearchResponse> {
        return select("a.movie-card").mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            val title = a.selectFirst(".movie-card-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.attr("aria-label").removeSuffix("details").trim()
            if (title.isBlank()) return@mapNotNull null
            val img = a.selectFirst(".movie-card-image img, img")
            val poster = fixUrlNull(
                img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src")
            )
            val meta = a.selectFirst(".movie-card-meta")?.text().orEmpty()
            val year = Regex("""(19|20)\d{2}""").find(meta)?.value?.toIntOrNull()
            val isSeries = href.contains("-series-") ||
                Regex("""S\d{2}""").containsMatchIn(meta)
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }.distinctBy { it.url }
    }

    data class HubLink(
        @JsonProperty("url") val url: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("ref") val ref: String = ""
    )

    /** Download buttons of one block (a .download-item or an episode item). */
    private fun org.jsoup.nodes.Element.downloadButtons(
        label: String,
        pageUrl: String
    ): List<HubLink> {
        return select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.startsWith("http") || href.contains("4khdhub")) {
                return@mapNotNull null
            }
            val host = a.text().replace("Download", "").trim()
                .ifBlank { "Download" }
            HubLink(url = href, name = "$label — $host", ref = pageUrl)
        }
    }

    private fun parseQuality(text: String): Int {
        return when {
            Regex("""2160p|4K|UHD""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) -> Qualities.P2160.value
            Regex("""1440p""").containsMatchIn(text) -> Qualities.P1440.value
            Regex("""1080p""").containsMatchIn(text) -> Qualities.P1080.value
            Regex("""720p""").containsMatchIn(text) -> Qualities.P720.value
            Regex("""480p""").containsMatchIn(text) -> Qualities.P480.value
            Regex("""360p""").containsMatchIn(text) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.page-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")?.substringBefore(" - 4K")?.trim()
            ?: throw ErrorLoadingException("No title found")
        val titleYear = Regex("""\((19|20)\d{2}\)""").find(rawTitle)
        val year = titleYear?.groupValues?.get(1)?.toIntOrNull()
        val title = rawTitle.replace(Regex("""\s*\((?:19|20)\d{2}\)\s*$"""), "").trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val plot = document.select(".content-main p.mt-4")
            .firstOrNull { it.text().trim().length > 40 }?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")
                ?.attr("content")
        // The pinned CloudStream library removed LoadResponse.rating
        // (DeprecationLevel.ERROR) in favour of Score — Build #36
        // failed on this, 2026-10-24.
        val score = document.selectFirst(".imdb-badge")?.text()
            ?.let { Regex("""\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
            ?.let { Score.from10(it) }
        val tags = document.select(".badge-outline")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val actors = document.select(".metadata-item")
            .firstOrNull { it.text().startsWith("Stars:") }
            ?.text()?.removePrefix("Stars:")?.split(",")
            ?.map { it.replace(Regex("""\(.*?\)"""), "").trim() }
            ?.filter { it.isNotBlank() }
            ?.map { ActorData(Actor(it)) }

        // The site's own online player (Videasy embed, keyed by TMDB id).
        val playerUrl = document.selectFirst("iframe[src*=videasy]")
            ?.attr("src")?.takeIf { it.isNotBlank() }

        val episodeItems = document.select(".episode-download-item")
        val isSeries = url.contains("-series-") || episodeItems.isNotEmpty()

        if (!isSeries) {
            val links = mutableListOf<HubLink>()
            document.select(".download-item").forEach { item ->
                val label = item.selectFirst(".download-header")
                    ?.text()?.trim().orEmpty()
                links += item.downloadButtons(label.ifBlank { title }, url)
            }
            if (playerUrl != null) {
                links += HubLink(playerUrl, "Watch Online — Videasy", url)
            }
            return newMovieLoadResponse(title, url, TvType.Movie, links.toJson()) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = score
                this.tags = tags
                this.actors = actors
            }
        }

        // Series: group per-episode files by (season, episode).
        val episodeLinks = sortedMapOf<Pair<Int, Int>, MutableList<HubLink>>(
            compareBy({ it.first }, { it.second })
        )
        episodeItems.forEach { item ->
            val file = item.selectFirst(".episode-file-title")
                ?.text()?.trim().orEmpty()
            val m = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE)
                .find(file) ?: return@forEach
            val key = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            val short = file.replace(Regex("""\.(mkv|mp4|zip)$""", RegexOption.IGNORE_CASE), "")
            episodeLinks.getOrPut(key) { mutableListOf() }
                .addAll(item.downloadButtons(short, url))
        }

        // Complete-season packs: attach to that season's first episode,
        // or stand alone as the season's episode 1 when the season has
        // no individual episodes.
        val packBySeason = mutableMapOf<Int, MutableList<HubLink>>()
        document.select(".download-item").forEach { item ->
            val label = item.selectFirst(".download-header")
                ?.text()?.trim().orEmpty()
            val season = Regex("""S(\d{1,2})\b""").find(label)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val buttons = item.downloadButtons("Season Pack — $label", url)
            if (buttons.isNotEmpty()) {
                packBySeason.getOrPut(season) { mutableListOf() }.addAll(buttons)
            }
        }
        packBySeason.forEach { (season, packs) ->
            val firstEp = episodeLinks.keys
                .filter { it.first == season }.minByOrNull { it.second }
                ?: (season to 1)
            val existing = episodeLinks.getOrPut(firstEp) { mutableListOf() }
            existing.addAll(0, packs)
        }

        val episodes = episodeLinks.map { (key, links) ->
            newEpisode(links.toJson()) {
                this.season = key.first
                this.episode = key.second
                this.name = "S${key.first} E${key.second}"
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.score = score
            this.tags = tags
            this.actors = actors
        }
    }

    // ------------------------------------------------------------------
    // greenmotors mediator resolution (see the class header for the
    // full protocol). The .cc "Redirecting" page hides the payload in
    // a script call: s('o','<blob>',...)
    // ------------------------------------------------------------------

    private fun extractBlob(html: String): String? =
        Regex("""s\('o','([A-Za-z0-9+/=]+)'""").find(html)?.groupValues?.get(1)

    private fun b64DecodeToString(s: String): String =
        String(
            android.util.Base64.decode(s, android.util.Base64.DEFAULT),
            Charsets.ISO_8859_1
        )

    private fun rot13(s: String): String = buildString(s.length) {
        for (c in s) {
            append(
                when (c) {
                    in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                    in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                    else -> c
                }
            )
        }
    }

    /** blob -> base64 -> base64 -> ROT13 -> base64 -> JSON {"o": base64} -> URL. */
    private fun decodeMediatorBlob(blob: String): String? {
        return try {
            val layer1 = b64DecodeToString(blob)
            val layer2 = b64DecodeToString(layer1)
            val jsonText = b64DecodeToString(rot13(layer2))
            val o = Regex(""""o"\s*:\s*"([^"]+)"""").find(jsonText)
                ?.groupValues?.get(1) ?: return null
            b64DecodeToString(o).takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private fun browserHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36",
        "Referer" to referer.ifBlank { "$mainUrl/" }
    )

    // v3 diagnostics: v1 and v2 both returned "No Links Found"
    // on-device within ~2s while every hop of the chain verifies
    // over plain HTTP from a server, so v3 records each step and —
    // when nothing playable was produced — surfaces the steps as
    // link names the user can read in the player / extension tester.
    private fun diagLog(diag: MutableList<String>?, msg: String) {
        diag?.add(msg)
        try {
            android.util.Log.i("4KHUB", msg)
        } catch (e: Throwable) {
            // logging must never break link loading
        }
    }

    private fun shortUrl(u: String): String =
        u.removePrefix("https://").removePrefix("http://").take(48)

    // v4: Cloudflare. From the user's network, greenmotors.club
    // answers plain HTTP clients with a "Verify you are human"
    // challenge page (confirmed in his Chrome), so no blob ever
    // arrives and every resolve fails instantly. The fix is the
    // app's own CloudflareKiller recipe: solve the challenge once
    // in a real WebView, then replay requests with the WebView's
    // user agent + cf_clearance cookie. Solved headers are cached
    // per host; hosts whose solve failed are not retried.
    private val cfSolvedHeaders = mutableMapOf<String, Map<String, String>>()
    private val cfSolveFailedHosts = mutableSetOf<String>()
    private val cfInteractiveFailedHosts = mutableSetOf<String>()

    private suspend fun solveCloudflare(
        url: String,
        diag: MutableList<String>? = null,
        interactive: Boolean = false
    ): Map<String, String> {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?: return emptyMap()
        cfSolvedHeaders[host]?.let { return it }
        if (host !in cfSolveFailedHosts) {
            val solved = try {
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
                diagLog(
                    diag,
                    "cf-solve $host: clearance=${cookie?.contains("cf_clearance") == true}"
                )
                if (cookie.isNullOrBlank()) {
                    null
                } else {
                    val headers = mutableMapOf("Cookie" to cookie)
                    WebViewResolver.webViewUserAgent?.let {
                        headers["User-Agent"] = it
                    }
                    headers
                }
            } catch (e: Exception) {
                diagLog(
                    diag,
                    "cf-solve $host: EXC ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                )
                null
            }
            if (solved != null) {
                cfSolvedHeaders[host] = solved
                return solved
            }
            cfSolveFailedHosts += host
        }
        // Hidden solving failed (e.g. the network's IP is distrusted
        // and Cloudflare demands a human tap): fall back to a
        // visible verification dialog the user can tap, the same
        // "verify once" flow some other providers use.
        if (interactive && host !in cfInteractiveFailedHosts) {
            val solved = solveCloudflareInteractive(url, diag)
            if (solved.isNotEmpty()) {
                cfSolvedHeaders[host] = solved
                return solved
            }
            cfInteractiveFailedHosts += host
        }
        return emptyMap()
    }

    /**
     * The currently resumed Activity, found via ActivityThread
     * reflection (plugins only ever see the application context,
     * which cannot host a dialog). Null when unavailable — the
     * caller then simply keeps the hidden-solve behaviour.
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
                val activityField = record.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                (activityField.get(record) as? Activity)?.let { return it }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v5: visible Cloudflare verification. Shows a dialog with a
     * real WebView on the challenge URL; the user taps "Verify you
     * are human" if asked, and as soon as a cf_clearance cookie
     * exists the dialog closes and the solved headers (WebView UA
     * + cookies) are returned for replay — once per host, the
     * clearance then unlocks every link.
     */
    private suspend fun solveCloudflareInteractive(
        url: String,
        diag: MutableList<String>? = null
    ): Map<String, String> {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val activity = currentActivity()
        if (activity == null) {
            diagLog(diag, "cf-interactive $host: no activity found")
            return emptyMap()
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var dialog: Dialog? = null
        var webView: WebView? = null
        var webViewUa: String? = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val density = activity.resources.displayMetrics.density
                fun dp(v: Int): Int = (v * density).toInt()
                val container = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(12))
                }
                val title = TextView(activity).apply {
                    text = "Verify once — unlocks every link"
                    textSize = 18f
                    setPadding(0, 0, 0, dp(12))
                }
                val wv = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
                val status = TextView(activity).apply {
                    text = "Widget loaded — solving…"
                    setPadding(0, dp(12), 0, 0)
                }
                container.addView(title)
                container.addView(
                    wv,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(430)
                    )
                )
                container.addView(status)
                val d = Dialog(activity)
                d.setContentView(container)
                d.setCancelable(true)
                d.setOnCancelListener { cancelled.set(true) }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, u: String?) {
                        status.text =
                            "If a checkbox is shown above, tap \u201cVerify you are human\u201d."
                    }
                }
                webViewUa = wv.settings.userAgentString
                wv.loadUrl(url)
                d.show()
                d.window?.setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                dialog = d
                webView = wv
            }
            diagLog(diag, "cf-interactive $host: dialog shown")
            val deadline = System.currentTimeMillis() + 120_000L
            while (System.currentTimeMillis() < deadline) {
                if (cancelled.get()) break
                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(url)
                }.getOrNull()
                if (cookie?.contains("cf_clearance") == true) {
                    diagLog(diag, "cf-interactive $host: clearance OK")
                    val headers = mutableMapOf("Cookie" to cookie)
                    webViewUa?.let { headers["User-Agent"] = it }
                    return headers
                }
                kotlinx.coroutines.delay(600)
            }
            diagLog(
                diag,
                "cf-interactive $host: no clearance (cancelled=${cancelled.get()})"
            )
        } catch (e: Exception) {
            diagLog(
                diag,
                "cf-interactive $host: EXC ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            )
        } finally {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { dialog?.dismiss() }
                    runCatching { webView?.destroy() }
                }
            }
        }
        return emptyMap()
    }

    /**
     * Resolve a greenmotors mediator link to the real HubCloud /
     * HubDrive URL. Tries, in order: (A) browser headers with
     * redirects followed, (B) the referer parameter, (C) a manual
     * Location follow (the .cc step wants the .club origin as
     * referer), (D) a plain request with no headers. The winning
     * attempt's page carries the `s('o','<blob>',...)` payload whose
     * decoding yields the final URL.
     */
    private suspend fun resolveRedirector(
        url: String,
        referer: String,
        diag: MutableList<String>? = null,
        fullAttempts: Boolean = false
    ): String? {
        if (!url.contains("greenmotors")) return url

        fun inspect(tag: String, text: String, finalUrl: String, code: Int): String? {
            val blob = extractBlob(text)
            diagLog(
                diag,
                "$tag: HTTP $code final=${shortUrl(finalUrl)} len=${text.length} blob=${blob != null}"
            )
            if (blob == null) return null
            val decoded = decodeMediatorBlob(blob)
            diagLog(diag, "$tag: decode=${decoded?.let { shortUrl(it) } ?: "FAILED"}")
            return decoded
        }

        // Attempt A: browser headers, redirects followed. When a
        // Cloudflare clearance was already solved for this host,
        // its headers (WebView UA + cf_clearance cookie) replace
        // the generic ones — the clearance is bound to that UA.
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val cachedCf = host?.let { cfSolvedHeaders[it] }
        try {
            val aHeaders = if (cachedCf != null) {
                browserHeaders(referer) + cachedCf
            } else {
                browserHeaders(referer)
            }
            val page = app.get(url, headers = aHeaders)
            inspect("A", page.text, page.url, runCatching { page.code }.getOrDefault(-1))
                ?.let { return it }
        } catch (e: Exception) {
            diagLog(diag, "A: EXC ${e.javaClass.simpleName}: ${e.message?.take(90)}")
        }
        if (!fullAttempts) return null

        // Attempt B: referer parameter (the v1 style).
        try {
            val page = app.get(url, referer = referer.ifBlank { "$mainUrl/" })
            inspect("B", page.text, page.url, runCatching { page.code }.getOrDefault(-1))
                ?.let { return it }
        } catch (e: Exception) {
            diagLog(diag, "B: EXC ${e.javaClass.simpleName}: ${e.message?.take(90)}")
        }

        // Attempt C: no redirect following; follow Location manually.
        try {
            val first = app.get(
                url,
                headers = browserHeaders(referer),
                allowRedirects = false
            )
            val loc = first.headers["Location"]?.takeIf { it.isNotBlank() }
            diagLog(
                diag,
                "C: HTTP ${runCatching { first.code }.getOrDefault(-1)} location=${loc?.let { shortUrl(it) }}"
            )
            if (loc != null) {
                val page = app.get(
                    loc,
                    headers = browserHeaders("https://greenmotors.club/")
                )
                inspect("C2", page.text, page.url, runCatching { page.code }.getOrDefault(-1))
                    ?.let { return it }
            } else {
                inspect("C", first.text, first.url, runCatching { first.code }.getOrDefault(-1))
                    ?.let { return it }
            }
        } catch (e: Exception) {
            diagLog(diag, "C: EXC ${e.javaClass.simpleName}: ${e.message?.take(90)}")
        }

        // Attempt D: plain request, no headers at all.
        try {
            val page = app.get(url)
            inspect("D", page.text, page.url, runCatching { page.code }.getOrDefault(-1))
                ?.let { return it }
        } catch (e: Exception) {
            diagLog(diag, "D: EXC ${e.javaClass.simpleName}: ${e.message?.take(90)}")
        }

        // Attempt E (v4): the plain attempts all received a
        // Cloudflare challenge page instead of the mediator page.
        // Solve the challenge in a WebView, replay with the solved
        // headers; if the replay's redirect target is challenged
        // too, follow it manually and solve that host as well.
        try {
            if (cachedCf != null && host != null) {
                // Stale clearance: force a fresh solve.
                cfSolvedHeaders.remove(host)
                cfSolveFailedHosts.remove(host)
            }
            val solved = solveCloudflare(url, diag, interactive = true)
            if (solved.isNotEmpty()) {
                val headers = solved.toMutableMap()
                headers["Referer"] = referer.ifBlank { "$mainUrl/" }
                val page = app.get(url, headers = headers)
                inspect("E", page.text, page.url, runCatching { page.code }.getOrDefault(-1))
                    ?.let { return it }
                val first = app.get(
                    url, headers = headers, allowRedirects = false
                )
                val loc = first.headers["Location"]?.takeIf { it.isNotBlank() }
                if (loc != null && loc.contains("greenmotors")) {
                    val solvedCc = solveCloudflare(loc, diag, interactive = true)
                    val ccHeaders = (solvedCc.ifEmpty { solved }).toMutableMap()
                    if (!ccHeaders.containsKey("User-Agent")) {
                        solved["User-Agent"]?.let { ccHeaders["User-Agent"] = it }
                    }
                    ccHeaders["Referer"] = "https://greenmotors.club/"
                    val ccPage = app.get(loc, headers = ccHeaders)
                    inspect(
                        "E2", ccPage.text, ccPage.url,
                        runCatching { ccPage.code }.getOrDefault(-1)
                    )?.let { return it }
                }
            }
        } catch (e: Exception) {
            diagLog(diag, "E: EXC ${e.javaClass.simpleName}: ${e.message?.take(90)}")
        }
        return null
    }

    /**
     * Self-contained HubCloud resolution, mirroring the app's built-in
     * HubCloud extractor flow (verified live 2026-10-24): the drive
     * page's #download button leads to a gamerxyt "hubcloud.php" page
     * whose buttons are the real file hosts (FSL / S3 / Pixeldrain /
     * BuzzServer / direct). Returns how many links were emitted.
     */
    private suspend fun resolveHubCloud(
        driveUrl: String,
        label: String,
        fallbackQuality: Int,
        emit: (ExtractorLink) -> Unit,
        diag: MutableList<String>? = null
    ): Int {
        var emitted = 0
        try {
            fun gamerOf(d: Document): String {
                var g = d.selectFirst("#download")?.attr("href").orEmpty()
                if (g.isNotBlank() && !g.startsWith("http")) {
                    val base = Regex("""^(https?://[^/]+)""").find(driveUrl)
                        ?.groupValues?.get(1).orEmpty()
                    g = base + "/" + g.trimStart('/')
                }
                return g
            }
            var gamerUrl = gamerOf(app.get(driveUrl).document)
            if (gamerUrl.isBlank()) {
                // Possibly a Cloudflare challenge page instead of
                // the drive page: solve in a WebView and retry once
                // with the clearance headers.
                val solved = solveCloudflare(driveUrl, diag, interactive = true)
                if (solved.isNotEmpty()) {
                    gamerUrl = gamerOf(app.get(driveUrl, headers = solved).document)
                }
            }
            if (gamerUrl.isBlank()) {
                diagLog(diag, "hub: NO #download button on drive page")
                return 0
            }

            var page = app.get(gamerUrl).document
            var buttons = page.select("div.card-body h2 a.btn")
            if (buttons.isEmpty()) buttons = page.select("a.btn[href]")
            if (buttons.isEmpty()) {
                // Possibly a Cloudflare challenge page here too:
                // solve and retry once with the clearance headers.
                val solvedG = solveCloudflare(gamerUrl, diag, interactive = true)
                if (solvedG.isNotEmpty()) {
                    page = app.get(gamerUrl, headers = solvedG).document
                    buttons = page.select("div.card-body h2 a.btn")
                    if (buttons.isEmpty()) buttons = page.select("a.btn[href]")
                }
            }
            diagLog(diag, "hub: gamer page buttons=${buttons.size}")
            val header = page.selectFirst("div.card-header")?.text().orEmpty()
            val quality = Regex("""(\d{3,4})[pP]""").find(header)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?.let { q ->
                    when (q) {
                        2160 -> Qualities.P2160.value
                        1440 -> Qualities.P1440.value
                        1080 -> Qualities.P1080.value
                        720 -> Qualities.P720.value
                        480 -> Qualities.P480.value
                        360 -> Qualities.P360.value
                        else -> fallbackQuality
                    }
                } ?: fallbackQuality

            val seen = mutableSetOf<String>()
            // Suspend lambda, not a local fun: in the pinned library
            // newExtractorLink is a suspend function, and Build #39
            // failed calling it from a plain nested fun.
            val direct: suspend (String, String) -> Unit = { url, server ->
                if (url.isNotBlank() && seen.add(url)) {
                    emitted++
                    emit(
                        newExtractorLink(
                            name, "$label [$server]", url, ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = mainUrl
                        }
                    )
                }
            }

            for (el in buttons) {
                val href = el.attr("href")
                val text = el.text()
                if (href.isBlank()) continue
                when {
                    text.contains("BuzzServer", ignoreCase = true) -> {
                        try {
                            val resp = app.get(
                                "$href/download",
                                referer = href,
                                allowRedirects = false
                            )
                            val dlink = resp.headers["hx-redirect"].orEmpty()
                            if (dlink.isNotBlank()) direct(dlink, "BuzzServer")
                        } catch (e: Exception) {
                            // server may be down; other buttons remain
                        }
                    }
                    text.contains("FSL", ignoreCase = true) ->
                        direct(href, "FSL Server")
                    text.contains("S3 Server", ignoreCase = true) ->
                        direct(href, "S3 Server")
                    text.contains("Mega Server", ignoreCase = true) ->
                        direct(href, "Mega Server")
                    text.contains("Download File", ignoreCase = true) ->
                        direct(href, "Download")
                    text.contains("pixel", ignoreCase = true) ||
                        href.contains("pixel.", ignoreCase = true) -> {
                        // pixel.hubcloud.ist/?id=... redirects to the
                        // file itself; follow it and emit the target.
                        try {
                            val resp = app.get(href)
                            val finalUrl = resp.url.takeIf {
                                it != href &&
                                    (it.contains(".mkv", true) ||
                                        it.contains(".mp4", true) ||
                                        resp.headers["Content-Type"]
                                            ?.startsWith("video") == true)
                            } ?: href
                            direct(finalUrl, "Pixel Server")
                        } catch (e: Exception) {
                            direct(href, "Pixel Server")
                        }
                    }
                    text.contains("Watch Online", ignoreCase = true) &&
                        href.contains("?u=") -> {
                        // hbplay embed: the u parameter is the base64
                        // of the direct file URL.
                        try {
                            val decoded = b64DecodeToString(
                                href.substringAfter("?u=")
                                    .substringBefore("&")
                            )
                            if (decoded.startsWith("http")) {
                                direct(decoded, "FSL Server")
                            }
                        } catch (e: Exception) {
                            // ignore; other buttons remain
                        }
                    }
                    else -> {
                        if (loadExtractor(href, gamerUrl, { }, emit)) {
                            emitted++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            diagLog(diag, "hub: EXC ${e.javaClass.simpleName}: ${e.message?.take(90)}")
            // fall through; caller tries the built-in extractor
        }
        diagLog(diag, "hub: emitted=$emitted")
        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emittedCount = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emittedCount++
            callback.invoke(link)
        }

        // Primary path: the JSON list stored by load(). Fallback:
        // scrape the url fields out of the raw data, or treat the
        // data itself as a single link — never fail silently.
        var links: List<HubLink> = try {
            parseJson(data)
        } catch (e: Exception) {
            emptyList()
        }
        if (links.isEmpty()) {
            val urls = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                .findAll(data)
                .map { it.groupValues[1].replace("\\/", "/") }
                .filter { it.startsWith("http") }
                .toList()
            links = if (urls.isNotEmpty()) {
                urls.map { HubLink(it, name, mainUrl) }
            } else if (data.trim().startsWith("http")) {
                listOf(HubLink(data.trim(), name, mainUrl))
            } else {
                emptyList()
            }
        }

        val diag = mutableListOf<String>()
        diagLog(diag, "loadLinks v3: ${links.size} stored link(s)")
        var hubDiagUsed = false
        var hubDriveDiagUsed = false
        for ((index, link) in links.withIndex()) {
            val resolved = resolveRedirector(
                link.url, link.ref, diag, fullAttempts = index == 0
            ) ?: continue
            when {
                resolved.contains("videasy") -> {
                    // No built-in extractor; hand the embed to the
                    // generic extractors as a last resort.
                    loadExtractor(resolved, mainUrl, subtitleCallback, counting)
                }
                resolved.contains("hubcloud", ignoreCase = true) -> {
                    val n = resolveHubCloud(
                        resolved,
                        link.name,
                        parseQuality(link.name),
                        counting,
                        if (hubDiagUsed) null else diag
                    )
                    hubDiagUsed = true
                    if (n == 0) {
                        // Self-resolution found nothing (layout may
                        // have changed): let the built-in extractor
                        // try the same URL.
                        val ok = loadExtractor(
                            resolved, mainUrl, subtitleCallback, counting
                        )
                        diagLog(diag, "hub: built-in extractor=$ok")
                    }
                }
                resolved.contains("hubdrive", ignoreCase = true) ||
                    resolved.contains("hubcdn", ignoreCase = true) -> {
                    val before = emittedCount
                    val ok = loadExtractor(
                        resolved, mainUrl, subtitleCallback, counting
                    )
                    if (!hubDriveDiagUsed) {
                        hubDriveDiagUsed = true
                        diagLog(
                            diag,
                            "hubdrive: extractor=$ok emitted=${emittedCount - before}"
                        )
                    }
                }
                resolved != link.url -> {
                    // Some other host after the redirector: if it is a
                    // direct media file, surface it; otherwise still
                    // give the generic extractors a chance.
                    if (Regex("""\.(mkv|mp4|m3u8)(\?|$)""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(resolved)
                    ) {
                        counting.invoke(
                            newExtractorLink(name, link.name, resolved, ExtractorLinkType.VIDEO) {
                                this.quality = parseQuality(link.name)
                                this.referer = mainUrl
                            }
                        )
                    } else {
                        loadExtractor(resolved, mainUrl, subtitleCallback, counting)
                    }
                }
            }
        }
        // v3 diagnostics: if nothing playable was produced, surface
        // the recorded steps as link names so the failure stage is
        // readable in the player / tester instead of a bare
        // "No Links Found".
        var diagShown = false
        if (emittedCount == 0 && diag.isNotEmpty()) {
            diag.take(10).forEach { line ->
                try {
                    callback.invoke(
                        newExtractorLink(
                            name, "DIAG: $line", "$mainUrl/", ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    diagShown = true
                } catch (e: Exception) {
                    // diagnostics must never break the caller
                }
            }
        }
        return emittedCount > 0 || diagShown
    }
}
