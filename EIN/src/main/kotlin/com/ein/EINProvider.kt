package com.ein

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * EIN — Einthusan (einthusan.tv) for CloudStream, with the 1080p fix.
 *
 * Listing and search pages are the site's results pages:
 *   Recent:  /movie/results/?find=Recent&lang=tamil&page=0  (page is 0-based)
 *   Search:  /movie/results/?lang=tamil&query=<text>
 * Cards live in section#UIMovieSummary > ul > li: div.block1 holds the
 * poster, div.block2 > a.title > h3 the title, div.block2 > div.info the
 * year, and an i.hd icon marks titles the site flags as HD.
 *
 * A watch page (/movie/watch/<CODE>/?lang=...) carries one
 * section#UIVideoPlayer whose data-mp4-link and data-hls-link attributes
 * point at the free file D<CODE>.mp4 on a signed IP host. That is the
 * only file the site's own player (and the CNC plugin) ever offers, and
 * on current titles it is only 720x480.
 *
 * The site's CDN (cdn2.einthusan.io/etv/content/) also stores a separate
 * UHD file B<CODE>.mp4 for many titles — verified live 2026-11-06:
 * Immortal (2026), code 14K4, has B14K4.mp4 at 1920x1080, reachable
 * anonymously, while D14K4.mp4 is 720x480. Not every title has one
 * (e.g. 5SXG has none), so loadLinks probes the B file with a HEAD
 * request and only adds the "1080p UHD" link when it really exists.
 * All patterns were verified live against the site on 2026-11-06.
 */
class EINProvider : MainAPI() {
    override var mainUrl = "https://einthusan.tv"
    override var name = "Einthusan 1080p"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/movie/results/?find=Recent&lang=tamil" to "Tamil — Recent",
        "$mainUrl/movie/results/?find=Recent&lang=hindi" to "Hindi — Recent",
        "$mainUrl/movie/results/?find=Recent&lang=telugu" to "Telugu — Recent",
        "$mainUrl/movie/results/?find=Recent&lang=malayalam" to "Malayalam — Recent",
        "$mainUrl/movie/results/?find=Recent&lang=kannada" to "Kannada — Recent",
        "$mainUrl/movie/results/?find=Recent&lang=bengali" to "Bengali — Recent",
        "$mainUrl/movie/results/?find=Recent&lang=marathi" to "Marathi — Recent",
        "$mainUrl/movie/results/?find=Recent&lang=punjabi" to "Punjabi — Recent"
    )

    private val yearRegex = Regex("""(?:19|20)\d{2}""")

    // ------------------------------------------------------------------
    // Listing parsing
    // ------------------------------------------------------------------

    /** Movie cards from a results page's UIMovieSummary list. */
    private fun Document.movieCards(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        for (li in this.select("#UIMovieSummary > ul > li")) {
            val a = li.selectFirst("div.block2 > a.title") ?: continue
            val href = fixUrl(a.attr("href"))
            if (!href.contains("/movie/watch/")) continue
            if (!seen.add(href)) continue
            val title = li.selectFirst("div.block2 > a.title > h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            val poster = li.selectFirst("div.block1 > a > img")
                ?.attr("abs:src")?.takeIf { it.isNotBlank() }
            val year = li.selectFirst("div.block2 > div.info > p")?.text()
                ?.let { yearRegex.find(it)?.value?.toIntOrNull() }
            val quality = if (li.selectFirst("i.hd") != null) {
                SearchQuality.HD
            } else {
                null
            }
            out.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
            })
        }
        return out
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // The site paginates results with a 0-based page parameter.
        val url = request.data + "&page=" + (page - 1)
        val doc = app.get(url).document
        val items = doc.movieCards()
        val totalPages = doc.selectFirst("div.results-info p")?.text()
            ?.let { Regex("""of\s+(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val hasNext = if (totalPages != null) page < totalPages else items.isNotEmpty()
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        for (language in listOf("tamil", "hindi", "telugu", "malayalam")) {
            try {
                val doc = app.get(
                    "$mainUrl/movie/results/?lang=$language&query=$encoded"
                ).document
                for (item in doc.movieCards()) {
                    if (seen.add(item.url)) out.add(item)
                }
            } catch (e: Exception) {
                // One language failing must not sink the whole search.
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Detail page (the watch page itself)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val player = doc.selectFirst("#UIVideoPlayer")
        var title = player?.attr("data-content-title")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title()
        // Page title shape: "Immortal (2026) Tamil in HD - Einthusan"
        title = title.removeSuffix("- Einthusan").trim()
            .removeSuffix("in HD").trim()
        val year = doc.selectFirst("div.block2 > div.info > p")?.text()
            ?.let { yearRegex.find(it)?.value?.toIntOrNull() }
            ?: yearRegex.find(title)?.value?.toIntOrNull()
        title = title.replace(Regex("""\((?:19|20)\d{2}\)"""), "").trim()
            .replace(Regex("""\s+"""), " ")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: doc.selectFirst("div.block1 > a > img")?.attr("abs:src")
                ?.takeIf { it.isNotBlank() }
        val plot = doc.selectFirst("p.synopsis")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        val genre = player?.attr("data-genre")?.trim()
            ?.takeIf { it.isNotBlank() }
        // Category ratings are 0-5 values on ul.average-rating; the site
        // average of the non-zero ones, doubled, is the 0-10 score.
        val ratings = doc.select("ul.average-rating > li > p[data-value]")
            .mapNotNull { it.attr("data-value").toDoubleOrNull() }
            .filter { it > 0.0 }
        val score = if (ratings.isNotEmpty()) {
            Score.from10(ratings.average() * 2.0)
        } else {
            null
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genre?.let { listOf(it.replaceFirstChar { c -> c.uppercase() }) }
            this.score = score
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /** True when a HEAD request to the URL answers 200. */
    private fun headOk(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            conn.disconnect()
            code == 200 || code == 206
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val player = doc.selectFirst("#UIVideoPlayer") ?: return false
        val code = player.attr("data-content-id").trim()
            .takeIf { it.isNotBlank() }
            ?: Regex("""/movie/watch/([^/?]+)""").find(data)?.groupValues?.get(1)
            ?: return false
        var found = false

        // The fix: the site's separate UHD file, offered only when the
        // probe proves it exists for this title.
        val uhdUrl = "https://cdn2.einthusan.io/etv/content/B$code.mp4"
        if (headOk(uhdUrl)) {
            callback.invoke(
                newExtractorLink(name, "1080p UHD", uhdUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("Referer" to "$mainUrl/")
                    this.quality = Qualities.P1080.value
                }
            )
            found = true
        }

        val hls = player.attr("data-hls-link").trim()
        if (hls.isNotBlank()) {
            callback.invoke(
                newExtractorLink(name, "HLS (site default)", hls, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("Referer" to "$mainUrl/")
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        val mp4 = player.attr("data-mp4-link").trim()
        if (mp4.isNotBlank()) {
            callback.invoke(
                newExtractorLink(name, "MP4 (site default)", mp4, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("Referer" to "$mainUrl/")
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        return found
    }
}
