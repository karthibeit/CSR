package com.xd

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import java.net.URLEncoder

/**
 * XD — XDMovies (huh.xdmovies.wtf).
 *
 * PHP download site (checked live 2026-11-08). Catalog pages are
 * server-rendered: cards are a.movie-link with a TMDB poster, a
 * type badge, a quality badge, title and year. Filters live at
 * category.php?language= / ?genre= / ?ott= and everything paginates
 * with &page=N; the home grid paginates with ?page=N and ?type=
 * filters movies/series. Search is a JSON API at
 * /php/search_api.php which demands an X-Auth-Token header whose
 * value is embedded in the home page HTML plus the session cookies.
 *
 * Detail pages group download versions by source ("BluRay
 * Versions(5)", "JioHotstar Versions(5)" ...). Every version is a
 * filename plus a size button pointing at
 * link.xdmovies.wtf/download/<token>, which 302s to a
 * latestnewsonline.sbs "Get Your Link" step page (Cloudflare
 * checked, JavaScript steps) that ends on a HubCloud or HubDrive
 * file page. The step page is walked in a real browser view by
 * XdCf; the HubCloud file is then resolved with the same flow the
 * 4KHUB plugin uses.
 */
class XDProvider : MainAPI() {
    override var mainUrl = "https://huh.xdmovies.wtf"
    override var name = "XDMovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // ------------------------------------------------------------------
    // Home / listings
    // ------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/?type=movies" to "Movies",
        "$mainUrl/?type=series" to "Series",
        "$mainUrl/category.php?language=Tamil" to "Tamil",
        "$mainUrl/category.php?language=Telugu" to "Telugu",
        "$mainUrl/category.php?language=Malayalam" to "Malayalam",
        "$mainUrl/category.php?language=Hindi" to "Hindi",
        "$mainUrl/category.php?language=English" to "English",
        "$mainUrl/category.php?language=Kannada" to "Kannada",
        "$mainUrl/category.php?ott=JioHotstar" to "JioHotstar",
        "$mainUrl/category.php?ott=Netflix" to "Netflix",
        "$mainUrl/category.php?ott=Amazon" to "Amazon Prime",
        "$mainUrl/category.php?ott=SonyLiv" to "SonyLIV",
        "$mainUrl/category.php?genre=Action" to "Action",
        "$mainUrl/category.php?genre=Drama" to "Drama",
        "$mainUrl/category.php?genre=Thriller" to "Thriller"
    )

    private fun paged(base: String, page: Int): String {
        if (page <= 1) return base
        val sep = if (base.contains("?")) "&" else "?"
        return base + sep + "page=" + page
    }

    private fun Element.toCard(): SearchResponse? {
        val href = attr("href").trim()
        if (href.isBlank()) return null
        val card = selectFirst(".movie-card") ?: this
        val title = card.selectFirst("h3")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = card.selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
        val year = card.selectFirst("p")?.text()?.trim()
            ?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val typeText = card.selectFirst(".type-badge")?.text()?.trim().orEmpty()
        val type = if (typeText.contains("series", true) ||
            href.contains("/series/")
        ) TvType.TvSeries else TvType.Movie
        val badge = card.selectFirst(".quality-badge")?.text()?.trim().orEmpty()
        val quality = when {
            badge.contains("4K", true) || badge.contains("2160") ->
                SearchQuality.UHD
            badge.contains("HD", true) -> SearchQuality.HD
            else -> null
        }
        val url = if (href.startsWith("http")) href else mainUrl + href
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
            }
        }
    }

    private fun Document.cards(): List<SearchResponse> =
        select("a.movie-link[href]").mapNotNull { it.toCard() }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(paged(request.data, page)).document
        val items = doc.cards()
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Search (token JSON API)
    // ------------------------------------------------------------------

    data class SearchItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("release_year") val year: String? = null,
        @JsonProperty("path") val path: String? = null,
        @JsonProperty("qualities") val qualities: List<String>? = null
    )

    private val tokenRegex = Regex("""AUTH_TOKEN\s*=\s*['"]([^'"]+)['"]""")

    override suspend fun search(query: String): List<SearchResponse> {
        val home = app.get("$mainUrl/").text
        val token = tokenRegex.find(home)?.groupValues?.get(1)
            ?: return emptyList()
        val api = "$mainUrl/php/search_api.php?query=" +
            URLEncoder.encode(query, "UTF-8") + "&fuzzy=true&limit=30"
        val resp = app.get(
            api,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-Auth-Token" to token,
                "Referer" to "$mainUrl/"
            )
        )
        val items: List<SearchItem> = try {
            val arr: Array<SearchItem> = mapper.readValue(
                resp.text,
                mapper.typeFactory.constructArrayType(SearchItem::class.java)
            )
            arr.toList()
        } catch (e: Exception) {
            emptyList()
        }
        return items.mapNotNull { item ->
            val title = item.title?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val path = item.path?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = if (path.startsWith("http")) path else mainUrl + path
            val poster = item.poster?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http")) it
                else "https://image.tmdb.org/t/p/w500" + it
            }
            val year = item.year?.toIntOrNull()
            val quality = when {
                item.qualities?.any { it.contains("2160") } == true ->
                    SearchQuality.UHD
                item.qualities?.any { it.contains("1080") } == true ->
                    SearchQuality.HD
                else -> null
            }
            if (item.type?.contains("series", true) == true ||
                path.contains("/series/")
            ) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = quality
                }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = quality
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Detail page
    // ------------------------------------------------------------------

    private fun looksChallenged(doc: Document, html: String): Boolean {
        if (html.contains("movie-link") || html.contains("Download Links")) {
            return false
        }
        val t = doc.title()
        return t.contains("Just a moment", true) ||
            t.contains("Attention Required", true) ||
            html.contains("Performing security verification")
    }

    /** Fetch a page, solving a Cloudflare check via XdCf if needed. */
    private suspend fun fetchDoc(url: String): Document {
        val first = app.get(url)
        val firstDoc = first.document
        if (!looksChallenged(firstDoc, first.text)) return firstDoc
        val solved = XdCf.solve(url)
        if (solved.isEmpty()) return firstDoc
        return app.get(url, headers = solved).document
    }

    private data class Version(
        val label: String,
        val filename: String,
        val size: String,
        val href: String,
        val quality: Int
    )

    private val filenameRegex =
        Regex("""([A-Za-z0-9][A-Za-z0-9.\-_'() ]*\.(?:mkv|mp4))""")
    private val sizeRegex = Regex("""(\d+(?:\.\d+)?\s*(?:GB|MB))""")

    private fun qualityOf(text: String): Int = when {
        text.contains("2160") -> Qualities.P2160.value
        text.contains("1440") -> Qualities.P1440.value
        text.contains("1080") -> Qualities.P1080.value
        text.contains("720") -> Qualities.P720.value
        text.contains("480") -> Qualities.P480.value
        text.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    /** Every download version on a detail page, in page order. */
    private fun Document.versions(): List<Version> {
        val out = mutableListOf<Version>()
        for (a in select("a[href*=\"link.xdmovies.wtf/download/\"]")) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue
            // The filename and size sit in the block around the
            // button: climb until an ancestor's text names a file.
            var filename = ""
            var size = sizeRegex.find(a.text())?.groupValues?.get(1).orEmpty()
            var node: Element? = a
            var hops = 0
            while (node != null && hops < 4) {
                val text = node.text()
                val fm = filenameRegex.find(text)
                if (fm != null) {
                    filename = fm.groupValues[1].trim()
                    if (size.isBlank()) {
                        size = sizeRegex.find(text)?.groupValues?.get(1)
                            .orEmpty()
                    }
                    break
                }
                node = node.parent()
                hops++
            }
            if (filename.isBlank()) continue
            val res = Regex("""(2160|1440|1080|720|480|360)p""")
                .find(filename)?.groupValues?.get(1)?.let { it + "p" }
                .orEmpty()
            val label = listOf(res, size)
                .filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { filename }
            out.add(Version(label, filename, size, href, qualityOf(filename)))
        }
        return out
    }

    /**
     * Primary version scan over the raw page HTML: pair every
     * download URL with the closest preceding filename and the
     * size printed in its button. Independent of the page's DOM
     * nesting (v1's DOM climb found nothing on the real pages).
     */
    private fun scanVersions(html: String): List<Version> {
        val urlRegex =
            Regex("""https://link\.xdmovies\.wtf/download/[A-Za-z0-9_\-]+""")
        val urlMatches = urlRegex.findAll(html).toList()
        if (urlMatches.isEmpty()) return emptyList()
        val fileMatches = filenameRegex.findAll(html).toList()
        val usedFiles = mutableSetOf<Int>()
        val seenHrefs = mutableSetOf<String>()
        val out = mutableListOf<Version>()
        for (u in urlMatches) {
            val href = u.value
            if (!seenHrefs.add(href)) continue
            val fi = fileMatches.indices.lastOrNull {
                it !in usedFiles && fileMatches[it].range.last < u.range.first
            }
            val filename = if (fi != null) {
                usedFiles += fi
                fileMatches[fi].groupValues[1].trim()
            } else ""
            val afterTo = (u.range.last + 200).coerceAtMost(html.length)
            var size = sizeRegex.find(html.substring(u.range.first, afterTo))
                ?.groupValues?.get(1).orEmpty()
            if (size.isBlank() && fi != null) {
                val seg = html.substring(
                    fileMatches[fi].range.first, u.range.first
                )
                size = sizeRegex.find(seg)?.groupValues?.get(1).orEmpty()
            }
            if (filename.isBlank()) continue
            val res = Regex("""(2160|1440|1080|720|480|360)p""")
                .find(filename)?.groupValues?.get(1)?.let { it + "p" }
                .orEmpty()
            val label = listOf(res, size)
                .filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { filename }
            out.add(Version(label, filename, size, href, qualityOf(filename)))
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" — ").substringBefore(" - ")
                .trim()
        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
        val bodyText = doc.body()?.text().orEmpty()
        fun infoLine(label: String): String {
            // bodyText is flattened to one line, so stop the value
            // at the next known field label.
            val stops = "Release Date|Genres|Audios|Audio|Sources|" +
                "Source|Star Cast|Cast|Director|Duration|Quality|" +
                "Language|Download"
            val m = Regex(
                label + """\s*:?\s*(.+?)(?=\s+(?:$stops)\s*:|$)""",
                RegexOption.IGNORE_CASE
            ).find(bodyText) ?: return ""
            return m.groupValues[1].trim().removeSuffix(".")
        }
        val year = Regex("""Release Date\s*:?\s*.*?((?:19|20)\d{2})""")
            .find(bodyText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(19|20)\d{2}""").find(url)?.value?.toIntOrNull()
        val score = Regex("""(\d+(?:\.\d+)?)\s*/\s*10""").find(bodyText)
            ?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { Score.from10(it) }
        val genres = infoLine("Genres")
            .split(",", "/", "·")
            .map { it.trim() }
            .filter { it.length in 2..24 }
        val cast = infoLine("Star Cast")
            .split(",")
            .map { it.trim() }
            .filter { it.length in 2..40 }
        val plot = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
        val versions = doc.versions()
        val isSeries = url.contains("/series/") ||
            doc.title().contains("Series", true)
        val extraPlot = listOf(
            infoLine("Audios").takeIf { it.isNotBlank() }
                ?.let { "Audio: $it" },
            infoLine("Source").takeIf { it.isNotBlank() }
                ?.let { "Source: $it" }
        ).filterNotNull().joinToString(" · ")
        val fullPlot = listOf(plot, extraPlot.ifBlank { null })
            .filterNotNull().joinToString("\n")
            .ifBlank { null }

        if (isSeries) {
            // v1: XD series posts carry per-quality season packs on
            // the series page itself; expose them as one entry whose
            // links resolve every version on the page.
            val episodes = listOf(
                newEpisode(url) {
                    this.name = "Season Pack — all versions"
                    this.season = 1
                    this.episode = 1
                }
            )
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = fullPlot
                this.year = year
                this.score = score
                this.tags = genres.takeIf { it.isNotEmpty() }
                this.actors = cast.map { ActorData(Actor(it)) }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = fullPlot
            this.year = year
            this.score = score
            this.tags = genres.takeIf { it.isNotEmpty() }
            this.actors = cast.map { ActorData(Actor(it)) }
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /**
     * Follow one version button to its HubCloud / HubDrive page.
     * The first hop (link.xdmovies.wtf) is a plain 302; the step
     * page behind it is walked in a browser view by XdCf.
     */
    private suspend fun resolveVersionTarget(
        buttonUrl: String,
        diag: MutableList<String>? = null
    ): String? {
        val hubInText = Regex("""https?://[^\s"'<>]*(?:hubcloud|hubdrive)[^\s"'<>]*""")
        try {
            val resp = app.get(buttonUrl, allowRedirects = false)
            diag?.add("btn code=${resp.code}")
            val loc = resp.headers["Location"]
                ?.takeIf { it.isNotBlank() }
            if (loc != null) {
                diag?.add(
                    "btn loc=" + Regex("""^(https?://[^/]+)""")
                        .find(loc)?.groupValues?.get(1).orEmpty()
                )
                if (loc.contains("hubcloud", true) ||
                    loc.contains("hubdrive", true)
                ) return loc
                val walked = XdCf.walk(loc)
                diag?.add("walk=" + (walked ?: "NULL"))
                return walked
            }
            hubInText.find(resp.text)?.value?.let {
                diag?.add("hub in body")
                return it
            }
            // A 200 here means the shortener served the step page
            // (or a challenge) directly: walk the button URL itself.
            val walked = XdCf.walk(buttonUrl)
            diag?.add("walk(self)=" + (walked ?: "NULL"))
            return walked
        } catch (e: Exception) {
            diag?.add(
                "btn EXC ${e.javaClass.simpleName}: " +
                    (e.message?.take(60) ?: "")
            )
            val walked = XdCf.walk(buttonUrl)
            diag?.add("walk(exc)=" + (walked ?: "NULL"))
            return walked
        }
    }

    private fun base64Decode(value: String): String {
        val clean = value.trim()
        return try {
            String(java.util.Base64.getUrlDecoder().decode(clean))
        } catch (e: Exception) {
            try {
                String(java.util.Base64.getDecoder().decode(clean))
            } catch (e2: Exception) {
                ""
            }
        }
    }

    /**
     * Resolve a HubCloud drive page to direct file links, mirroring
     * the flow verified in the 4KHUB plugin: drive page #download
     * leads to a gamer page whose buttons are the real file hosts.
     * Returns how many links were emitted.
     */
    private suspend fun resolveHubCloud(
        driveUrl: String,
        label: String,
        fallbackQuality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
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
                val solved = XdCf.solve(driveUrl)
                if (solved.isNotEmpty()) {
                    gamerUrl = gamerOf(app.get(driveUrl, headers = solved).document)
                }
            }
            if (gamerUrl.isBlank()) {
                diag?.add("hub: no #download on drive page")
                return 0
            }
            diag?.add("hub: gamer page found")

            var page = app.get(gamerUrl).document
            var buttons = page.select("div.card-body h2 a.btn")
            if (buttons.isEmpty()) buttons = page.select("a.btn[href]")
            diag?.add("hub: buttons=${buttons.size}")
            if (buttons.isEmpty()) {
                val solvedG = XdCf.solve(gamerUrl)
                if (solvedG.isNotEmpty()) {
                    page = app.get(gamerUrl, headers = solvedG).document
                    buttons = page.select("div.card-body h2 a.btn")
                    if (buttons.isEmpty()) buttons = page.select("a.btn[href]")
                }
            }
            val header = page.selectFirst("div.card-header")?.text().orEmpty()
            val quality = Regex("""(\d{3,4})[pP]""").find(header)
                ?.groupValues?.get(1)?.let { qualityOf(it + "p") }
                ?.takeIf { it != Qualities.Unknown.value }
                ?: fallbackQuality

            val seen = mutableSetOf<String>()
            val direct: suspend (String, String) -> Unit = { u, server ->
                if (u.isNotBlank() && seen.add(u)) {
                    emitted++
                    emit(
                        newExtractorLink(
                            name, "$label [$server]", u,
                            ExtractorLinkType.VIDEO
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
                    text.contains("BuzzServer", true) -> {
                        try {
                            val resp = app.get(
                                "$href/download",
                                referer = href,
                                allowRedirects = false
                            )
                            val dlink = resp.headers["hx-redirect"].orEmpty()
                            if (dlink.isNotBlank()) direct(dlink, "BuzzServer")
                        } catch (e: Exception) {
                            // server down; other buttons remain
                        }
                    }
                    text.contains("FSL", true) -> direct(href, "FSL Server")
                    text.contains("S3 Server", true) ->
                        direct(href, "S3 Server")
                    text.contains("Mega Server", true) ->
                        direct(href, "Mega Server")
                    text.contains("Download File", true) ->
                        direct(href, "Download")
                    text.contains("pixel", true) ||
                        href.contains("pixel.", true) -> {
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
                    text.contains("Watch Online", true) &&
                        href.contains("?u=") -> {
                        val decoded = base64Decode(
                            href.substringAfter("?u=").substringBefore("&")
                        )
                        if (decoded.startsWith("http")) {
                            direct(decoded, "FSL Server")
                        }
                    }
                    else -> {
                        if (loadExtractor(href, gamerUrl, subtitleCallback, emit)) {
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

    /**
     * If nothing playable was produced, surface the recorded steps
     * as link names (the 4KHUB v3 diagnostics pattern) so a single
     * screenshot shows exactly which stage stopped.
     */
    private suspend fun emitDiag(
        diag: List<String>,
        data: String,
        callback: (ExtractorLink) -> Unit
    ) {
        for (line in diag) {
            try {
                callback(
                    newExtractorLink(
                        name, "XD diag: $line", data,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            } catch (e: Exception) {
                // diagnostics must never break the caller
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
        var any = false
        try {
            val first = app.get(data)
            diag.add("fetch code=${first.code}")
            var doc = first.document
            var html = first.text
            if (looksChallenged(doc, html)) {
                diag.add("page CHALLENGED")
                val solved = XdCf.solve(data)
                diag.add(
                    "solve=" + if (solved.isEmpty()) "EMPTY" else "OK"
                )
                if (solved.isNotEmpty()) {
                    val r2 = app.get(data, headers = solved)
                    doc = r2.document
                    html = r2.text
                    diag.add("refetch code=${r2.code}")
                }
            }
            var versions = scanVersions(html)
            diag.add("versions(scan)=${versions.size}")
            if (versions.isEmpty()) {
                versions = doc.versions()
                diag.add("versions(dom)=${versions.size}")
            }
            if (versions.isEmpty()) {
                diag.add("html chars=${html.length}")
                emitDiag(diag, data, callback)
                return false
            }
            XdCf.beginSession()
            try {
                for ((index, version) in versions.withIndex()) {
                    diag.add("v${index + 1} ${version.label}")
                    val target = resolveVersionTarget(version.href, diag)
                        ?: continue
                    val n = when {
                        target.contains("hubcloud", true) ->
                            resolveHubCloud(
                                target, version.label, version.quality,
                                subtitleCallback, callback, diag
                            )
                        else -> {
                            if (loadExtractor(
                                    target, mainUrl,
                                    subtitleCallback, callback
                                )
                            ) 1 else 0
                        }
                    }
                    diag.add("v${index + 1} emitted=$n")
                    if (n == 0 && target.contains("hubcloud", true)) {
                        // Layout may differ from the 4KHUB flow:
                        // give the built-in extractor the same URL
                        // before giving up on this version.
                        if (loadExtractor(
                                target, mainUrl, subtitleCallback, callback
                            )
                        ) {
                            any = true
                        }
                    } else if (n > 0) {
                        any = true
                    }
                }
            } finally {
                XdCf.endSession()
            }
        } catch (e: Exception) {
            diag.add(
                "EXC ${e.javaClass.simpleName}: " +
                    (e.message?.take(80) ?: "")
            )
        }
        if (!any) emitDiag(diag, data, callback)
        return any
    }
}
