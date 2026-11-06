package com.ein

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * EIN — Einthusan (einthusan.tv) for CloudStream, with the 1080p fix.
 *
 * Listing and search pages are the site's results pages:
 *   Recent:  /movie/results/?find=Recent&lang=tamil&page=1  (page is 1-based)
 *   Search:  /movie/results/?lang=tamil&query=<text>
 * Cards live in section#UIMovieSummary > ul > li: div.block1 holds the
 * poster, div.block2 > a.title > h3 the title, div.block2 > div.info the
 * year. The site's own i.hd icon sits on essentially every card, so it
 * says nothing; the card badge instead shows the probed real maximum —
 * UHD when the title's B file exists, no badge otherwise.
 *
 * A watch page (/movie/watch/<CODE>/?lang=...) carries one
 * section#UIVideoPlayer with a data-content-id (the title's CODE) and
 * signed data-mp4-link / data-hls-link URLs on bare IP hosts. Those
 * signed IP links do not actually play from real networks (the
 * connection hangs — verified 2026-11-06, and the reason v1/v2's
 * default links failed on-device), so loadLinks ignores them except
 * as a last resort.
 *
 * The files themselves answer unsigned on the site's CDN domain:
 * cdn2.einthusan.io/etv/content/D<CODE>.mp4 is the free default file
 * (on current titles only 720x480 — the "720p" users see), and many
 * titles also have a separate UHD file B<CODE>.mp4 — verified live
 * 2026-11-06: Immortal (2026), code 14K4, has B14K4.mp4 at 1920x1080,
 * while 5SXG has none. loadLinks HEAD-probes both CDN files and only
 * adds the links that really exist.
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

    /** One parsed listing card, before quality probing. */
    private data class Card(
        val title: String,
        val href: String,
        val code: String,
        val poster: String?,
        val year: Int?
    )

    /**
     * The site writes image URLs protocol-relative ("//cdn2..."), and
     * Jsoup's abs:src comes back empty for them in the app (no base URL
     * on the parsed document) — that is why v1–v3 showed placeholder
     * posters. Normalise by hand instead.
     */
    private fun normalizeUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> value
        }
    }

    /** Movie cards from a results page's UIMovieSummary list. */
    private fun Document.movieCards(): List<Card> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Card>()
        for (li in this.select("#UIMovieSummary > ul > li")) {
            val a = li.selectFirst("div.block2 > a.title") ?: continue
            val href = fixUrl(a.attr("href"))
            if (!href.contains("/movie/watch/")) continue
            if (!seen.add(href)) continue
            val title = li.selectFirst("div.block2 > a.title > h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            val code = Regex("""/movie/watch/([^/?]+)""").find(href)
                ?.groupValues?.get(1) ?: continue
            val poster = normalizeUrl(li.selectFirst("div.block1 > a > img")?.attr("src"))
            val year = li.selectFirst("div.block2 > div.info > p")?.text()
                ?.let { yearRegex.find(it)?.value?.toIntOrNull() }
            out.add(Card(title, href, code, poster, year))
        }
        return out
    }

    /**
     * Build the search responses, probing each title's UHD (B) file in
     * parallel so the card badge shows the REAL maximum quality: UHD
     * when the B file exists, no badge otherwise. The site's own i.hd
     * icon is useless for this — every card on the site carries it.
     */
    private suspend fun List<Card>.toResponses(): List<SearchResponse> = coroutineScope {
        map { card ->
            async {
                val quality = if (
                    headOk("https://cdn2.einthusan.io/etv/content/B${card.code}.mp4", 5000)
                ) {
                    SearchQuality.UHD
                } else {
                    null
                }
                newMovieSearchResponse(card.title, card.href, TvType.Movie) {
                    this.posterUrl = card.poster
                    this.year = card.year
                    this.quality = quality
                }
            }
        }.awaitAll()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // The site's page parameter is 1-based in effect: page=1 is the
        // first page (same as no parameter), while page=0 returns a
        // "Page 0" shell with an EMPTY list — that was the v1 bug that
        // made every home section show no movies (verified live
        // 2026-11-06). CloudStream's page is already 1-based, so it is
        // passed through unchanged.
        val url = request.data + "&page=" + page
        val doc = app.get(url).document
        val items = doc.movieCards().toResponses()
        val totalPages = doc.selectFirst("div.results-info p")?.text()
            ?.let { Regex("""of\s+(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val hasNext = if (totalPages != null) page < totalPages else items.isNotEmpty()
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val seen = mutableSetOf<String>()
        val cards = mutableListOf<Card>()
        for (language in listOf("tamil", "hindi", "telugu", "malayalam")) {
            try {
                val doc = app.get(
                    "$mainUrl/movie/results/?lang=$language&query=$encoded"
                ).document
                for (card in doc.movieCards()) {
                    if (seen.add(card.href)) cards.add(card)
                }
            } catch (e: Exception) {
                // One language failing must not sink the whole search.
            }
        }
        return cards.toResponses()
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
        val poster = normalizeUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: normalizeUrl(doc.selectFirst("div.block1 > a > img")?.attr("src"))
        // Same real-max-quality badge as the listing cards.
        val code = player?.attr("data-content-id")?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("""/movie/watch/([^/?]+)""").find(url)?.groupValues?.get(1)
        val quality = if (
            code != null && headOk("https://cdn2.einthusan.io/etv/content/B$code.mp4", 5000)
        ) {
            SearchQuality.UHD
        } else {
            null
        }
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
            this.quality = quality
            this.tags = genre?.let { listOf(it.replaceFirstChar { c -> c.uppercase() }) }
            this.score = score
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /** True when a HEAD request to the URL answers 200. */
    private fun headOk(url: String, timeoutMs: Int = 10000): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
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

        // Playback URLs are built on the public CDN domain, NOT taken
        // from the page's data-mp4-link / data-hls-link: those are
        // signed URLs on bare per-session IP hosts, and connections to
        // them hang from real networks (verified 2026-11-06 — the
        // reason v1/v2's HLS and MP4 links would not play). The same
        // files answer fine on cdn2.einthusan.io without any signing,
        // which is also how the CNC plugin plays them.
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

        val mp4Url = "https://cdn2.einthusan.io/etv/content/D$code.mp4"
        if (headOk(mp4Url)) {
            callback.invoke(
                newExtractorLink(name, "MP4", mp4Url, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("Referer" to "$mainUrl/")
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        // Last resort only: if neither CDN file exists for this title,
        // hand over the page's own signed links rather than nothing.
        if (!found) {
            val hls = player.attr("data-hls-link").trim()
            if (hls.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(name, "HLS (site)", hls, ExtractorLinkType.M3U8) {
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
                    newExtractorLink(name, "MP4 (site)", mp4, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf("Referer" to "$mainUrl/")
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        return found
    }
}
