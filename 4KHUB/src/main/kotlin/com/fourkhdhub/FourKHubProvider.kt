package com.fourkhdhub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
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

    /**
     * Resolve a greenmotors mediator link to the real HubCloud /
     * HubDrive URL. v2: sends explicit browser headers (the v1 build
     * relied on the client's referer parameter and its built-in
     * extractors — on-device that produced "No Links Found" within a
     * second even though the same chain works over plain HTTP, so v2
     * resolves every hop itself). Handles both client behaviours: if
     * the HTTP client follows the .club -> .cc redirect itself, the
     * payload blob is already in the response; if not, the Location
     * header is followed manually (the .cc step wants the .club
     * origin as referer).
     */
    private suspend fun resolveRedirector(url: String, referer: String): String? {
        if (!url.contains("greenmotors")) return url
        return try {
            var page = app.get(url, headers = browserHeaders(referer))
            var blob = extractBlob(page.text)
            if (blob == null) {
                val loc = page.headers["Location"]?.takeIf { it.isNotBlank() }
                if (loc != null) {
                    page = app.get(
                        loc,
                        headers = browserHeaders("https://greenmotors.club/")
                    )
                    blob = extractBlob(page.text)
                }
            }
            if (blob == null && page.url.contains("greenmotors.cc")) {
                page = app.get(
                    page.url,
                    headers = browserHeaders("https://greenmotors.club/")
                )
                blob = extractBlob(page.text)
            }
            blob?.let { decodeMediatorBlob(it) }
        } catch (e: Exception) {
            null
        }
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
        emit: (ExtractorLink) -> Unit
    ): Int {
        var emitted = 0
        try {
            val doc = app.get(driveUrl).document
            var gamerUrl = doc.selectFirst("#download")?.attr("href")
                .orEmpty()
            if (gamerUrl.isNotBlank() && !gamerUrl.startsWith("http")) {
                val base = Regex("""^(https?://[^/]+)""").find(driveUrl)
                    ?.groupValues?.get(1).orEmpty()
                gamerUrl = base + "/" + gamerUrl.trimStart('/')
            }
            if (gamerUrl.isBlank()) return 0

            val page = app.get(gamerUrl).document
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

            var buttons = page.select("div.card-body h2 a.btn")
            if (buttons.isEmpty()) buttons = page.select("a.btn[href]")

            val seen = mutableSetOf<String>()
            fun direct(url: String, server: String) {
                if (url.isBlank() || !seen.add(url)) return
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
            // fall through; caller tries the built-in extractor
        }
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

        for (link in links) {
            val resolved = resolveRedirector(link.url, link.ref) ?: continue
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
                        counting
                    )
                    if (n == 0) {
                        // Self-resolution found nothing (layout may
                        // have changed): let the built-in extractor
                        // try the same URL.
                        loadExtractor(resolved, mainUrl, subtitleCallback, counting)
                    }
                }
                resolved.contains("hubdrive", ignoreCase = true) ||
                    resolved.contains("hubcdn", ignoreCase = true) -> {
                    loadExtractor(resolved, mainUrl, subtitleCallback, counting)
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
        return emittedCount > 0
    }
}
