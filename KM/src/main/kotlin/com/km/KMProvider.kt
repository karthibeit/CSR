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
import java.net.HttpURLConnection
import java.net.URL
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
        val poster = doc.select("img[src*=\"tmdb/poster\"]").firstOrNull()?.attr("src")
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
            val isHub = href.contains("one.kmcloud.lol/s/")
            val isMagic = href.contains("magiclinks.lol")
            if (!isHub && !isMagic) continue
            val res = btn.selectFirst(".dl-res")?.text()?.trim().orEmpty()
            val qual = btn.selectFirst(".dl-quality")?.text()?.trim().orEmpty()
            val size = btn.selectFirst(".dl-size")?.text()?.trim().orEmpty()
            val resLabel = listOf(qual.ifBlank { res }, res)
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
            // The WebDL category holds the original-quality files
            // (including every 2160p) behind magiclinks buttons; mark
            // those jobs so the user can tell them from encoded ones.
            val category = btn.parents()
                .firstOrNull { it.hasClass("download-category") }
                ?.selectFirst(".category-title")?.text()?.trim().orEmpty()
            val catLabel = if (category.contains("WebDL", true)) "WebDL" else ""
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
            val label = listOf(catLabel, season, kind, resLabel, size)
                .filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "Download" }
            jobs.add(DlJob(label, href, qualityOf(resLabel)))
        }
        return jobs
    }

    private val pixeldrainIdRegex = Regex("""pixeldrain\.com/u/([A-Za-z0-9]+)""")
    private val gofileRegex = Regex("""gofile\.io/d/([A-Za-z0-9]+)""")
    private val routeRegex = Regex("""one\.kmcloud\.lol/d/[A-Za-z0-9]+/[a-z]+""")
    private val directFileRegex = Regex("""https?://[^\s"'<>]+\.(?:mkv|mp4)(?:\?[^\s"'<>]*)?""")

    /** Emit a direct Pixeldrain file link for one file id. */
    private suspend fun emitPixeldrain(
        job: DlJob,
        id: String,
        callback: (ExtractorLink) -> Unit
    ) {
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
    }

    /**
     * Resolve one kmcloud hub page. v1 relied on the hub page's
     * anchors alone and produced "no link found" on-device, so v2
     * casts a wider net: anchors first, then a regex scan of the raw
     * page for host routes or direct host URLs, then the hub's
     * mydirect route, whose page is scanned the same way. Pixeldrain
     * becomes a direct file link; gofile goes through the built-in
     * extractor. Returns true when at least one link was emitted.
     */
    private suspend fun resolveHub(
        job: DlJob,
        postUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val resp = try {
            app.get(job.url, headers = mapOf("Referer" to postUrl))
        } catch (e: Exception) {
            return false
        }
        val html = resp.text
        val routes = LinkedHashSet<String>()
        for (a in resp.document.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.contains("/d/")) continue
            when {
                href.startsWith("http") -> routes.add(href)
                href.startsWith("/") -> routes.add("https://one.kmcloud.lol" + href)
            }
        }
        if (routes.isEmpty()) {
            for (m in routeRegex.findAll(html)) {
                routes.add("https://" + m.value)
            }
        }
        // The hub page might already carry direct host URLs.
        pixeldrainIdRegex.find(html)?.groupValues?.get(1)?.let { id ->
            emitPixeldrain(job, id, callback)
            found = true
        }
        if (found) return true
        gofileRegex.find(html)?.value?.let { path ->
            try {
                if (loadExtractor("https://" + path, job.url, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                // Fall through to the route-based attempts below.
            }
        }
        if (found) return true

        val pixeldrain = routes.firstOrNull { it.contains("/pixeldrain") }
        if (pixeldrain != null) {
            try {
                val pdResp = app.get(pixeldrain, headers = mapOf("Referer" to job.url))
                val id = pixeldrainIdRegex.find(pdResp.url)?.groupValues?.get(1)
                    ?: pixeldrainIdRegex.find(pdResp.text)?.groupValues?.get(1)
                if (id != null) {
                    emitPixeldrain(job, id, callback)
                    found = true
                }
            } catch (e: Exception) {
                // A dead mirror must not sink the other routes.
            }
        }
        val gofile = routes.firstOrNull { it.contains("/gofile") }
        if (gofile != null) {
            try {
                val gfResp = app.get(gofile, headers = mapOf("Referer" to job.url))
                val finalUrl = gfResp.url
                val gofileUrl = if (finalUrl.contains("gofile.io/d/")) {
                    finalUrl
                } else {
                    gofileRegex.find(gfResp.text)?.value?.let { "https://" + it }
                }
                if (gofileUrl != null) {
                    if (loadExtractor(gofileUrl, job.url, subtitleCallback, callback)) {
                        found = true
                    }
                }
            } catch (e: Exception) {
                // Same reasoning as above.
            }
        }
        if (found) return true

        // Last resort: the hub's mydirect route, scanned for anything
        // playable (a host page URL or a direct file URL).
        val mydirect = routes.firstOrNull { it.contains("/mydirect") }
            ?: (job.url.replace("/s/", "/d/") + "/mydirect")
        try {
            val mdResp = app.get(mydirect, headers = mapOf("Referer" to job.url))
            val mdHtml = mdResp.text
            val pdId = pixeldrainIdRegex.find(mdHtml)?.groupValues?.get(1)
            if (pdId != null) {
                emitPixeldrain(job, pdId, callback)
                found = true
            }
            val gf = gofileRegex.find(mdHtml)?.value
            if (gf != null) {
                if (loadExtractor("https://" + gf, job.url, subtitleCallback, callback)) {
                    found = true
                }
            }
            if (!found) {
                val fileUrl = directFileRegex.find(mdHtml)?.value
                if (fileUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            job.label + " · Direct",
                            fileUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = job.url
                            this.quality = job.quality
                        }
                    )
                    found = true
                }
            }
        } catch (e: Exception) {
            // Nothing more to try for this hub page.
        }
        return found
    }

    private val skydropRegex = Regex("""https?://[^\s"'<>]*skydrop\.sbs[^\s"'<>]*""")
    private val transferRegex = Regex("""https?://transfer\.it/t/[A-Za-z0-9]+""")
    private val vikingRegex = Regex("""https?://vikingfile\.com/f/[A-Za-z0-9]+""")
    private val buzzRegex = Regex("""https?://bzzhr\.co/[A-Za-z0-9]+""")

    /**
     * Follow HEAD redirects by hand (no body is ever downloaded) and
     * report the final URL plus its content type, or null when the
     * chain cannot be walked at all.
     */
    private fun headFinal(url: String): Pair<String, String?>? {
        var current = url
        repeat(5) {
            try {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val code = conn.responseCode
                val loc = conn.getHeaderField("Location")
                val type = conn.contentType
                conn.disconnect()
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    current = URL(URL(current), loc).toString()
                } else {
                    return current to type
                }
            } catch (e: Exception) {
                return null
            }
        }
        return current to null
    }

    /** Emit a direct-download endpoint (skydrop, transfer.it) as a link. */
    private suspend fun emitDirect(
        job: DlJob,
        hostLabel: String,
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolved = headFinal(url) ?: return false
        val finalUrl = resolved.first
        val type = resolved.second
        val typeOk = type != null &&
            (type.startsWith("video") || type.contains("octet-stream"))
        if (!typeOk && !isFileHref(finalUrl)) return false
        callback.invoke(
            newExtractorLink(
                name,
                job.label + " · " + hostLabel,
                finalUrl,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = job.url
                this.quality = job.quality
            }
        )
        return true
    }

    /**
     * Emit every host link found on one magiclinks hub page:
     * gofile goes through the built-in extractor, skydrop and
     * transfer.it are direct-download endpoints, and vikingfile
     * and buzzheavier pages are offered to the extractor
     * dispatcher as a best effort. Returns true when at least one
     * host produced a link.
     */
    private suspend fun emitMagicHosts(
        job: DlJob,
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        gofileRegex.find(html)?.value?.let { path ->
            try {
                if (loadExtractor("https://" + path, job.url, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                // Other hosts on the hub may still resolve.
            }
        }
        pixeldrainIdRegex.find(html)?.groupValues?.get(1)?.let { id ->
            emitPixeldrain(job, id, callback)
            found = true
        }
        skydropRegex.find(html)?.value?.let { u ->
            if (emitDirect(job, "Skydrop", u, callback)) found = true
        }
        transferRegex.find(html)?.value?.let { u ->
            if (emitDirect(job, "TransferIt", u, callback)) found = true
        }
        for (rx in listOf(vikingRegex, buzzRegex)) {
            rx.find(html)?.value?.let { u ->
                try {
                    if (loadExtractor(u, job.url, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (e: Exception) {
                    // Best effort only.
                }
            }
        }
        return found
    }

    /**
     * Resolve one magiclinks hub page (the WebDL buttons).
     *
     * Three routes, in order:
     *  1. Plain fetch of the hub page (works only if the host
     *     ever serves the anchors without a browser check).
     *  2. The rendered hub page in the browser view (KmCf):
     *     the server buttons are added by the page's scripts,
     *     so only the rendered page has them. GoFile goes to
     *     the built-in extractor; Skydrop and Transfer.it
     *     buttons open landing pages, so those are loaded in
     *     the browser view too and the final file link is
     *     pulled out of them; VikingFile and BuzzHeavier go
     *     to the extractor dispatcher.
     *  3. The v3 route: solve the check, refetch with the
     *     solved headers, parse the raw page.
     * Returns the success flag plus a one-line diagnostic
     * when every route failed.
     */
    private suspend fun resolveMagic(
        job: DlJob,
        postUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Pair<Boolean, String?> {
        val plainHtml = try {
            app.get(job.url, headers = mapOf("Referer" to postUrl)).text
        } catch (e: Exception) {
            null
        }
        if (plainHtml != null &&
            emitMagicHosts(job, plainHtml, subtitleCallback, callback)
        ) {
            return true to null
        }
        if (resolveMagicViaBrowser(job, subtitleCallback, callback)) {
            return true to null
        }
        val solved = KmCf.solve(job.url)
        if (solved.isEmpty()) {
            return false to "WebDL ${job.label}: browser=EMPTY"
        }
        val html = try {
            app.get(
                job.url,
                headers = solved + ("Referer" to postUrl)
            ).text
        } catch (e: Exception) {
            return false to "WebDL ${job.label}: solve=OK, refetch failed"
        }
        val ok = emitMagicHosts(job, html, subtitleCallback, callback)
        return ok to if (ok) null else
            "WebDL ${job.label}: solve=OK, hosts=0"
    }

    /** True when any collected link points at a known file host. */
    private fun hasHostHref(hrefs: List<String>): Boolean {
        val joined = hrefs.joinToString("\n")
        return gofileRegex.containsMatchIn(joined) ||
            skydropRegex.containsMatchIn(joined) ||
            transferRegex.containsMatchIn(joined) ||
            vikingRegex.containsMatchIn(joined) ||
            buzzRegex.containsMatchIn(joined) ||
            pixeldrainIdRegex.containsMatchIn(joined)
    }

    /**
     * Route 2 for resolveMagic: read the hub page's server
     * buttons out of the browser view that passed the check,
     * then resolve each host from that rendered page.
     */
    private suspend fun resolveMagicViaBrowser(
        job: DlJob,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hrefs = KmCf.fetchHrefs(job.url, 120_000L) { hs ->
            hasHostHref(hs)
        } ?: return false
        val joined = hrefs.joinToString("\n")
        var found = false
        gofileRegex.find(joined)?.value?.let { path ->
            try {
                if (loadExtractor("https://" + path, job.url, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                // Other hosts on the hub may still resolve.
            }
        }
        pixeldrainIdRegex.find(joined)?.groupValues?.get(1)?.let { id ->
            emitPixeldrain(job, id, callback)
            found = true
        }
        skydropRegex.find(joined)?.value?.let { u ->
            if (emitHostPageFile(job, "Skydrop", u, callback)) found = true
        }
        transferRegex.find(joined)?.value?.let { u ->
            if (emitHostPageFile(job, "TransferIt", u, callback)) found = true
        }
        for (rx in listOf(vikingRegex, buzzRegex)) {
            rx.find(joined)?.value?.let { u ->
                try {
                    if (loadExtractor(u, job.url, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (e: Exception) {
                    // Best effort only.
                }
            }
        }
        return found
    }

    /** True for URLs that name a video file directly. */
    private fun isFileHref(h: String): Boolean {
        val clean = h.substringBefore('?').substringBefore('#')
        return clean.endsWith(".mkv", true) || clean.endsWith(".mp4", true)
    }

    /**
     * Open a host landing page (Skydrop, Transfer.it) in the
     * browser view and let the page start its own download
     * the way it would for a user: Skydrop's page first asks
     * its server for a private link, jumps to a "Your video
     * is ready" page, and its Download button then starts
     * the file. The browser view captures the download URL
     * (it does not download the file itself), and the link
     * goes out with the browser session's cookies attached,
     * so the player presents the same session the link was
     * minted under.
     */
    private suspend fun emitHostPageFile(
        job: DlJob,
        hostLabel: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileUrl = KmCf.captureDownload(pageUrl, 120_000L)
            ?: return false
        val cookies = KmCf.cookiesFor(fileUrl)
        callback.invoke(
            newExtractorLink(
                name,
                job.label + " · " + hostLabel,
                fileUrl,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = pageUrl
                this.quality = job.quality
                if (cookies.isNotBlank()) {
                    this.headers = mapOf("Cookie" to cookies)
                }
            }
        )
        return true
    }

    /**
     * When every WebDL button failed, surface the recorded stage
     * as link names (the diagnostics pattern from XD) so a single
     * screenshot shows exactly where the WebDL route stopped.
     */
    private suspend fun emitDiag(
        diag: List<String>,
        data: String,
        callback: (ExtractorLink) -> Unit
    ) {
        for (line in diag) {
            try {
                callback(
                    newExtractorLink(
                        name, "KM diag: $line", data,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            } catch (e: Exception) {
                // diagnostics must never break the caller
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val jobs = app.get(data).document.downloadJobs()
        if (jobs.isEmpty()) return false
        val hasMagic = jobs.any { it.url.contains("magiclinks.lol") }
        if (hasMagic) KmCf.beginSession()
        try {
            val results = coroutineScope {
                jobs.map { job ->
                    async {
                        if (job.url.contains("magiclinks.lol")) {
                            resolveMagic(job, data, subtitleCallback, callback)
                        } else {
                            resolveHub(job, data, subtitleCallback, callback) to null
                        }
                    }
                }.awaitAll()
            }
            val magicOk = jobs.indices.any {
                jobs[it].url.contains("magiclinks.lol") && results[it].first
            }
            if (hasMagic && !magicOk) {
                val diag = results.mapNotNull { it.second }
                if (diag.isNotEmpty()) emitDiag(diag.take(6), data, callback)
            }
            return results.any { it.first }
        } finally {
            if (hasMagic) KmCf.endSession()
        }
    }
}
