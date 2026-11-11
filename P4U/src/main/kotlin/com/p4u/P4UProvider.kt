package com.p4u

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

/**
 * P4U — Play4u (play4u.org), a streaming site (its own HLS player, no
 * torrents and no download gauntlet). The catalog is server-rendered:
 * article.poster-card cards carry data-title, data-year, data-quality
 * and data-search attributes, and link to /watch/tt<IMDb id> for movies
 * or /series/tt<IMDb id> for series. Filters are query parameters
 * (type=movie, type=series, lang=ta) and listing pages paginate with
 * page=N. The site has no server search — its search box filters the
 * loaded catalog in the browser — so search() below filters the first
 * catalog pages the same way, using each card's data-search text.
 *
 * A watch page embeds its data as JSON in the page: __PA_PLAY__ for
 * the playable item (movie details plus a streams list) and, on series
 * pages, __PA_SERIES__ with every season and episode (episode page
 * addresses follow the pattern /series/<imdb>/s-NN/ep-NN under the
 * series root). Stream addresses are "sealed": strip the pa!~ prefix,
 * base64url-decode the rest, then XOR the bytes with the seal key
 * (the page's __PA_SEAL_KEY__, default pa4u-stream-seal-v1). The
 * result is a real HLS master address on an edge host — the whole
 * chain was verified from a plain HTTP client on 2026-11-11 (Jailer 2
 * master playlist at 1080p with Hindi, Tamil, Telugu, Malayalam and
 * Kannada listed, English subtitles inside the playlist).
 */
