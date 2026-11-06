package com.ty

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * TY — TamilYogi (tamilyogi.se), a streaming (not torrent) Tamil and
 * dubbed-movie site. Its Series and Anime archives were empty when
 * checked on 2026-11-06, so v1 covers Movies and the genre pages.
 *
 * Listing cards are plain anchors to /movies/<slug>/ (or /anime/…)
 * holding a TMDB poster img, an h3 title and a year paragraph.
 * Listing pages paginate as page/<N>/ below the section path, and
 * search is the WordPress ?s= route; both reuse the same cards.
 *
 * A movie page offers two or three server buttons (data-url
 * attributes) plus a default iframe: a YouTube trailer (ignored), a
 * minochinos.com embed that redirects to callistanise.com, and a
 * hanerix.com embed. Both player hosts serve the same packed-JS
 * player family: the page hides its HLS address inside a Dean
 * Edwards packed script, which loadLinks unpacks (the algorithm was
 * verified live on 2026-11-06 against both hosts, yielding real
 * master.m3u8 URLs on their CDNs). The stream URL is emitted with
 * the embed page as referer, which those CDNs check.
 */
class TYProvider : MainAPI() {
    override var mainUrl = "https://tamilyogi.se"
    override var name = "TamilYogi"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies — Latest",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/drama/" to "Drama",
        "$mainUrl/genre/crime/" to "Crime",
        "$mainUrl/genre/romance/" to "Romance",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/fantasy/" to "Fantasy",
        "$mainUrl/genre/mystery/" to "Mystery",
        "$mainUrl/genre/sci-fi/" to "Sci-Fi",
        "$mainUrl/genre/family/" to "Family",
        "$mainUrl/genre/adventure/" to "Adventure",
        "$mainUrl/genre/musical/" to "Musical"
    )

    private val yearRegex = Regex("""(?:19|20)\d{2}""")
    private val postPathRegex = Regex("""/(?:movies|anime)/[^/]+/$""")

    // ------------------------------------------------------------------
    // Listing parsing
    // ------------------------------------------------------------------

    private fun Document.movieCards(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        for (a in this.select("a[href]")) {
            val href = fixUrl(a.attr("href"))
            if (!href.startsWith(mainUrl)) continue
            if (!postPathRegex.containsMatchIn(href.removePrefix(mainUrl))) continue
            val img = a.selectFirst("img") ?: continue
            val h3 = a.selectFirst("h3") ?: continue
            if (!seen.add(href)) continue
            val title = h3.text().trim().takeIf { it.isNotBlank() } ?: continue
            val poster = img.attr("src").takeIf { it.isNotBlank() }
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
            val year = a.select("p").firstOrNull { yearRegex.containsMatchIn(it.text()) }
                ?.text()?.let { yearRegex.find(it)?.value?.toIntOrNull() }
            out.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            )
        }
        return out
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data else request.data + "page/" + page + "/"
        val items = app.get(url).document.movieCards()
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        return app.get(url).document.movieCards()
    }

    // ------------------------------------------------------------------
    // Detail page
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val html = doc.html()
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: doc.title()
        // The page shows the year as a lone chip; JSON-LD is the backup.
        val year = doc.select("span").firstOrNull {
            Regex("""^(?:19|20)\d{2}$""").matches(it.text().trim())
        }?.text()?.trim()?.toIntOrNull()
            ?: Regex(""""datePublished":"((?:19|20)\d{2})""").find(html)
                ?.groupValues?.get(1)?.toIntOrNull()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val plot = Regex(""""description":"((?:[^"\\]|\\.)*)"""").find(html)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")?.replace("\\\"", "\"")?.replace("\\n", " ")
            ?.trim()?.takeIf { it.isNotBlank() && !it.contains("TamilYogi", true) }
        val duration = Regex(""""duration":"PT(\d+)M"""").find(html)
            ?.groupValues?.get(1)?.toIntOrNull()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.year = year
            this.plot = plot
            this.duration = duration
        }
    }

    // ------------------------------------------------------------------
    // Playback: unpack the embed players' packed scripts
    // ------------------------------------------------------------------

    private val packedRegex = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val m3u8Regex = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")

    /** Decode one Dean Edwards packed script, or return null. */
    private fun unpackPacked(text: String): String? {
        val m = packedRegex.find(text) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        val count = m.groupValues[3].toIntOrNull() ?: return null
        val keywords = m.groupValues[4].split("|")
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (radix < 2 || radix > chars.length) return null
        fun encode(n0: Int): String {
            var n = n0
            var s = ""
            while (n >= radix) {
                s = chars[n % radix] + s
                n /= radix
            }
            return chars[n].toString() + s
        }
        val dict = HashMap<String, String>()
        for (i in count - 1 downTo 0) {
            if (i < keywords.size && keywords[i].isNotEmpty()) {
                dict[encode(i)] = keywords[i]
            }
        }
        return Regex("""\b\w+\b""").replace(payload) { dict[it.value] ?: it.value }
    }

    /** All m3u8 URLs in a player page, packed or plain. */
    private fun extractM3u8(text: String): List<String> {
        val direct = m3u8Regex.findAll(text).map { it.value }.toList()
        if (direct.isNotEmpty()) return direct
        val unpacked = unpackPacked(text) ?: return emptyList()
        return m3u8Regex.findAll(unpacked).map { it.value }.toList()
    }

    /** Resolve one embed page to stream links. True when any emitted. */
    private suspend fun resolveEmbed(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = try {
            app.get(embedUrl, headers = mapOf("Referer" to "$mainUrl/"))
        } catch (e: Exception) {
            return false
        }
        val links = extractM3u8(resp.text)
        if (links.isEmpty()) return false
        val seen = mutableSetOf<String>()
        var found = false
        for (link in links) {
            if (!seen.add(link.substringBefore("?"))) continue
            callback.invoke(
                newExtractorLink(name, label, link, ExtractorLinkType.M3U8) {
                    this.referer = resp.url
                    this.headers = mapOf("Referer" to resp.url)
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val embeds = LinkedHashSet<String>()
        for (f in doc.select("iframe[src]")) {
            val src = f.attr("src").trim()
            if (src.isNotBlank() && !src.contains("youtube")) embeds.add(src)
        }
        for (el in doc.select("[data-url]")) {
            val u = el.attr("data-url").trim()
            if (u.startsWith("http") && !u.contains("youtube")) embeds.add(u)
        }
        val playable = embeds.filter { it.contains("/embed/") || it.contains("/e/") }
        if (playable.isEmpty()) return false
        val results = coroutineScope {
            playable.mapIndexed { i, embed ->
                async { resolveEmbed(embed, "Server " + (i + 1), callback) }
            }.awaitAll()
        }
        return results.any { it }
    }
}
