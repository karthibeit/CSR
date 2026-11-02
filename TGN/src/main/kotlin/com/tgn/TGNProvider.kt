package com.tgn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * TGN — TamilGun (tamilgun.now) for CloudStream.
 *
 * The site is a WordPress streaming archive. Listing pages (the
 * /movies/ hub and the /video-category/ archives) use a grid of
 * "image-item" cards: each card links to a /video/<slug>/ post and
 * carries its own poster, title and year. Category archives paginate
 * with page/N/. Every post is a single video (even web-series posts
 * pack their episodes into one file), so posts map to Movie.
 *
 * A post page embeds two players as iframes:
 *  - Player 1 is a hosted player page at <host>/videos/<uuid>
 *    (player4.spirituallifewell.com, player4.namtamil.com). Its own
 *    web app resolves playback through a JSON API on the same host:
 *        GET <host>/api/playback/resolve?contentType=movie&videoId=<uuid>
 *    which returns playbackOptions with direct HLS sourceUrls
 *    (arivumani.live CDN, Vimeo external playlists, tamils.click live
 *    channels). The streams need the player host as Referer.
 *  - Player 2 is usually playallu (a token-locked player with no
 *    public resolve API); it is handed to CloudStream's extractors
 *    as a fallback.
 * All patterns were verified live against the site on 2026-11-02.
 */
class TGNProvider : MainAPI() {
    override var mainUrl = "https://tamilgun.now"
    override var name = "TGN"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Latest",
        "$mainUrl/video-category/hd-movies/" to "Movies",
        "$mainUrl/video-category/dubbed-movies/" to "Dubbed Movies",
        "$mainUrl/video-category/web-series/" to "Web Series",
        "$mainUrl/video-category/malayalam/" to "Malayalam"
    )

    // ------------------------------------------------------------------
    // Listing parsing
    // ------------------------------------------------------------------

    /** Movie cards from a listing page's image-item grid. */
    private fun Document.movieCards(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        for (item in this.select("div.image-item")) {
            val a = item.selectFirst("a[href]") ?: continue
            val href = fixUrl(a.attr("href"))
            if (!href.startsWith(mainUrl) || !href.contains("/video/")) continue
            if (!seen.add(href)) continue
            val title = item.selectFirst(".item-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            val poster = item.selectFirst("img")?.let {
                fixUrlNull(
                    it.attr("data-lazy-src").ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("src") }
                )
            }
            out.add(newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isHub = request.data.endsWith("/movies/")
        if (isHub && page > 1) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }
        val url = if (page > 1) {
            request.data.trimEnd('/') + "/page/$page/"
        } else {
            request.data
        }
        val items = app.get(url).document.movieCards()
        return newHomePageResponse(request, items, hasNext = !isHub && items.size >= 12)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        return app.get(url).document.movieCards()
    }

    // ------------------------------------------------------------------
    // Detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.title()
            .removeSuffix(" - TamilGun").removeSuffix(" | TamilGun")
            .replace(Regex("\\s+"), " ").trim()
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(doc.selectFirst("meta[name=twitter:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val year = Regex("""\((?:19|20)\d{2}\)""").find(title)
            ?.value?.filter { it.isDigit() }?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    data class PlaybackOption(
        val label: String? = null,
        val isEmbed: Boolean = false,
        val sourceUrl: String? = null
    )

    data class ResolveResponse(
        val sourceUrl: String? = null,
        val playbackOptions: List<PlaybackOption> = emptyList()
    )

    private val playerEmbedRegex =
        Regex("""^(https?://[^/]+)/videos/([0-9a-fA-F-]{36})""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false
        val seenUrls = mutableSetOf<String>()

        suspend fun emitM3u8(linkName: String, linkUrl: String, referer: String) {
            if (!seenUrls.add(linkUrl)) return
            callback.invoke(
                newExtractorLink(name, linkName, linkUrl, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = mapOf("Referer" to referer)
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        val iframes = doc.select("iframe[src]").map { it.attr("src") }
            .filter { it.isNotBlank() }
        for ((index, src) in iframes.withIndex()) {
            val embed = playerEmbedRegex.find(src)
            if (embed == null) {
                // Unknown player (playallu etc.) — let extractors try.
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    found = true
                }
                continue
            }
            val host = embed.groupValues[1]
            val videoId = embed.groupValues[2]
            val playerNo = index + 1
            try {
                val text = app.get(
                    "$host/api/playback/resolve?contentType=movie&videoId=$videoId",
                    referer = "$host/"
                ).text
                val parsed = try {
                    parseJson<ResolveResponse>(text)
                } catch (e: Exception) {
                    null
                }
                val options = when {
                    parsed == null -> emptyList()
                    parsed.playbackOptions.isNotEmpty() -> parsed.playbackOptions
                    parsed.sourceUrl != null -> listOf(
                        PlaybackOption(label = "Server", sourceUrl = parsed.sourceUrl)
                    )
                    else -> emptyList()
                }
                if (options.isEmpty()) {
                    // Last resort: pull sourceUrl values straight from
                    // the raw JSON text.
                    val raw = Regex(""""sourceUrl"\s*:\s*"([^"]+)"""")
                        .findAll(text).map { it.groupValues[1] }.toList()
                    for ((j, rawUrl) in raw.withIndex()) {
                        emitM3u8("Player $playerNo · Server ${j + 1}", rawUrl, "$host/")
                    }
                } else {
                    for (option in options) {
                        val linkUrl = option.sourceUrl?.takeIf { it.isNotBlank() }
                            ?: continue
                        val label = option.label?.takeIf { it.isNotBlank() } ?: "Server"
                        if (option.isEmbed) {
                            if (loadExtractor(linkUrl, data, subtitleCallback, callback)) {
                                found = true
                            }
                        } else {
                            emitM3u8("Player $playerNo · $label", linkUrl, "$host/")
                        }
                    }
                }
            } catch (e: Exception) {
                // Resolve API failed for this player — try the raw embed.
                if (loadExtractor(src, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }
}