class P4UProvider : MainAPI() {
    override var mainUrl = "https://play4u.org"
    override var name = "Play4u"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/?type=movie" to "Movies",
        "$mainUrl/?type=series" to "Series",
        "$mainUrl/?lang=ta" to "Tamil"
    )

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val sealKeyRegex =
        Regex("__PA_SEAL_KEY__\\s*=\\s*\"([^\"]+)\"")

    companion object {
        private const val DEFAULT_SEAL_KEY = "pa4u-stream-seal-v1"
        private const val SEAL_PREFIX = "pa!~"
    }

    // ------------------------------------------------------------------
    // DTOs for the embedded page JSON
    // ------------------------------------------------------------------

    data class PlayData(
        @JsonProperty("movie") val movie: MovieDto? = null,
        @JsonProperty("streams") val streams: List<StreamDto>? = null
    )

    data class MovieDto(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("runtime_minutes") val runtime: Int? = null,
        @JsonProperty("poster_url") val poster: String? = null,
        @JsonProperty("cover_url") val cover: String? = null
    )

    data class StreamDto(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("m3u8_url") val m3u8Url: String? = null,
        @JsonProperty("languages") val languages: List<LangDto>? = null,
        @JsonProperty("qualities") val qualities: List<QualityDto>? = null
    )

    data class LangDto(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("code") val code: String? = null
    )

    data class QualityDto(
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("slug") val slug: String? = null
    )

    data class SeriesData(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("backdrop") val backdrop: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("seasons") val seasons: Map<String, SeasonDto>? = null
    )

    data class SeasonDto(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("episodes") val episodes: Map<String, EpisodeDto>? = null
    )

    data class EpisodeDto(
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still") val still: String? = null
    )

    // ------------------------------------------------------------------
    // Listing parsing
    // ------------------------------------------------------------------

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.poster-link") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!href.startsWith(mainUrl)) return null
        val title = attr("data-title").trim()
            .ifBlank { selectFirst(".poster-name")?.text()?.trim() }
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val year = attr("data-year").toIntOrNull()
        val quality = when (attr("data-quality").trim()) {
            "2160p" -> SearchQuality.UHD
            "1080p", "720p" -> SearchQuality.HD
            else -> null
        }
        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.quality = quality
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            request.data
        } else {
            request.data +
                (if (request.data.contains("?")) "&" else "?") +
                "page=" + page
        }
        val items = app.get(url).document.select("article.poster-card")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val tokens = query.lowercase().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val results = LinkedHashMap<String, SearchResponse>()
        for (page in 1..6) {
            val url = if (page == 1) "$mainUrl/" else "$mainUrl/?page=$page"
            val doc = try {
                app.get(url).document
            } catch (e: Exception) {
                break
            }
            val cards = doc.select("article.poster-card")
            if (cards.isEmpty()) break
            for (card in cards) {
                val hay = (card.attr("data-search") + " " +
                    card.attr("data-title")).lowercase()
                if (tokens.all { hay.contains(it) }) {
                    card.toSearchResult()?.let { results[it.url] = it }
                }
            }
            if (results.size >= 40) break
        }
        return results.values.toList()
    }

    // ------------------------------------------------------------------
    // Embedded JSON helpers
    // ------------------------------------------------------------------

    /** The balanced-brace JSON object assigned to a page variable. */
    private fun extractJsonObject(html: String, marker: String): String? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') {
                    inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * The site's own unseal: strip the seal prefix, base64url-decode,
     * XOR the bytes with the cycling seal key. Plain addresses pass
     * through unchanged.
     */
    private fun unseal(value: String?, key: String): String? {
        if (value.isNullOrBlank()) return null
        if (!value.startsWith(SEAL_PREFIX)) {
            return value.takeIf { it.startsWith("http") }
        }
        var body = value.substring(SEAL_PREFIX.length)
            .replace('-', '+').replace('_', '/')
        while (body.length % 4 != 0) body += "="
        val raw = try {
            Base64.decode(body, Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
        val kb = key.toByteArray(Charsets.UTF_8)
        if (kb.isEmpty()) return null
        val out = ByteArray(raw.size) { i ->
            (raw[i].toInt() xor kb[i % kb.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // Detail pages
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        if (url.contains("/series/")) {
            val json = extractJsonObject(html, "__PA_SERIES__")
                ?: throw ErrorLoadingException("No series data on the page")
            val series: SeriesData = mapper.readValue(json)
            val imdb = series.imdbId
                ?: throw ErrorLoadingException("Series id missing")
            val episodes = mutableListOf<Episode>()
            var firstYear: Int? = null
            val seasonKeys = series.seasons?.keys
                ?.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .orEmpty()
            for (sKey in seasonKeys) {
                val season = series.seasons?.get(sKey) ?: continue
                val sNum = season.number ?: sKey.toIntOrNull() ?: 0
                if (firstYear == null) {
                    firstYear = season.year?.toIntOrNull()
                }
                val epKeys = season.episodes?.keys
                    ?.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                    .orEmpty()
                for (eKey in epKeys) {
                    val ep = season.episodes?.get(eKey) ?: continue
                    val eNum = ep.number ?: eKey.toIntOrNull() ?: 0
                    val epUrl = "$mainUrl/series/$imdb/s-" +
                        sNum.toString().padStart(2, '0') + "/ep-" +
                        eNum.toString().padStart(2, '0')
                    episodes.add(newEpisode(epUrl) {
                        this.name = ep.title
                        this.season = sNum
                        this.episode = eNum
                        this.description = ep.overview
                        this.posterUrl = ep.still
                    })
                }
            }
            if (episodes.isEmpty()) {
                throw ErrorLoadingException("No episodes on the page")
            }
            return newTvSeriesLoadResponse(
                series.title ?: "Play4u",
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.plot = series.overview
                this.posterUrl = series.poster
                this.backgroundPosterUrl = series.backdrop
                this.year = firstYear
            }
        }
        val json = extractJsonObject(html, "__PA_PLAY__")
            ?: throw ErrorLoadingException("No movie data on the page")
        val play: PlayData = mapper.readValue(json)
        val movie = play.movie
            ?: throw ErrorLoadingException("Movie data missing")
        return newMovieLoadResponse(
            movie.title ?: "Play4u",
            url,
            TvType.Movie,
            url
        ) {
            this.plot = movie.overview
            this.posterUrl = movie.poster
            this.backgroundPosterUrl = movie.cover
            this.year = movie.releaseDate?.take(4)?.toIntOrNull()
            this.duration = movie.runtime
        }
    }

    // ------------------------------------------------------------------
    // Playback
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
                        "P4U diag: $line",
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
        val html = try {
            app.get(data).text
        } catch (e: Exception) {
            diag.add("page fetch failed: " + (e.message ?: "error"))
            emitDiag(diag, data, callback)
            return false
        }
        val key = sealKeyRegex.find(html)?.groupValues?.get(1)
            ?: DEFAULT_SEAL_KEY
        val json = extractJsonObject(html, "__PA_PLAY__")
        if (json == null) {
            diag.add("no play data found on the page")
            emitDiag(diag, data, callback)
            return false
        }
        val play: PlayData = try {
            mapper.readValue(json)
        } catch (e: Exception) {
            diag.add("play data unreadable")
            emitDiag(diag, data, callback)
            return false
        }
        var emitted = 0
        for (stream in play.streams.orEmpty()) {
            val url = unseal(stream.m3u8Url, key)
            if (url == null) {
                diag.add("stream " + (stream.id ?: "?") + ": unseal failed")
                continue
            }
            val langs = stream.languages.orEmpty()
                .mapNotNull { it.name }
                .distinct()
                .joinToString(", ")
            val maxH = stream.qualities.orEmpty()
                .mapNotNull { it.height }
                .maxOrNull()
            val label = buildString {
                append(langs.ifBlank { "Play4u" })
                if (maxH != null) append(" · " + maxH + "p")
            }
            callback.invoke(
                newExtractorLink(
                    name,
                    label,
                    url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = when {
                        maxH == null -> Qualities.Unknown.value
                        maxH >= 2000 -> Qualities.P2160.value
                        maxH >= 1000 -> Qualities.P1080.value
                        maxH >= 700 -> Qualities.P720.value
                        maxH >= 400 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }
                }
            )
            emitted++
        }
        if (emitted == 0) {
            diag.add("streams=" + (play.streams?.size ?: 0) + ", none emitted")
            emitDiag(diag, data, callback)
            return false
        }
        return true
    }
}
