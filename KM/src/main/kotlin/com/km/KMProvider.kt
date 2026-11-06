package com.km

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
import java.net.URLEncoder

/**
 * KM — KMMOVIES (kmmovies.autos, the new home of Khatrimaza).
 *
 * WordPress catalog with quality-first categories (4K, 10bit, 60fps,
 * BluRay Remux, Dual Audio) and language sections (Tamil, Telugu,
 * Kannada, South, Hindi Dubbed, plus KDrama). Listing cards are
 * article.movie-card elements: first anchor is the post, img.poster
 * the poster, h3.movie-title the "Title (Year)" text, .badge-left the
 * top quality label (e.g. "4K WEB-DL").
 *
 * A post page carries a .downloads-section. Movie buttons (a.dl-btn)
 * point at the site's own host hub, one.kmcloud.lol/s/<code>, each
 * labelled with .dl-res, .dl-quality and .dl-size spans. Series posts
 * group buttons into .season-block elements with three tab contents
 * (data-type starts with episodes, combined or zip): episode-wise
 * buttons point at episodes.magiclinks.lol pages, which sit behind a
 * Cloudflare check page, so v1 skips those and uses the Combined and
 * Zip season packs, which are kmcloud links like the movie ones.
 *
 * A kmcloud page lists per-host routes /d/<code>/<host>. Two hosts
 * resolve to streamable files (both verified live 2026-11-06):
 * pixeldrain redirects to pixeldrain.com/u/<id>, whose file answers
 * at pixeldrain.com/api/file/<id>; gofile redirects to a
 * gofile.io/d/<id> folder, handed to the built-in GoFile extractor.
 * All patterns were verified live against the site on 2026-11-06.
 */
