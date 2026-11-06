package com.ctv

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// CastleTV — the Castle app's catalog (api.hlowb.com) for CloudStream.
//
// Why this plugin exists: the Castle catalog carries most Indian
// releases in several audio languages, but how a title is packaged
// differs per title, and players that ignore the packaging end up
// stuck on one language (usually Hindi):
//  - Some languages are SEPARATE videos. The details response lists
//    one track per language; a track with existIndividualVideo=true
//    must be requested with its own languageId, and the video API
//    then returns that language's own stream URL.
//  - The remaining tracks share ONE multi-audio stream (a single
//    HLS playlist whose TS segments carry every one of those
//    languages as separate audio tracks). No URL can pick one of
//    those languages; the choice happens in the player, so those
//    links are labelled "Multi-audio ..." and are best played via
//    an external player with an audio-track menu (e.g. VLC).
// This provider follows the packaging exactly: one link per
// individual language, one clearly-labelled link for the shared
// multi-audio file, per resolution.
//
// API notes (verified live 2026-11-06):
//  - Every film-api response body is base64 AES-CBC ciphertext
//    (sometimes wrapped in quotes, sometimes in a JSON envelope).
//    The key comes from the getSecurityKey endpoint: base64-decode
//    its data, append the ASCII suffix below, zero-pad or truncate
//    to 16 bytes; the IV is the key itself.
//  - Playback URLs come from a POST to the getVideo2 endpoint;
//    passing a track's languageId returns that language's stream
//    when the track is an individual video, and the shared stream
//    when it is not.
class CTVProvider : MainAPI() {
    override var mainUrl = "https://api.hlowb.com"
    override var name = "CastleTV"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Home"
    )

    private val keySuffix = "T!BgJB"
    private var cachedKey: String? = null

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    // ------------------------------------------------------------------
    // API plumbing
    // ------------------------------------------------------------------

    data class SecurityKeyResponse(
        val code: Int = 0,
        val msg: String? = null,
        val data: String? = null
    )

    data class Envelope(
        val code: Int = 0,
        val msg: String? = null,
        val data: String? = null
    )

    private suspend fun getSecurityKey(): String? {
        cachedKey?.let { return it }
        return try {
            val text = app.get(
                "$mainUrl/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US"
            ).text
            val parsed = mapper.readValue<SecurityKeyResponse>(text)
            parsed.data?.also { cachedKey = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptData(text: String, apiKeyB64: String): String? {
        return try {
            var raw = text.trim()
            if (raw.startsWith("{")) {
                raw = mapper.readValue<Envelope>(raw).data ?: return null
            }
            if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length - 1)
            }
            val keyMaterial = base64DecodeArray(apiKeyB64) +
                keySuffix.toByteArray(StandardCharsets.US_ASCII)
            val key = ByteArray(16)
            System.arraycopy(keyMaterial, 0, key, 0, minOf(keyMaterial.size, 16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(key)
            )
            String(cipher.doFinal(base64DecodeArray(raw)), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun <T> apiGet(path: String, type: Class<T>): T? {
        val key = getSecurityKey() ?: return null
        return try {
            val decrypted = decryptData(app.get("$mainUrl$path").text, key) ?: return null
            mapper.readValue(decrypted, type)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Data model
    // ------------------------------------------------------------------

    data class HomeResponse(val code: Int = 0, val data: HomeData? = null)

    data class HomeData(val rows: List<HomeRow>? = null)

    data class HomeRow(
        val name: String? = null,
        val contents: List<HomeItem>? = null
    )

    data class HomeItem(
        val title: String? = null,
        val coverImage: String? = null,
        val redirectId: Long? = null,
        val movieType: Int? = null,
        val score: Double? = null,
        val languages: List<String>? = null
    )

    data class SearchResponseData(val rows: List<SearchItem>? = null)

    data class SearchApiResponse(val code: Int = 0, val data: SearchResponseData? = null)

    data class SearchItem(
        val id: Long? = null,
        val title: String? = null,
        val coverVerticalImage: String? = null,
        val coverHorizontalImage: String? = null,
        val movieType: Int? = null,
        val score: Double? = null,
        val languages: List<String>? = null
    )

    data class DetailsResponse(val code: Int = 0, val data: MovieDetails? = null)

    data class MovieDetails(
        val id: Long? = null,
        val title: String? = null,
        val score: Double? = null,
        val movieType: Int? = null,
        val coverHorizontalImage: String? = null,
        val coverVerticalImage: String? = null,
        val seasonDescription: String? = null,
        val languages: List<String>? = null,
        val briefIntroduction: String? = null,
        val publishTime: Long? = null,
        val tags: List<String>? = null,
        val actors: List<Person>? = null,
        val episodes: List<ApiEpisode>? = null,
        val seasonNumber: Int? = null,
        val seasons: List<Season>? = null
    )

    data class Person(
        val name: String? = null,
        val avatar: String? = null
    )

    data class Season(
        val movieId: Long? = null,
        val number: Int? = null
    )

    data class ApiEpisode(
        val id: Long? = null,
        val title: String? = null,
        val number: Int? = null,
        val coverImage: String? = null,
        val duration: Int? = null,
        val videos: List<VideoQuality>? = null,
        val tracks: List<Track>? = null,
        val subtitles: List<SubtitleData>? = null
    )

    data class VideoQuality(
        val resolution: Int? = null,
        val premiumProPermission: Boolean? = null
    )

    data class Track(
        val languageId: Int? = null,
        val languageName: String? = null,
        val abbreviate: String? = null,
        val existIndividualVideo: Boolean? = null,
        val order: Int? = null
    )

    data class SubtitleData(
        val abbreviate: String? = null,
        val title: String? = null,
        val url: String? = null
    )

    data class VideoResponse(val code: Int = 0, val data: VideoData? = null)

    data class VideoData(
        val videoUrl: String? = null,
        val isPreview: Boolean? = null,
        val permissionDenied: Boolean? = null,
        val subtitles: List<SubtitleData>? = null
    )

    // ------------------------------------------------------------------
    // Home and search
    // ------------------------------------------------------------------

    private fun typeOf(movieType: Int?): TvType = when (movieType) {
        1, 3, 5 -> TvType.TvSeries
        else -> TvType.Movie
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)
        val resp = apiGet(
            "/film-api/v0.1/category/home?channel=IndiaA&clientType=1&lang=en-US" +
                "&locationId=1001&mode=1&packageName=com.external.castle&page=1&size=17",
            HomeResponse::class.java
        ) ?: return newHomePageResponse(emptyList(), hasNext = false)
        val lists = mutableListOf<HomePageList>()
        for (row in resp.data?.rows.orEmpty()) {
            val rowName = row.name?.takeIf { it.isNotBlank() } ?: continue
            val items = row.contents.orEmpty().mapNotNull { item ->
                val id = item.redirectId ?: return@mapNotNull null
                val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = typeOf(item.movieType)
                val url = "$mainUrl/title/$id"
                if (type == TvType.TvSeries) {
                    newTvSeriesSearchResponse(title, url, type) {
                        this.posterUrl = item.coverImage
                    }
                } else {
                    newMovieSearchResponse(title, url, type) {
                        this.posterUrl = item.coverImage
                    }
                }
            }
            if (items.isNotEmpty()) {
                lists.add(HomePageList(rowName, items))
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val resp = apiGet(
            "/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=1" +
                "&keyword=$encoded&lang=en-US&mode=1&packageName=com.external.castle&page=1&size=30",
            SearchApiResponse::class.java
        ) ?: return emptyList()
        return resp.data?.rows.orEmpty().mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = typeOf(item.movieType)
            val url = "$mainUrl/title/$id"
            if (type == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, url, type) {
                    this.posterUrl = item.coverVerticalImage ?: item.coverHorizontalImage
                }
            } else {
                newMovieSearchResponse(title, url, type) {
                    this.posterUrl = item.coverVerticalImage ?: item.coverHorizontalImage
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Detail page
    // ------------------------------------------------------------------

    private suspend fun fetchDetails(movieId: String): MovieDetails? {
        return apiGet(
            "/film-api/v1.9.9/movie?channel=IndiaA&clientType=1&lang=en-US" +
                "&movieId=$movieId&packageName=com.external.castle",
            DetailsResponse::class.java
        )?.data
    }

    override suspend fun load(url: String): LoadResponse? {
        val movieId = url.substringAfterLast('/')
        val details = fetchDetails(movieId) ?: return null
        val title = details.title?.takeIf { it.isNotBlank() } ?: return null
        val poster = details.coverVerticalImage ?: details.coverHorizontalImage
        val backdrop = details.coverHorizontalImage ?: details.coverVerticalImage
        val year = details.publishTime?.let {
            Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.YEAR)
        }
        val score = details.score?.let { Score.from((it * 1000).toInt(), 1000) }
        val actors = details.actors.orEmpty().mapNotNull { person ->
            val actorName = person.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActorData(Actor(actorName, person.avatar))
        }
        val isSeries = details.movieType == 1 || details.movieType == 3 ||
            details.movieType == 5 || (details.episodes?.size ?: 0) > 1

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasons = details.seasons.orEmpty()
            if (seasons.size > 1) {
                for (season in seasons) {
                    val seasonId = season.movieId ?: continue
                    val seasonDetails = if (seasonId.toString() == movieId) {
                        details
                    } else {
                        fetchDetails(seasonId.toString())
                    } ?: continue
                    seasonDetails.episodes.orEmpty().forEachIndexed { index, ep ->
                        episodes.add(newEpisode("${seasonId}_${ep.id}") {
                            this.name = episodeName(ep, index)
                            this.season = season.number ?: seasonDetails.seasonNumber
                            this.episode = ep.number ?: (index + 1)
                            this.posterUrl = ep.coverImage
                        })
                    }
                }
            } else {
                details.episodes.orEmpty().forEachIndexed { index, ep ->
                    episodes.add(newEpisode("${details.id}_${ep.id}") {
                        this.name = episodeName(ep, index)
                        this.season = details.seasonNumber
                        this.episode = ep.number ?: (index + 1)
                        this.posterUrl = ep.coverImage
                    })
                }
            }
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = details.briefIntroduction
                this.year = year
                this.score = score
                this.tags = details.tags
                this.actors = actors
                this.duration = details.episodes?.firstOrNull()?.duration?.div(60)
            }
        }

        val episode = details.episodes?.firstOrNull()
        return newMovieLoadResponse(
            title, url, TvType.Movie, "${details.id}_${episode?.id}"
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = details.briefIntroduction
            this.year = year
            this.score = score
            this.tags = details.tags
            this.actors = actors
            this.duration = episode?.duration?.div(60)
        }
    }

    private fun episodeName(ep: ApiEpisode, index: Int): String {
        val number = ep.number ?: (index + 1)
        val raw = ep.title?.trim().orEmpty()
        return if (raw.isBlank() || raw == number.toString()) {
            "Episode $number"
        } else {
            raw
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private suspend fun fetchVideo(
        movieId: String,
        episodeId: String,
        languageId: Int?,
        resolution: Int,
        key: String
    ): VideoData? {
        val languagePart = if (languageId != null) {
            "\n                              \"languageId\": \"$languageId\","
        } else {
            ""
        }
        val body = """
            {
              "mode": "1",
              "appMarket": "GuanWang",
              "clientType": "1",
              "woolUser": "false",
              "apkSignKey": "ED0955EB04E67A1D9F3305B95454FED485261475",
              "androidVersion": "13",$languagePart
              "movieId": "$movieId",
              "episodeId": "$episodeId",
              "isNewUser": "true",
              "resolution": "$resolution",
              "packageName": "com.external.castle"
            }
        """.trimIndent()
        return try {
            val text = app.post(
                url = "$mainUrl/film-api/v2.0.1/movie/getVideo2" +
                    "?clientType=1&packageName=com.external.castle&channel=IndiaA&lang=en-US",
                requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
            ).text
            val decrypted = decryptData(text, key) ?: return null
            mapper.readValue<VideoResponse>(decrypted).data
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
        val parts = data.split("_")
        if (parts.size != 2) return false
        val movieId = parts[0].substringAfterLast('/')
        val episodeId = parts[1]
        val key = getSecurityKey() ?: return false
        val details = fetchDetails(movieId) ?: return false
        val episode = details.episodes?.firstOrNull { it.id?.toString() == episodeId }
            ?: details.episodes?.firstOrNull()
            ?: return false

        var subtitlesSent = false
        suspend fun sendSubtitles(subs: List<SubtitleData>?) {
            if (subtitlesSent) return
            val list = subs.orEmpty()
            if (list.isEmpty()) return
            subtitlesSent = true
            for (sub in list) {
                val subUrl = sub.url?.takeIf { it.isNotBlank() } ?: continue
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lang = sub.title ?: sub.abbreviate ?: "Unknown",
                        url = subUrl
                    )
                )
            }
        }
        sendSubtitles(episode.subtitles)

        val resolutions = episode.videos.orEmpty()
            .mapNotNull { it.resolution }
            .distinct()
            .sortedDescending()
            .ifEmpty { listOf(3, 2, 1) }

        // Group the tracks the way the title is packaged: tracks with
        // their own video are fetched per language; the rest share the
        // single multi-audio stream fetched without a languageId.
        val tracks = episode.tracks.orEmpty().sortedBy { it.order ?: Int.MAX_VALUE }
        val shared = tracks.filter { it.existIndividualVideo != true }
        val individual = tracks.filter { it.existIndividualVideo == true }

        data class Job(val languageId: Int?, val label: String)

        val jobs = mutableListOf<Job>()
        if (tracks.isEmpty()) {
            jobs.add(Job(null, name))
        } else {
            if (shared.isNotEmpty()) {
                val sharedNames = shared.mapNotNull {
                    it.languageName ?: it.abbreviate
                }
                val label = when {
                    individual.isEmpty() && sharedNames.size > 1 ->
                        "Multi-audio " + sharedNames.joinToString("/")
                    sharedNames.isNotEmpty() -> sharedNames.joinToString(" + ")
                    else -> name
                }
                jobs.add(Job(null, label))
            }
            for (track in individual) {
                val languageId = track.languageId ?: continue
                val label = track.languageName ?: track.abbreviate ?: "Language $languageId"
                jobs.add(Job(languageId, label))
            }
        }

        var found = false
        val seenPaths = mutableSetOf<String>()
        for (job in jobs) {
            for (resolution in resolutions) {
                val video = fetchVideo(movieId, episodeId, job.languageId, resolution, key)
                    ?: continue
                if (video.permissionDenied == true) continue
                val videoUrl = video.videoUrl?.takeIf { it.isNotBlank() } ?: continue
                if (!seenPaths.add(videoUrl.substringBefore('?'))) continue
                sendSubtitles(video.subtitles)
                val qualityLabel = when (resolution) {
                    3 -> "1080p"
                    2 -> "720p"
                    1 -> "480p"
                    else -> "${resolution}p"
                }
                val linkName = buildString {
                    append(job.label)
                    append(" · ")
                    append(qualityLabel)
                    if (video.isPreview == true) append(" (preview)")
                }
                callback.invoke(
                    newExtractorLink(name, linkName, videoUrl, ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to mainUrl)
                        this.quality = when (resolution) {
                            3 -> Qualities.P1080.value
                            2 -> Qualities.P720.value
                            1 -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                    }
                )
                found = true
            }
        }
        return found
    }
}
