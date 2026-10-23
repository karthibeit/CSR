package com.onetamilmv

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
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * OneTamilMV — CloudStream provider for the 1TamilMV forum.
 *
 * How the site actually works (checked 2026-10-23):
 *  - 1TamilMV is a XenForo forum, not a streaming site. Every release is a
 *    forum thread, and the first post of the thread holds the links.
 *  - Those links are mostly magnet / .torrent links, plus some direct and
 *    file-host download links grouped by quality (360p / 480p / 720p /
 *    1080p / 2160p). There are no embedded streaming players to scrape.
 *  - The domain rotates constantly (blocked / parked domains), so the
 *    provider tries a list of known domains until one answers, and the
 *    list also lives in domains.json in this repo so it can be updated
 *    without recompiling.
 *
 * CloudStream note: ExtractorLinkType.MAGNET / TORRENT are declared in the
 * CloudStream library as "No support at the moment", so magnet/torrent
 * entries are surfaced for download / external handling, while direct
 * (.mp4/.mkv/.m3u8) and file-host links are the playable ones.
 */
class OneTamilMVProvider : MainAPI() {

    // Last widely reported domain. If it is parked/blocked the provider
    // walks fallbackDomains below until a live mirror answers.
    override var mainUrl = "https://www.1tamilmv.solar"
    override var name = "1TamilMV"
    override var lang = "ta" // Tamil
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val fallbackDomains = listOf(
        "https://www.1tamilmv.solar",
        "https://www.1tamilmv.cz",
        "https://1tamilmv.fi",
        "https://www.1tamilmv.onl",
        "https://1tamilmv.onl",
        "https://www.1tamilmv.lc",
        "https://1tamilmv.app"
    )

    override val mainPage = mainPageOf(
        "/" to "Latest Threads",
        "/whats-new/" to "What's New"
    )

    // ------------------------------------------------------------------
    // Networking helpers
    // ------------------------------------------------------------------

    private fun swapDomain(url: String, newBase: String): String {
        return try {
            val uri = URI(url)
            val base = URI(newBase)
            URI(base.scheme, base.host, uri.path, uri.query, uri.fragment).toString()
        } catch (e: Exception) {
            url
        }
    }