class KMProvider : MainAPI() {
    override var mainUrl = "https://kmmovies.autos"
    override var name = "KMMovies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/4k/" to "4K",
        "$mainUrl/category/tamil/" to "Tamil",
        "$mainUrl/category/south/" to "South",
        "$mainUrl/category/telugu/" to "Telugu",
        "$mainUrl/category/kannada/" to "Kannada",
        "$mainUrl/category/hindi-dubbed/" to "Hindi Dubbed",
        "$mainUrl/category/dual-audio/" to "Dual Audio",
        "$mainUrl/category/bollywood/" to "Bollywood",
        "$mainUrl/category/hollywood/" to "Hollywood",
        "$mainUrl/category/bluray-remux/" to "BluRay Remux",
        "$mainUrl/category/kdrama/" to "KDrama",
        "$mainUrl/category/tv-series/" to "TV Series",
        "$mainUrl/category/anime/" to "Anime"
    )

    private val yearRegex = Regex("""(?:19|20)\d{2}""")

    // ------------------------------------------------------------------
    // Listing parsing
    // ------------------------------------------------------------------

    private fun Document.movieCards(): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<SearchResponse>()
        for (card in this.select("article.movie-card")) {
            val a = card.selectFirst("a[href]") ?: continue
            val href = fixUrl(a.attr("href"))
            if (!href.startsWith(mainUrl)) continue
            if (href.contains("/category/") || href.contains("/genre/")) continue
            if (!seen.add(href)) continue
            val rawTitle = card.selectFirst("h3.movie-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            val year = yearRegex.find(rawTitle)?.value?.toIntOrNull()
            val title = rawTitle
                .replace(Regex("""\((?:19|20)\d{2}\)"""), "").trim()
                .replace(Regex("""\s+"""), " ")
            val poster = card.selectFirst("img.poster")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            }?.takeIf { it.isNotBlank() }
            val badge = card.selectFirst(".badge-left")?.text()?.trim().orEmpty()
            val quality = when {
                badge.contains("4K") || badge.contains("2160") -> SearchQuality.UHD
                badge.contains("1080") -> SearchQuality.HD
                else -> null
            }
            out.add(
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = quality
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
        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val year = yearRegex.find(h1)?.value?.toIntOrNull()
        val title = h1
            .replace(Regex("""\((?:19|20)\d{2}\)"""), "").trim()
            .replace(Regex("""\s+"""), " ")
            .ifBlank { doc.title() }
        val poster = doc.select("img[src*=tmdb/poster]").firstOrNull()?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val backdrop = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        // Quick-facts block: "Type Movie IMDb 6.2 Release 2023 ..."
        val facts = doc.selectFirst("[class*=about-highlights]")?.text().orEmpty()
        val score = Regex("""IMDb\s+(\d+(?:\.\d+)?)""").find(facts)
            ?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { Score.from10(it) }
        val genres = doc.select("a[href*=/genre/]").map { it.text().trim() }
            .filter { it.length in 2..24 && !it.equals("Genres", true) }
            .distinct()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.score = score
            this.tags = genres.takeIf { it.isNotEmpty() }
        }
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /** One kmcloud button on a post page: hub URL plus its label parts. */
    private data class DlJob(val label: String, val url: String, val quality: Int)

    private fun qualityOf(text: String): Int {
        return when {
            text.contains("2160") || text.contains("4K") -> Qualities.P2160.value
            text.contains("1080") -> Qualities.P1080.value
            text.contains("720") -> Qualities.P720.value
            text.contains("480") -> Qualities.P480.value
            text.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /** Collect every kmcloud button from a post page, with context labels. */
    private fun Document.downloadJobs(): List<DlJob> {
        val jobs = mutableListOf<DlJob>()
        for (btn in this.select(".downloads-section a.dl-btn[href]")) {
            val href = btn.attr("href").trim()
            if (!href.contains("one.kmcloud.lol/s/")) continue
            val res = btn.selectFirst(".dl-res")?.text()?.trim().orEmpty()
            val qual = btn.selectFirst(".dl-quality")?.text()?.trim().orEmpty()
            val size = btn.selectFirst(".dl-size")?.text()?.trim().orEmpty()
            val resLabel = listOf(qual.ifBlank { res }, res)
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
            // Context: season block title and tab kind (combined or zip),
            // when the button belongs to a series post.
            val season = btn.parents().firstOrNull { it.hasClass("season-block") }
                ?.selectFirst(".season-block-title")?.text()?.trim()
                ?.replace(Regex("""\s+"""), " ")
                ?.substringBefore("(")?.trim().orEmpty()
            val kind = btn.parents().firstOrNull { it.hasClass("type-content") }
                ?.attr("data-type")
                ?.substringBefore("-")?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.equals("Combined", true) || it.equals("Zip", true) }
                .orEmpty()
            val label = listOf(season, kind, resLabel, size)
                .filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "Download" }
            jobs.add(DlJob(label, href, qualityOf(resLabel)))
        }
        return jobs
    }

    /**
     * Resolve one kmcloud hub page: pixeldrain becomes a direct file
     * link, gofile goes through the built-in extractor. Returns true
     * when at least one playable link was emitted.
     */
    private suspend fun resolveHub(
        job: DlJob,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val doc = try {
            app.get(job.url).document
        } catch (e: Exception) {
            return false
        }
        val routes = doc.select("a[href*=/d/]").map { it.attr("href") }.distinct()
        val pixeldrain = routes.firstOrNull { it.contains("/pixeldrain") }
        if (pixeldrain != null) {
            try {
                val finalUrl = app.get(pixeldrain).url
                val id = Regex("""pixeldrain\.com/u/([A-Za-z0-9]+)""")
                    .find(finalUrl)?.groupValues?.get(1)
                if (id != null) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            job.label + " · Pixeldrain",
                            "https://pixeldrain.com/api/file/" + id,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = job.quality
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                // A dead mirror must not sink the other routes.
            }
        }
        val gofile = routes.firstOrNull { it.contains("/gofile") }
        if (gofile != null) {
            try {
                val finalUrl = app.get(gofile).url
                if (finalUrl.contains("gofile.io/d/")) {
                    if (loadExtractor(finalUrl, job.url, subtitleCallback, callback)) {
                        found = true
                    }
                }
            } catch (e: Exception) {
                // Same reasoning as above.
            }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val jobs = app.get(data).document.downloadJobs()
        if (jobs.isEmpty()) return false
        val results = coroutineScope {
            jobs.map { job ->
                async { resolveHub(job, subtitleCallback, callback) }
            }.awaitAll()
        }
        return results.any { it }
    }
}
