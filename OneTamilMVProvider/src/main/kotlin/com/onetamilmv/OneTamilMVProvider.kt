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

    // The current site runs Invision Community (checked live 2026-10-23):
    // topics live under /index.php?/forums/topic/<id>-<slug>/ and the
    // homepage carries a "RECENTLY ADDED" block of topic links.
    override val mainPage = mainPageOf(
        "https://www.1tamilmv.solar/" to "Latest",
        "https://www.1tamilmv.solar/index.php?/forums/forum/9-tamil-language/" to "Tamil",
        "https://www.1tamilmv.solar/index.php?/forums/forum/22-telugu-language/" to "Telugu",
        "https://www.1tamilmv.solar/index.php?/forums/forum/56-hindi-language/" to "Hindi",
        "https://www.1tamilmv.solar/index.php?/forums/forum/34-malayalam-language/" to "Malayalam",
        "https://www.1tamilmv.solar/index.php?/forums/forum/67-kannada-language/" to "Kannada"
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
        var t = raw
            .replace(Regex("\\s*\\|\\s*1TamilMV.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*1TamilMV.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Listings append the quality bracket to the title: "Title - [4K, ...]"
        val bracket = t.indexOf(" - [")
        if (bracket > 8) t = t.substring(0, bracket).trim()
        return t.trim().trimEnd('-', '–').trim()
    }

    /** True when a text looks like a quality/tag bracket, not a title. */
    private fun String.looksLikeTag(): Boolean {
        val s = trim()
        return s.startsWith("[") || s.equals("MAGNET", true) ||
            s.equals("Watch Online", true) || s.length < 4
    }

    /** Derive a readable title from a topic URL slug (last-resort fallback). */
    private fun titleFromSlug(href: String): String {
        val slug = href.substringAfter("/forums/topic/", "")
            .substringAfter("/threads/", "")
        val noId = slug.replace(Regex("^\\d+-"), "").trim('/')
        return try {
            java.net.URLDecoder.decode(noId, "UTF-8")
        } catch (e: Exception) {
            noId
        }.replace('-', ' ').replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Best title for a topic anchor. On the homepage the title sits in the
     * <strong> right before the quality-bracket link; in forum/search
     * listings the anchor text itself is the title.
     */
    private fun Element.topicTitle(href: String): String {
        val attrTitle = attr("title").trim()
        if (!attrTitle.looksLikeTag() && attrTitle.length >= 8) {
            return cleanThreadTitle(attrTitle)
        }
        val own = text().trim()
        if (!own.looksLikeTag() && own.length >= 8) return cleanThreadTitle(own)

        // Walk up a few levels checking previous siblings for the title text.
        val chain = listOf(this) + parents().take(3)
        for (el in chain) {
            val prevText = el.previousElementSibling()?.text()?.trim().orEmpty()
            if (prevText.length >= 8 && !prevText.looksLikeTag()) {
                return cleanThreadTitle(prevText)
            }
        }
        return cleanThreadTitle(titleFromSlug(href))
    }

    private val topicUrlRegex = Regex("/forums/topic/\\d+[-/]|/threads/[^/]*\\.?\\d+")

    private fun Document.extractThreads(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        // Generic pass over every topic anchor (Invision /forums/topic/ and
        // legacy XenForo /threads/ links both work with this).
        this.select("a[href*=/forums/topic/], a[href*=/threads/]").forEach { a ->
            val rawHref = a.absUrl("href").ifBlank { a.attr("href") }
            val href = fixUrl(rawHref)
            if (!topicUrlRegex.containsMatchIn(href)) return@forEach
            if (href.contains("/page/") || href.contains("#")) return@forEach
            if (!seen.add(href)) return@forEach
            val title = a.topicTitle(href)
            if (title.length < 4) return@forEach
            // Sidebar/widget headings that can get mistaken for titles.
            if (title.lowercase() in setOf(
                    "languages", "top releases this week", "recently added",
                    "trending", "latest posts", "popular topics"
                )
            ) return@forEach
            // Poster thumbs ride along in forum listings when present.
            val poster = a.parents().take(4)
                .flatMap { it.select("img") }
                .firstOrNull { img ->
                    val src = img.absUrl("src").ifBlank { img.attr("src") }
                    src.isNotBlank() && !src.contains("avatar") &&
                        !src.contains("logo") && !src.contains("emoji")
                }?.let { fixUrlNull(it.absUrl("src").ifBlank { it.attr("src") }) }
            results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            })
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

        // 1) Invision guest search (verified working for guests 2026-10-23).
        try {
            val doc = getDocument("$mainUrl/index.php?/search/&q=$encoded")
            val results = doc.extractThreads()
            if (results.isNotEmpty()) return results
        } catch (e: Exception) {
            // fall through to the scan below
        }

        // 2) Fallback: scan the homepage and match titles locally.
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
        // Invision markup first, legacy XenForo selectors as fallback.
        val post = document.selectFirst(
            "div.cPost_contentWrap, [data-role=commentContent], " +
                "div.ipsType_richText, article.message-body, div.bbWrapper"
        ) ?: document.body()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        ) ?: post.select("img").firstOrNull { img ->
            // Real posters are content images inside the post; skip the
            // site's decoration (logo, torrent borders, uTorrent button).
            val src = img.absUrl("src").ifBlank { img.attr("src") }
            src.isNotBlank() &&
                !src.contains("logo") && !src.contains("avatar") &&
                !src.contains("torrborder") && !src.contains("uTorrent") &&
                !src.contains("smilie") && !src.contains("emoji") &&
                !src.startsWith("data:") &&
                Regex("\\.(jpg|jpeg|webp)(\\?|$)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(src)
        }?.let { fixUrlNull(it.absUrl("src").ifBlank { it.attr("src") }) }

        val plot = post.text().take(2000).ifBlank { null }
        val year = Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()

        // Collect every usable link from the first post.
        // Each quality block is: <.torrent filename link> <MAGNET button>
        // <DIRECT LINK button>. The filename text is remembered so the
        // DIRECT LINK of the same block can inherit its quality label.
        val links = LinkedHashMap<String, OneTamilMVLink>()
        var blockLabel = ""
        post.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) return@forEach
            // Magnet buttons just say "MAGNET" — use the torrent's own
            // display name (dn=) as the label, it carries quality + size.
            val label = a.text().trim().ifBlank { href }.let { text ->
                if (href.startsWith("magnet:") && text.length < 12) {
                    val dn = Regex("[&?]dn=([^&]+)").find(href)?.groupValues?.get(1)
                    try {
                        dn?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: text
                    } catch (e: Exception) {
                        text
                    }
                } else {
                    text
                }
            }
            if (label.length > 25 &&
                (label.contains(".mkv", true) || label.contains(".torrent", true))
            ) {
                blockLabel = label
            }
            // The DIRECT LINK button's own text carries no quality info —
            // tag it so loadLinks can resolve and prioritise it.
            val isDirectButton = a.hasClass("download-button") ||
                label.equals("DIRECT LINK", true)
            val storedLabel = if (isDirectButton) {
                "DIRECT | " + blockLabel.ifBlank { "Direct Link" }
            } else {
                label
            }
            val isMagnet = href.startsWith("magnet:")
            val isTorrent = href.endsWith(".torrent") ||
                href.contains("/attachments/") || href.contains("attachment.php")
            val isDirectVideo = Regex("\\.(mp4|mkv|avi|m3u8)(\\?|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(href)
            val isExternalHost = href.startsWith("http") &&
                !href.contains("1tamilmv") && !href.contains("tamilmv.") &&
                !href.contains("facebook.com") && !href.contains("twitter.com") &&
                !href.contains("t.me") && !href.contains("telegram")
            if (isMagnet || isTorrent || isDirectVideo || isExternalHost) {
                links.putIfAbsent(href, OneTamilMVLink(href, storedLabel))
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, links.values.toList().toJson()) {
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

    /** Display order: playable direct files first, torrents/magnets last. */
    private fun linkPriority(link: OneTamilMVLink): Int {
        return when {
            link.label.startsWith("DIRECT |") -> 0
            Regex("\\.(mp4|mkv|avi|m3u8)(\\?|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(link.url) -> 1
            link.url.startsWith("magnet:") -> 3
            link.url.endsWith(".torrent") || link.url.contains("/attachments/") ||
                link.url.contains("attachment.php") -> 4
            else -> 2
        }
    }

    /**
     * Resolve a DIRECT LINK shortener chain to the final file URL.
     * Verified live 2026-10-23: cyberloom.best/l/<code> redirects to an
     * interstitial page whose "Continue to Destination" (/out?t=) link
     * redirects to a file page that carries the direct CDN file link.
     * Handing the raw shortener URL to the player fails with a
     * "parsing container" error, so resolving here is required.
     */
    private suspend fun resolveShortLink(url: String): String? {
        return try {
            val hop1 = app.get(url).document
            val outUrl = hop1.select("a[href*=/out?t=]").firstOrNull()
                ?.absUrl("href")?.ifBlank { null }
                ?: hop1.select("a").firstOrNull {
                    it.text().contains("Continue", ignoreCase = true)
                }?.absUrl("href")?.ifBlank { null }
                ?: return null
            val hop2 = app.get(outUrl).document
            hop2.select("a[href]").mapNotNull {
                it.absUrl("href").ifBlank { null }
            }.firstOrNull { candidate ->
                candidate.contains("/files/") &&
                    Regex("\\.(mkv|mp4|avi)(\\?|$)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(candidate)
            }
        } catch (e: Exception) {
            null
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
        links.sortedBy { linkPriority(it) }.forEach { link ->
            val quality = parseQuality(link.label + " " + link.url)
            when {
                link.label.startsWith("DIRECT |") -> {
                    // Shortened DIRECT LINK — resolve it to the real file
                    // first; only fall back to the raw URL if that fails.
                    val shortLabel = link.label.removePrefix("DIRECT | ")
                    val resolved = resolveShortLink(link.url)
                    when {
                        resolved != null -> {
                            callback.invoke(
                                newExtractorLink(
                                    name, "Direct — $shortLabel", resolved,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                }
                            )
                            found = true
                        }
                        loadExtractor(link.url, mainUrl, subtitleCallback, callback) -> {
                            found = true
                        }
                        else -> {
                            callback.invoke(
                                newExtractorLink(
                                    name, "Direct — $shortLabel", link.url,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.referer = mainUrl
                                }
                            )
                            found = true
                        }
                    }
                }
                link.url.startsWith("magnet:") -> {
                    callback.invoke(
                        newExtractorLink(name, "Magnet — ${link.label}", link.url, ExtractorLinkType.MAGNET) {
                            this.quality = quality
                        }
                    )
                    found = true
                }
                link.url.endsWith(".torrent") || link.url.contains("/attachments/") ||
                    link.url.contains("attachment.php") -> {
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
