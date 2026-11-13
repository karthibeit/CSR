package com.myl

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * MYL — MyList. Karthi's own link list, served by a tiny server on
 * his Raspberry Pi at home (mainUrl below): the Pi holds a plain
 * text list of addresses he pastes (HubCloud drive links or any
 * other video address) and this plugin shows them as a movie list.
 * Adding works from any browser at the Pi's address, and from the
 * "Add a link" card at the top of the list (MylUi posts to the Pi).
 *
 * List format (one entry per line): a bare address, or an address
 * and a title separated by a pipe character. Entries without a
 * stored title get one from the address itself — for HubCloud the
 * drive page's title is the full filename.
 *
 * Playback routing by address: HubCloud drive and video pages are
 * resolved in-plugin (drive page -> gamerxyt button page -> the
 * server buttons: FSL, S3, Mega, 10Gbps, BuzzServer, Pixel, Watch
 * Online — the flow ported from the 4KHUB plugin and verified live
 * on 2026-11-12); HubDrive pages hand over to their HubCloud drive
 * address; Pixeldrain and direct file addresses are emitted as-is;
 * anything else goes to the app's built-in extractors. When a
 * HubCloud page comes back as a Cloudflare check (possible on
 * flagged VPN addresses) resolution stops with diagnostics instead
 * of links — the diag lines say so.
 */