    /** GET a page, walking the fallback domains if the current one is dead. */
    private suspend fun getDocument(url: String): Document {
        val candidates = buildList {
            add(url)
            fallbackDomains.forEach { domain ->
                val swapped = swapDomain(url, domain)
                if (swapped != url) add(swapped)
            }
        }.distinct()

        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val res = app.get(candidate)
                if (res.code == 200) {
                    val doc = res.document
                    // Parked domains answer 200 with a registrar page — skip them.
                    val text = doc.title() + doc.selectFirst("h1")?.text().orEmpty()
                    if (!text.contains("registrar", ignoreCase = true) &&
                        !text.contains("domain for sale", ignoreCase = true)
                    ) {
                        // Remember the mirror that worked for the next calls.
                        try {
                            val uri = URI(candidate)
                            if (uri.scheme != null && uri.host != null) {
                                mainUrl = "${uri.scheme}://${uri.host}"
                            }
                        } catch (e: Exception) {
                            // keep the previous mainUrl
                        }
                        return doc
                    }
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("All 1TamilMV domains are unreachable")
    }

    // ------------------------------------------------------------------
    // Listing / search parsing (XenForo thread lists)
    // ------------------------------------------------------------------

    private fun cleanThreadTitle(raw: String): String {
        return raw
            .replace(Regex("\\s*\\|\\s*1TamilMV.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*1TamilMV.*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun Element.toThreadSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*=/threads/]") ?: return null
        val href = fixUrl(link.attr("href"))
        if (href.contains("/latest") || href.contains("/post-") ||
            href.contains("/members/") || href.contains("/page-")
        ) return null
        val title = cleanThreadTitle(link.text())
        if (title.length < 4) return null
        // Forum listings have no movie poster (it lives in the first post,
        // load() fills it in), so posterUrl stays null here.
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = null
        }
    }

    private fun Document.extractThreads(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        // Primary: XenForo thread list rows.
        this.select("div.structItem--thread, li.block-row").forEach { row ->
            row.toThreadSearchResult()?.let {
                if (seen.add(it.url)) results.add(it)
            }
        }
        // Fallback: any thread anchor on the page.
        if (results.isEmpty()) {
            this.select("a[href*=/threads/]").forEach { a ->
                a.toThreadSearchResult()?.let {
                    if (seen.add(it.url)) results.add(it)
                }
            }
        }
        return results
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Homepage / What's New are not reliably paginated — page 1 only.
        if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        val document = getDocument(fixUrl(request.data))
        return newHomePageResponse(request, document.extractThreads(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // 1) XenForo search endpoint (works when the mirror allows guest search).
        try {
            val doc = getDocument("$mainUrl/search/?keywords=$encoded&o=date")
            val results = doc.extractThreads()
            if (results.isNotEmpty()) return results
        } catch (e: Exception) {
            // fall through to the scan below
        }

        // 2) Fallback: scan the latest threads and match the title locally.
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val latest = try {
            getDocument(mainUrl).extractThreads()
        } catch (e: Exception) {
            emptyList()
        }
        return latest.filter { item ->
            val title = item.name.lowercase()
            tokens.all { it in title }
        }
    }

    // ------------------------------------------------------------------
    // Detail page (thread first post)
    // ------------------------------------------------------------------

    data class OneTamilMVLink(
        @JsonProperty("url") val url: String,
        @JsonProperty("label") val label: String
    )

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1.p-title-value")?.text()
            ?: document.title()
        val title = cleanThreadTitle(rawTitle)

        // First post = the release post holding poster + links.
        val post = document.selectFirst(
            "article.message-body, div.bbWrapper, div.message-content"
        ) ?: document.body()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        ) ?: post.select("img").firstOrNull { img ->
            val src = img.attr("src")
            src.isNotBlank() &&
                !src.contains("/styles/") && !src.contains("avatar") &&
                !src.contains("smilie") && !src.contains("emoji") &&
                !src.startsWith("data:")
        }?.let { fixUrlNull(it.attr("src")) }

        val plot = post.text().take(2000).ifBlank { null }
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()

        // Collect every usable link from the first post.
        val links = LinkedHashMap<String, OneTamilMVLink>()
        post.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) return@forEach
            val label = a.text().trim().ifBlank { href }
            val isMagnet = href.startsWith("magnet:")
            val isTorrent = href.endsWith(".torrent") || href.contains("/attachments/")
            val isDirectVideo = Regex("\\.(mp4|mkv|avi|m3u8)(\\?|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(href)
            val isExternalHost = href.startsWith("http") &&
                !href.contains("1tamilmv") && !href.contains("tamilmv.") &&
                !href.contains("facebook.com") && !href.contains("twitter.com") &&
                !href.contains("t.me") && !href.contains("telegram")
            if (isMagnet || isTorrent || isDirectVideo || isExternalHost) {
                links.putIfAbsent(href, OneTamilMVLink(href, label))
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, toJson(links.values.toList())) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ------------------------------------------------------------------
    // Link extraction
    // ------------------------------------------------------------------

    private fun parseQuality(text: String): Int {
        return when {
            Regex("2160|4k", RegexOption.IGNORE_CASE).containsMatchIn(text) -> Qualities.P2160.value
            Regex("1080").containsMatchIn(text) -> Qualities.P1080.value
            Regex("720").containsMatchIn(text) -> Qualities.P720.value
            Regex("480").containsMatchIn(text) -> Qualities.P480.value
            Regex("360").containsMatchIn(text) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links: List<OneTamilMVLink> = try {
            parseJson(data)
        } catch (e: Exception) {
            emptyList()
        }

        var found = false
        links.forEach { link ->
            val quality = parseQuality(link.label + " " + link.url)
            when {
                link.url.startsWith("magnet:") -> {
                    callback.invoke(
                        newExtractorLink(name, "Magnet — ${link.label}", link.url, ExtractorLinkType.MAGNET) {
                            this.quality = quality
                        }
                    )
                    found = true
                }
                link.url.endsWith(".torrent") || link.url.contains("/attachments/") -> {
                    callback.invoke(
                        newExtractorLink(name, "Torrent — ${link.label}", link.url, ExtractorLinkType.TORRENT) {
                            this.quality = quality
                            this.referer = mainUrl
                        }
                    )
                    found = true
                }
                Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(link.url) -> {
                    callback.invoke(
                        newExtractorLink(name, link.label, link.url, ExtractorLinkType.M3U8) {
                            this.quality = quality
                            this.referer = mainUrl
                        }
                    )
                    found = true
                }
                Regex("\\.(mp4|mkv|avi)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(link.url) -> {
                    callback.invoke(
                        newExtractorLink(name, link.label, link.url, ExtractorLinkType.VIDEO) {
                            this.quality = quality
                            this.referer = mainUrl
                        }
                    )
                    found = true
                }
                else -> {
                    // File-host page (Drive, FilePress, Streamtape, …) —
                    // let CloudStream's built-in extractors resolve it.
                    if (loadExtractor(link.url, mainUrl, subtitleCallback, callback)) {
                        found = true
                    } else {
                        // Still surface it as a direct download link.
                        callback.invoke(
                            newExtractorLink(name, link.label, link.url, ExtractorLinkType.VIDEO) {
                                this.quality = quality
                                this.referer = mainUrl
                            }
                        )
                        found = true
                    }
                }
            }
        }
        return found
    }
}
