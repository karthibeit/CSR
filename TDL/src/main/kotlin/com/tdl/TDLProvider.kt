package com.tdl

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * TDL — TamilDhool (tamildhool.tech) for CloudStream.
 *
 * The site is a WordPress archive of Tamil TV serials and shows:
 *  - Listing pages (home, channel categories) are <article> cards for
 *    single EPISODE posts, each with its own poster image.
 *  - A serial/show page is an archive of its episodes, paginated
 *    (page/2/, page/3/, ...); episodes are dated posts under it.
 *  - "Programs" pages list the serial/show pages themselves.
 *
 * Episode pages carry no visible player. Each "Watch Episode" button is
 * a teamstoday.com gateway link (?video=<id>) paired with a thumbnail
 * that reveals the real host:
 *  - BunnyCDN thumb (vz-….b-cdn.net/<uuid>/thumbnail.jpg)
 *      -> https://<same host>/<uuid>/playlist.m3u8   (Referer required)
 *  - VibrantPulseLab thumb (media.vibrantpulselab.com/thumbs/<id>.webp)
 *      -> https://media.vibrantpulselab.com/<id>/playlist.m3u8 (Referer required)
 *  - Vibrant live thumb (…/live-thumbs/<id>.webp)
 *      -> https://media.vibrantpulselab.com/live/<id>/index.m3u8
 *  - Vimeo (…/?video=<num>#vimeo) -> player.vimeo.com extractor
 *  - Bunny live (id "bunny-live-<slug>")
 *      -> https://pbvideo-live-test.b-cdn.net/live/<slug>/index.m3u8
 * All patterns were verified live against the site on 2026-11-02.
 */
class TDLProvider : MainAPI() {
    override var mainUrl = "https://www.tamildhool.tech"
    override var name = "TDL"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Episodes",
        "$mainUrl/sun-tv/sun-tv-serial/" to "Sun TV Serials",
        "$mainUrl/vijay-tv/vijay-tv-serial/" to "Vijay TV Serials",
        "$mainUrl/zee-tamil/zee-tamil-serial/" to "Zee Tamil Serials",
        "$mainUrl/sony-vizha/sony-vizha-serial/" to "Sony Vizha Serials",
        "$mainUrl/sun-tv/sun-tv-show/" to "Sun TV Shows",
        "$mainUrl/vijay-tv/vijay-tv-show/" to "Vijay TV Shows",
        "$mainUrl/zee-tamil/zee-tamil-show/" to "Zee Tamil Shows",
        "$mainUrl/sony-vizha/sony-vizha-show/" to "Sony Vizha Shows",
        "$mainUrl/sun-tv-programs/" to "Sun TV Programs",
        "$mainUrl/vijay-tv-programs/" to "Vijay TV Programs",
        "$mainUrl/zee-tamil-programs/" to "Zee Tamil Programs"
    )

    // ------------------------------------------------------------------
    // URL shapes
    // ------------------------------------------------------------------

    private val serialUrlRegex =
        Regex("""/(sun-tv|vijay-tv|zee-tamil|sony-vizha)/[a-z0-9-]+-(serial|show)/[a-z0-9-]+/?$""")

    private val dateSlugRegex = Regex("""(\d{2})-(\d{2})-(\d{4})""")

    private fun isSerialUrl(url: String): Boolean = serialUrlRegex.containsMatchIn(url)

    private fun isEpisodeUrl(url: String): Boolean {
        val slug = url.trimEnd('/').substringAfterLast('/')
        return dateSlugRegex.containsMatchIn(slug) ||
            slug.contains("live") || url.contains("/news-gossips/")
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun Element.imgSrc(): String? {
        val img = selectFirst("img") ?: return null
        val src = img.attr("data-lazy-src").ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun cleanCardTitle(raw: String): String =
        raw.replace(Regex("\\s+"), " ").replace(" | ", " – ").trim()

    private fun cleanPageTitle(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        listOf("• TamilDhool", "- TamilDhool", "| TamilDhool").forEach {
            t = t.replace(it, "")
        }
        // Serial pages: "Lakshmi – Latest Full Episodes in HD" -> "Lakshmi"
        val cut = t.indexOf(" – Latest")
        if (cut > 0) t = t.substring(0, cut)
        return t.trim().trimEnd('-', '–').trim()
    }

    private val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /** "lakshmi-02-11-2026-sun-tv-serial" -> Triple(2026, 11, 2) or null. */
    private fun dateOfSlug(slug: String): Triple<Int, Int, Int>? {
        val m = dateSlugRegex.find(slug) ?: return null
        val (d, mo, y) = m.destructured
        return Triple(y.toInt(), mo.toInt(), d.toInt())
    }

    private fun dateLabel(date: Triple<Int, Int, Int>): String {
        val (y, mo, d) = date
        val mon = months.getOrNull(mo - 1) ?: mo.toString()
        return "$d $mon $y"
    }

    // ------------------------------------------------------------------
    // Listing parsing
    // ------------------------------------------------------------------

    /** Episode cards from a listing page's <article> elements. */
    private fun Document.episodeCards(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        for (article in this.select("article")) {
            // The episode link is the heading anchor when present; else
            // the first anchor pointing at a dated post.
            val anchors = article.select("a[href]")
            val link = anchors.firstOrNull {
                isEpisodeUrl(it.attr("href"))
            } ?: anchors.firstOrNull {
                it.attr("href").contains(mainUrl.removePrefix("https://"))
            } ?: continue
            val href = fixUrl(link.attr("href"))
            if (!seen.add(href)) continue
            val heading = article.selectFirst("h1, h2, h3, h4")?.text()
            val title = cleanCardTitle(
                heading?.takeIf { it.isNotBlank() }
                    ?: link.attr("title").takeIf { it.isNotBlank() }
                    ?: anchors.maxByOrNull { it.text().length }?.text()
                    ?: continue
            )
            if (title.length < 3) continue
            out.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = article.imgSrc()
            })
        }
        return out
    }

    /** Serial/show cards from a Programs page (anchors, not articles). */
    private fun Document.programCards(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in this.select("a[href]")) {
            val href = fixUrl(a.attr("href"))
            if (!isSerialUrl(href) || !seen.add(href)) continue
            val title = cleanCardTitle(
                a.text().takeIf { it.isNotBlank() }
                    ?: a.attr("title")
            )
            if (title.length < 3) continue
            out.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = a.imgSrc()
            })
        }
        return out
    }

    // ------------------------------------------------------------------
    // Main page / search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isPrograms = request.data.contains("-programs")
        if (isPrograms) {
            if (page > 1) return newHomePageResponse(request, emptyList(), false)
            val doc = app.get(request.data).document
            return newHomePageResponse(request, doc.programCards(), false)
        }
        val url = if (page > 1) {
            request.data.trimEnd('/') + "/page/$page/"
        } else {
            request.data
        }
        val doc = app.get(url).document
        val items = doc.episodeCards()
        return newHomePageResponse(request, items, hasNext = items.size >= 8)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document
        val results = mutableListOf<SearchResponse>()
        // Serial/show pages matching the query come first.
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        results.addAll(doc.programCards().filter { card ->
            val t = card.name.lowercase()
            tokens.all { it in t }
        })
        results.addAll(doc.episodeCards())
        return results.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.title()
        val title = cleanPageTitle(rawTitle)
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
        ) ?: doc.selectFirst("article")?.imgSrc()
            ?: doc.selectFirst("img[src*=b-cdn.net]")?.let { fixUrlNull(it.attr("src")) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        if (isSerialUrl(url)) {
            // Episodes are child posts of the serial archive, paginated.
            val base = url.trimEnd('/') + "/"
            val maxPage = doc.select("a[href*=/page/]")
                .mapNotNull { Regex("""/page/(\d+)/""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull() ?: 1
            val childUrls = LinkedHashSet<String>()
            fun collect(d: Document) {
                d.select("a[href]").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    if (href.startsWith(base) && href != base &&
                        !href.contains("/page/")
                    ) {
                        childUrls.add(href)
                    }
                }
            }
            collect(doc)
            val lastPage = minOf(maxPage, 10)
            for (p in 2..lastPage) {
                try {
                    collect(app.get(base + "page/$p/").document)
                } catch (e: Exception) {
                    break
                }
            }
            data class EpItem(val url: String, val date: Triple<Int, Int, Int>?)
            val items = childUrls.map { child ->
                EpItem(child, dateOfSlug(child.substringAfterLast('/')))
            }
            val dated = items.filter { it.date != null }
                .sortedWith(compareBy({ it.date!!.first }, { it.date!!.second }, { it.date!!.third }))
            val undated = items.filter { it.date == null }
            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
            dated.forEachIndexed { i, item ->
                episodes.add(newEpisode(item.url) {
                    this.name = dateLabel(item.date!!)
                    this.season = 1
                    this.episode = i + 1
                })
            }
            undated.forEachIndexed { j, item ->
                val slug = item.url.trimEnd('/').substringAfterLast('/')
                episodes.add(newEpisode(item.url) {
                    this.name = if (slug.contains("live")) "LIVE 24/7" else slug.replace('-', ' ')
                    this.season = 1
                    this.episode = dated.size + j + 1
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        // Single episode / promo / live post — plays directly.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        // Group the gateway anchors by video id, remembering the
        // thumbnail that reveals the real host.
        data class Watch(val id: String, val frag: String, val img: String?)
        val watches = LinkedHashMap<String, Watch>()
        for (a in doc.select("a[href*=teamstoday.com/?video=]")) {
            val href = a.attr("href")
            val id = href.substringAfter("?video=").substringBefore("#")
                .substringBefore("&")
            if (id.isBlank()) continue
            val frag = href.substringAfter('#', "")
            val img = a.selectFirst("img")?.attr("src")?.ifBlank { null }
            val prev = watches[id]
            watches[id] = Watch(
                id, frag.ifBlank { prev?.frag ?: "" },
                img ?: prev?.img
            )
        }

        fun emitM3u8(linkName: String, linkUrl: String) {
            callback.invoke(
                newExtractorLink(name, linkName, linkUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("Referer" to "$mainUrl/")
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        for (w in watches.values) {
            val img = w.img ?: ""
            when {
                w.id.startsWith("bunny-live-") -> {
                    val slug = w.id.removePrefix("bunny-live-")
                    emitM3u8("Bunny LIVE", "https://pbvideo-live-test.b-cdn.net/live/$slug/index.m3u8")
                }
                img.contains("b-cdn.net") -> {
                    val host = try {
                        URI(img).host
                    } catch (e: Exception) {
                        null
                    } ?: "vz-8cf4325c-bc5.b-cdn.net"
                    emitM3u8("Server — Bunny HD", "https://$host/${w.id}/playlist.m3u8")
                }
                img.contains("live-thumbs") -> {
                    emitM3u8("Vibrant LIVE", "https://media.vibrantpulselab.com/live/${w.id}/index.m3u8")
                }
                img.contains("vibrantpulselab") -> {
                    emitM3u8("Server — Vibrant HD", "https://media.vibrantpulselab.com/${w.id}/playlist.m3u8")
                }
                w.frag.contains("vimeo") || img.contains("vimeocdn") -> {
                    if (loadExtractor(
                            "https://player.vimeo.com/video/${w.id}",
                            data, subtitleCallback, callback
                        )
                    ) {
                        found = true
                    }
                }
                else -> {
                    // Unknown gateway id — let the extractors try it.
                    if (loadExtractor(
                            "https://teamstoday.com/?video=${w.id}",
                            data, subtitleCallback, callback
                        )
                    ) {
                        found = true
                    }
                }
            }
        }
        return found
    }
}