class MYLProvider : MainAPI() {
    override var mainUrl = "http://192.168.5.177:8765"
    override var name = "MyList"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/list" to "My List"
    )

    private val addUrl = "$mainUrl/__add__"
    private val infoUrl = "$mainUrl/__info__"

    private data class Entry(val url: String, val storedTitle: String?)

    // ------------------------------------------------------------------
    // The Pi list
    // ------------------------------------------------------------------

    /** Null when the Pi cannot be reached (off home Wi-Fi, Pi down). */
    private suspend fun fetchEntries(): List<Entry>? {
        return try {
            app.get("$mainUrl/list.txt").text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split("|", limit = 2)
                    Entry(
                        parts[0].trim(),
                        parts.getOrNull(1)?.trim()
                            ?.takeIf { it.isNotBlank() }
                    )
                }
                .filter { it.url.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private fun tailTitle(url: String): String {
        val tail = url.substringBefore('?').trimEnd('/')
            .substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(tail, "UTF-8")
        } catch (e: Exception) {
            tail
        }
        return decoded.ifBlank { url }
    }

    private suspend fun titleFor(entry: Entry): String {
        entry.storedTitle?.let { return it }
        if (entry.url.contains("hubcloud", ignoreCase = true)) {
            try {
                val t = app.get(entry.url).document.title().trim()
                if (t.isNotBlank()) return t
            } catch (e: Exception) {
                // fall through to the address tail
            }
        }
        return tailTitle(entry.url)
    }

    private fun yearOf(title: String): Int? =
        Regex("""(?:19|20)\d{2}""").find(title)?.value?.toIntOrNull()

    private fun searchQualityOf(title: String): SearchQuality? = when {
        title.contains("2160") -> SearchQuality.UHD
        title.contains("1080") || title.contains("720") ->
            SearchQuality.HD
        else -> null
    }

    private fun qualityValueOf(title: String): Int = when {
        title.contains("2160") -> Qualities.P2160.value
        title.contains("1440") -> Qualities.P1440.value
        title.contains("1080") -> Qualities.P1080.value
        title.contains("720") -> Qualities.P720.value
        title.contains("480") -> Qualities.P480.value
        title.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun cardOf(title: String, url: String): SearchResponse {
        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.year = yearOf(title)
            this.quality = searchQualityOf(title)
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request, emptyList(), false)
        }
        val cards = mutableListOf<SearchResponse>()
        cards.add(cardOf("➕ Add a link", addUrl))
        val entries = fetchEntries()
        if (entries == null) {
            cards.add(
                cardOf("⚠ Pi not reachable — open on home Wi-Fi", infoUrl)
            )
        } else {
            val titled = coroutineScope {
                entries.map { e -> async { titleFor(e) to e.url } }
                    .awaitAll()
            }
            for ((title, url) in titled) {
                cards.add(cardOf(title, url))
            }
        }
        return newHomePageResponse(request, cards, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val tokens = query.lowercase().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val entries = fetchEntries() ?: return emptyList()
        val titled = coroutineScope {
            entries.map { e -> async { titleFor(e) to e.url } }.awaitAll()
        }
        return titled
            .filter { (title, _) ->
                tokens.all { title.lowercase().contains(it) }
            }
            .map { (title, url) -> cardOf(title, url) }
    }

    // ------------------------------------------------------------------
    // Detail pages
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        if (url == addUrl) {
            MylUi.showAddDialog(mainUrl)
            return newMovieLoadResponse("Add a link", url, TvType.Movie, url) {
                this.plot = "A paste box should have popped up: paste a " +
                    "HubCloud (or any video) link and tap Add — it is " +
                    "saved to your Pi's list. Then go back and reopen " +
                    "MyList to see it. You can also add links from any " +
                    "browser at $mainUrl/ while on home Wi-Fi."
            }
        }
        if (url == infoUrl) {
            return newMovieLoadResponse(
                "Pi not reachable", url, TvType.Movie, url
            ) {
                this.plot = "The plugin could not read the list from " +
                    "your Raspberry Pi at $mainUrl. Check that this " +
                    "device is on your home Wi-Fi and that the Pi's " +
                    "MyList server is running, then reopen MyList."
            }
        }
        val title = titleFor(Entry(url, null))
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.plot = "Your pasted link:\n$url"
            this.year = yearOf(title)
        }
    }

    // ------------------------------------------------------------------
    // HubCloud resolution (ported from the 4KHUB plugin)
    // ------------------------------------------------------------------

    private fun b64DecodeToString(s: String): String {
        var body = s.replace('-', '+').replace('_', '/')
        while (body.length % 4 != 0) body += "="
        val raw = Base64.decode(body, Base64.NO_WRAP)
        return String(raw, Charsets.UTF_8)
    }

    private suspend fun resolveHubCloud(
        driveUrl: String,
        fallbackQuality: Int,
        emit: (ExtractorLink) -> Unit,
        diag: MutableList<String>
    ): Int {
        var emitted = 0
        try {
            fun gamerOf(d: Document): String {
                var g = d.selectFirst("#download")?.attr("href").orEmpty()
                if (g.isNotBlank() && !g.startsWith("http")) {
                    val base = Regex("""^(https?://[^/]+)""")
                        .find(driveUrl)?.groupValues?.get(1).orEmpty()
                    g = base + "/" + g.trimStart('/')
                }
                return g
            }
            val driveDoc = app.get(driveUrl).document
            val gamerUrl = gamerOf(driveDoc)
            if (gamerUrl.isBlank()) {
                diag.add(
                    "hub: no download button on the drive page" +
                        " (title: " + driveDoc.title().take(40) + ")"
                )
                return 0
            }
            val page = app.get(gamerUrl).document
            var buttons: List<Element> = page.select("div.card-body h2 a.btn")
            if (buttons.isEmpty()) buttons = page.select("a.btn[href]")
            if (buttons.isEmpty()) {
                buttons = page.select("a[href]")
                    .filter {
                        it.text().contains("Download", ignoreCase = true) ||
                            it.text().contains("Watch", ignoreCase = true)
                    }
            }
            diag.add("hub: server buttons=" + buttons.size)
            val header = page.selectFirst("div.card-header")?.text()
                .orEmpty()
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
            val direct: suspend (String, String) -> Unit = { u, server ->
                if (u.isNotBlank() && seen.add(u)) {
                    emitted++
                    emit(
                        newExtractorLink(
                            name,
                            "HubCloud [$server]",
                            u,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.referer = gamerUrl
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
                            val dlink = resp.headers["hx-redirect"]
                                .orEmpty()
                            if (dlink.isNotBlank()) {
                                direct(dlink, "BuzzServer")
                            }
                        } catch (e: Exception) {
                            // server may be down; others remain
                        }
                    }
                    text.contains("FSL", ignoreCase = true) ->
                        direct(href, "FSL Server")
                    text.contains("S3 Server", ignoreCase = true) ->
                        direct(href, "S3 Server")
                    text.contains("Mega Server", ignoreCase = true) ->
                        direct(href, "Mega Server")
                    text.contains("10Gbps", ignoreCase = true) ||
                        href.contains("gpdl", ignoreCase = true) ->
                        direct(href, "10Gbps")
                    text.contains("Download File", ignoreCase = true) ->
                        direct(href, "Download")
                    text.contains("pixel", ignoreCase = true) ||
                        href.contains("pixel.", ignoreCase = true) -> {
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
                        try {
                            val decoded = b64DecodeToString(
                                href.substringAfter("?u=")
                                    .substringBefore("&")
                            )
                            if (decoded.startsWith("http")) {
                                direct(decoded, "FSL Server")
                            }
                        } catch (e: Exception) {
                            // other buttons remain
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
            diag.add(
                "hub: " + e.javaClass.simpleName + ": " +
                    (e.message?.take(80) ?: "")
            )
        }
        diag.add("hub: emitted=" + emitted)
        return emitted
    }

    // ------------------------------------------------------------------
    // Playback routing
    // ------------------------------------------------------------------

    private suspend fun emitDiag(
        diag: List<String>,
        data: String,
        callback: (ExtractorLink) -> Unit
    ) {
        for (line in diag) {
            try {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "MYL diag: $line",
                        data,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            } catch (e: Exception) {
                // Diagnostics must never break link loading.
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val diag = mutableListOf<String>()
        var emitted = 0
        val counting: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback.invoke(link)
        }
        val clean = data.substringBefore('?').lowercase()
        try {
            when {
                data.contains("hubcloud", ignoreCase = true) &&
                    (data.contains("/drive/") ||
                        data.contains("/video/")) -> {
                    val title = titleFor(Entry(data, null))
                    resolveHubCloud(
                        data,
                        qualityValueOf(title),
                        counting,
                        diag
                    )
                    if (emitted == 0) {
                        loadExtractor(data, data, subtitleCallback, counting)
                    }
                }
                data.contains("hubdrive", ignoreCase = true) -> {
                    val doc = app.get(data).document
                    val hub = doc.select("a[href]").firstOrNull {
                        it.attr("href").contains("hubcloud", true) &&
                            it.attr("href").contains("/drive/")
                    }?.attr("href")
                    if (hub != null) {
                        resolveHubCloud(
                            hub,
                            qualityValueOf(tailTitle(data)),
                            counting,
                            diag
                        )
                    } else {
                        diag.add("hubdrive: no HubCloud link on the page")
                        loadExtractor(data, data, subtitleCallback, counting)
                    }
                }
                data.contains("pixeldrain.com/u/", ignoreCase = true) -> {
                    val id = data.substringAfter("/u/")
                        .substringBefore('/').substringBefore('?')
                    counting.invoke(
                        newExtractorLink(
                            name,
                            "Pixeldrain",
                            "https://pixeldrain.com/api/file/$id",
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                clean.endsWith(".mkv") || clean.endsWith(".mp4") -> {
                    counting.invoke(
                        newExtractorLink(
                            name,
                            "Direct file",
                            data,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityValueOf(data)
                        }
                    )
                }
                clean.endsWith(".m3u8") -> {
                    counting.invoke(
                        newExtractorLink(
                            name,
                            "Stream",
                            data,
                            ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                else -> {
                    loadExtractor(data, data, subtitleCallback, counting)
                    if (emitted == 0) {
                        diag.add("no extractor handled this address")
                    }
                }
            }
        } catch (e: Exception) {
            diag.add(
                "route: " + e.javaClass.simpleName + ": " +
                    (e.message?.take(80) ?: "")
            )
        }
        if (emitted == 0) {
            emitDiag(diag, data, callback)
            return false
        }
        return true
    }
}
