package com.tml

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * TML — CloudStream provider for the 1TamilMV forum.
 *
 * How the site actually works (checked 2026-10-23):
 *  - 1TamilMV is an Invision Community forum, not a streaming site.
 *    Every release is a forum topic, and the first post holds the links.
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
 *
 * v3 (2026-10-24): home/menus were very slow — every section borrowed
 * posters for 24 cards (one topic-page download each) and forum
 * sections wandered sub-forums (up to 20 extra fetches, 300 merged
 * topics), so a home load could exceed 300 page downloads. v3 caps
 * poster borrowing at the first 8 cards per section, gives each
 * poster fetch a 6s timeout, caches fetched documents for 60s (the
 * homepage is requested by two sections), and drops the sub-forum
 * wandering — sections show their own page of topics, with a single
 * one-level merge only for category pages that list no topics.
 *
 * v4 (2026-10-24): Web Series section moved to right after WEB-HD
 * (Karthi's requested order). Series fixes: titles that spell out
 * "Season 1" (no S01 token) now get episode lists too; when no link
 * label carries an episode marker, episodes fall back to the
 * title's EP range with all links as one batch; screenshot image
 * anchors (pixelbb) are no longer collected as download links.
 * Note on playback: series topics hold magnet/torrent links only,
 * so in-app episode playback depends on the app's torrent support
 * (Settings → Providers → Preferred media must include Torrent,
 * plus the one-time "Stream Torrent" acceptance popup).
 *
 * v5 (2026-10-24): Karthi's loading pattern — first load shows the
 * first 8 cards with thumbnails fast (as v3/v4), and ALL remaining
 * thumbnails load later instead of staying blank. CloudStream hands
 * each section to the UI exactly once, so a poster cannot be pushed
 * into an already-shown card; instead the remaining posters are
 * fetched in the background (max 6 at a time, de-duplicated) into a
 * session poster cache, and every card — first 8 or not — uses the
 * cache instantly when it already has the poster. Net effect: first
 * open is fast with 8 thumbnails per section, the rest fill the
 * cache in the background, and a refresh / reopening of TML shows
 * every thumbnail immediately.
 *
 * v6 (2026-10-28): batch-torrent episode selection. Series posts pack
 * several episodes into ONE torrent per quality, and CloudStream's
 * torrent playback otherwise starts the pack's FIRST file for every
 * episode (Karthi: tapping EP 90 played EP 89's video). CloudStream's
 * Torrent glue reads an "index=" value out of the link URL and hands
 * it to its embedded TorrServer, so v6: the episode data now carries
 * the tapped episode number; loadLinks downloads the batch's .torrent
 * file (session-cached), parses its file list (minimal bencode), finds
 * the episode's file index — counting EVERY file in the torrent,
 * .pad fillers included — and appends &index=N to that quality's
 * magnet and torrent links. Any failure falls back to the plain
 * links (the pre-v6 behaviour).
 */
class TMLProvider : MainAPI() {

    // Last widely reported domain. If it is parked/blocked the provider
    // walks fallbackDomains below until a live mirror answers.
    override var mainUrl = "https://www.1tamilmv.solar"
    override var name = "TML"
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
    // Homepage widgets first, then every Tamil sub-forum as its own
    // main-page section, so each menu shows all of its movies
    // instead of one latest topic.
    override val mainPage = mainPageOf(
        "https://www.1tamilmv.solar/#top-releases" to "Top Releases",
        "https://www.1tamilmv.solar/#recently-added" to "Recently Added",
        "https://www.1tamilmv.solar/index.php?/forums/forum/10-predvd-dvdscr-cam-tc/" to "PreDVD",
        "https://www.1tamilmv.solar/index.php?/forums/forum/11-web-hd-itunes-hd-bluray/" to "WEB-HD",
        "https://www.1tamilmv.solar/index.php?/forums/forum/19-web-series-tv-shows/" to "Web Series",
        "https://www.1tamilmv.solar/index.php?/forums/forum/12-hd-rips-dvd-rips-br-rips/" to "HD-Rips",
        "https://www.1tamilmv.solar/index.php?/forums/forum/13-dvd9-dvd5/" to "DVD9",
        "https://www.1tamilmv.solar/index.php?/forums/forum/14-hdtv-sdtv-hdtv-rips/" to "HDTV",
        "https://www.1tamilmv.solar/index.php?/forums/forum/15-mp3-songs/" to "MP3 Songs",
        "https://www.1tamilmv.solar/index.php?/forums/forum/16-video-songs/" to "Video Songs",
        "https://www.1tamilmv.solar/index.php?/forums/forum/17-hollywood-movies-in-multi-audios/" to "Hollywood Multi Audio",
        "https://www.1tamilmv.solar/index.php?/forums/forum/18-cinema-news-reviews-general-videos/" to "Cinema News",
        "https://www.1tamilmv.solar/index.php?/forums/forum/102-watch-tamil-movies-online/" to "Watch Online"
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

    // v3 speedup: the home screen asks for the same homepage twice
    // (Top Releases + Recently Added differ only by URL fragment)
    // and sections repeat across visits, so fetched documents are
    // cached briefly, keyed by URL without its fragment.
    private val docCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Document>>()

    private fun cachedDocument(url: String): Document? {
        val entry = docCache[url.substringBefore('#')] ?: return null
        return if (System.currentTimeMillis() - entry.first < 60_000L) {
            entry.second
        } else {
            null
        }
    }

    /** GET a page, walking the fallback domains if the current one is dead. */
    private suspend fun getDocument(url: String): Document {
        cachedDocument(url)?.let { return it }
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
                        docCache[url.substringBefore('#')] =
                            System.currentTimeMillis() to doc
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
    // Listing / search parsing (Invision topic lists)
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

    private data class ThreadItem(val title: String, val href: String)

    private fun Document.extractThreads(): List<ThreadItem> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ThreadItem>()

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
            results.add(ThreadItem(title, href))
        }
        return results
    }

    /**
     * Sub-forum links on a forum CATEGORY page (e.g. "Tamil Language").
     * A category page lists one "latest post" topic per sub-forum, so
     * parsing it directly yields only ~1 movie per submenu. The real
     * sub-forum rows live in div.cForumList — navigation links to the
     * other languages live outside it and must NOT be followed, or the
     * Tamil section would fill up with Telugu/Hindi/etc. topics.
     */
    private fun Document.extractSubForums(): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        this.select("div.cForumList h4 a[href*=/forums/forum/]").forEach { a ->
            val raw = a.absUrl("href").ifBlank { a.attr("href") }
            val href = fixUrl(raw)
            if (!Regex("/forums/forum/\\d+-").containsMatchIn(href)) return@forEach
            if (seen.add(href)) out.add(href)
        }
        return out
    }

    /**
     * Topics inside a homepage "banger" widget (TOP RELEASES THIS WEEK /
     * RECENTLY ADDED). Each entry is a pair of <strong> elements: the
     * first carries the title ("Baththa (2026) Tamil HD + HC-ESub -"),
     * the second carries the topic link (quality bracket) plus an
     * external [W] watch link (ignored — it is not a topic URL).
     * Some entries instead put the link inside the title <strong>.
     */
    private fun Element.extractBangerThreads(limit: Int): List<ThreadItem> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<ThreadItem>()
        var lastTitle = ""
        for (strong in this.select("strong")) {
            val anchor = strong.selectFirst("a[href*=/forums/topic/]")
            if (anchor == null) {
                val t = cleanThreadTitle(strong.text().trim())
                if (t.length >= 8 && !t.looksLikeTag()) lastTitle = t
                continue
            }
            val rawHref = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val href = fixUrl(rawHref)
            if (!topicUrlRegex.containsMatchIn(href)) continue
            if (href.contains("/page/") || href.contains("#")) continue
            if (!seen.add(href)) continue
            val ownText = cleanThreadTitle(strong.text().trim())
            val title = when {
                ownText.length >= 8 && !ownText.looksLikeTag() -> ownText
                lastTitle.length >= 8 -> lastTitle
                else -> anchor.topicTitle(href)
            }
            if (title.length < 4) continue
            results.add(ThreadItem(title, href))
            if (results.size >= limit) break
        }
        return results
    }

    // ------------------------------------------------------------------
    // Posters
    //
    // Listing pages (home / forums / search) carry NO poster images at
    // all — checked live 2026-10-23, they only have the site logo and
    // member avatars. The one poster lives inside each topic's first
    // post: it is the first content image (hosted on pixelbb, Twitter
    // image CDN, …), followed by vlcsnap screenshots and decoration
    // (torrborder / uTorrent). So home/search cards fetch the first
    // ~24 topic pages in parallel and borrow each topic's poster.
    // ------------------------------------------------------------------

    private fun Document.firstPost(): Element =
        this.selectFirst("div.cPost_contentWrap")
            ?: this.selectFirst("[data-role=commentContent]")
            ?: this.selectFirst("div.ipsType_richText")
            ?: this.body()

    /** First real poster image inside a post; null when none is usable. */
    private fun Element.posterFromPost(): String? {
        val skip = listOf(
            "logo", "avatar", "torrborder", "utorrent", "smilie", "emoji",
            "vlcsnap", "snapshot", "screenshot"
        )
        for (img in this.select("img")) {
            val src = img.absUrl("src")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
            if (src.isBlank() || src.startsWith("data:")) continue
            // Posters are not always .jpg files — Twitter's image CDN
            // serves them as media/<id>?format=jpg — so judge by name
            // and position, not by file extension.
            if (skip.any { it in src.lowercase() }) continue
            return fixUrlNull(src)
        }
        return null
    }

    // v5: session poster cache (topic URL -> poster URL). Once a
    // poster has been fetched — in the foreground, in the
    // background, or on a detail page — every later list shows it
    // instantly, including cards beyond the first-8 fast batch.
    private val posterCache =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    // Topics whose poster is currently being fetched in the
    // background, so overlapping sections never fetch one twice.
    private val posterInFlight =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Background poster fetching must outlive the getMainPage /
    // search call that started it, so it runs on its own scope.
    private val posterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Keep background traffic gentle: at most 6 poster pages at once.
    private val posterSemaphore = Semaphore(6)

    private suspend fun fetchPoster(url: String): String? {
        posterCache[url]?.let { return it }
        // v3: a single slow topic page must not stall its whole
        // section — give up on that poster after a few seconds.
        val poster = kotlinx.coroutines.withTimeoutOrNull(6_000L) {
            try {
                val doc = app.get(url).document
                fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
                    ?: doc.firstPost().posterFromPost()
            } catch (e: Exception) {
                null
            }
        }
        if (poster != null) posterCache[url] = poster
        return poster
    }

    /**
     * v5: fetch these topics' posters in the background and cache
     * them. The already-returned cards cannot be updated in place
     * (CloudStream delivers each section once), so these thumbnails
     * appear on the next refresh / reopening of TML — and any other
     * section or search listing the same topic shows them at once.
     */
    private fun warmPostersLater(items: List<ThreadItem>) {
        items.forEach { item ->
            if (posterCache.containsKey(item.href)) return@forEach
            if (!posterInFlight.add(item.href)) return@forEach
            posterScope.launch {
                try {
                    posterSemaphore.withPermit { fetchPoster(item.href) }
                } catch (e: Exception) {
                    // A failed background poster is retried on a
                    // later load — it must never break the UI call.
                } finally {
                    posterInFlight.remove(item.href)
                }
            }
        }
    }

    private suspend fun List<ThreadItem>.toSearchResponses(
        enrich: Int
    ): List<SearchResponse> {
        // First `enrich` cards fetch their poster before returning
        // (the fast first load). Every other card uses the poster
        // cache instantly when it has one, and the uncached rest are
        // warmed in the background below (v5).
        val posters: List<String?> = if (enrich > 0) {
            coroutineScope {
                mapIndexed { index, item ->
                    async {
                        if (index < enrich) fetchPoster(item.href)
                        else posterCache[item.href]
                    }
                }.awaitAll()
            }
        } else {
            map { posterCache[it.href] }
        }
        warmPostersLater(drop(enrich).filter { posterCache[it.href] == null })
        return mapIndexed { index, item ->
            val poster = posters[index]
            val headers = if (poster != null) mapOf("Referer" to mainUrl) else null
            if (isSeriesTitle(item.title)) {
                newTvSeriesSearchResponse(item.title, item.href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.posterHeaders = headers
                }
            } else {
                newMovieSearchResponse(item.title, item.href, TvType.Movie) {
                    this.posterUrl = poster
                    this.posterHeaders = headers
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Series detection
    //
    // Web-series topics are season packs: the post holds one torrent /
    // magnet per EPISODE BATCH ("EP (85-88)") per quality, newest batch
    // first, and each batch torrent contains the individual episode
    // files (verified by decoding a .torrent 2026-10-23: EP (85-88)
    // 1080p holds S01E85–E88 as separate .mkv files). Series topics get
    // a real episode list; movies keep the single Play button.
    // ------------------------------------------------------------------

    /** Episode span carried by a title or file label, e.g. EP (85-88). */
    private fun episodeRangeOf(text: String): Pair<Int, Int>? {
        Regex("""EP\s*\(?\s*(\d{1,4})\s*[-–]\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                val a = it.groupValues[1].toInt()
                val b = it.groupValues[2].toInt()
                if (a >= 1 && b >= a && b - a <= 500) return a to b
            }
        Regex("""S\d{1,2}\s*E(\d{1,4})\s*[-–]\s*E?(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                val a = it.groupValues[1].toInt()
                val b = it.groupValues[2].toInt()
                if (a >= 1 && b >= a && b - a <= 500) return a to b
            }
        Regex("""S\d{1,2}\s*E(\d{1,4})""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                val n = it.groupValues[1].toInt()
                if (n >= 1) return n to n
            }
        Regex("""\bEP\s*\(?\s*(\d{1,4})\s*\)?""", RegexOption.IGNORE_CASE)
            .find(text)?.let {
                val n = it.groupValues[1].toInt()
                if (n >= 1) return n to n
            }
        return null
    }

    private fun isSeriesTitle(title: String): Boolean {
        // v4: also accept "Season 1" style titles — the old check
        // required an "S01" token, so series whose titles spell out
        // "Season" were treated as movies and got no episode list.
        val hasSeasonMarker =
            Regex("""\bS\d{1,2}\b""", RegexOption.IGNORE_CASE).containsMatchIn(title) ||
                Regex("""\bSeason\s*\d{1,2}\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(title)
        if (!hasSeasonMarker) return false
        return episodeRangeOf(title) != null ||
            title.contains("Season", ignoreCase = true) ||
            title.contains("Episode", ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // Wikipedia summaries for the detail-page description.
    //
    // The detail page used to show the forum post's raw text (release
    // titles, resolutions, sizes, torrent/magnet labels). Karthi asked
    // for real movie details instead (2026-10-24). TMDB was tried
    // first: its website is Cloudflare-blocked for app requests and
    // its API needs a key, so he chose Wikipedia summaries (no key).
    // Matching is deliberately strict — the Wikipedia title must equal
    // the movie name and, when the year is known, the search snippet
    // must contain that year — so a wrong film is never shown; when
    // nothing matches, the caller keeps the post text.
    // ------------------------------------------------------------------

    private data class WikiSearchResponse(
        @JsonProperty("query") val query: WikiSearchQuery?
    )

    private data class WikiSearchQuery(
        @JsonProperty("search") val search: List<WikiSearchResult>?
    )

    private data class WikiSearchResult(
        @JsonProperty("title") val title: String?,
        @JsonProperty("snippet") val snippet: String?
    )

    private data class WikiSummary(
        @JsonProperty("extract") val extract: String?,
        @JsonProperty("type") val type: String?
    )

    /** "Gajab Raat (2026) TRUE WEB-DL" -> ("Gajab Raat", "2026"). */
    private fun cleanMovieTitle(title: String): Pair<String, String?> {
        val m = Regex("""^(.*?)\s*\(((?:19|20)\d{2})\)""").find(title)
        if (m != null) return m.groupValues[1].trim() to m.groupValues[2]
        return title.trim() to null
    }

    /** Lowercased, "&" -> "and", trailing "(...)" and punctuation stripped. */
    private fun normWikiTitle(s: String): String {
        var t = s.lowercase().replace("&", " and ")
        t = Regex("""\s*\([^)]*\)\s*$""").replace(t, "")
        t = Regex("""[^a-z0-9 ]""").replace(t, "")
        return t.trim().replace(Regex("""\s+"""), " ")
    }

    private suspend fun fetchWikiSummary(title: String): String? {
        return try {
            val (name, year) = cleanMovieTitle(title)
            if (name.length < 2) return null
            val query = URLEncoder.encode(
                if (year != null) "$name $year film" else "$name film", "UTF-8"
            )
            val search: WikiSearchResponse = parseJson(
                app.get(
                    "https://en.wikipedia.org/w/api.php?action=query&list=search" +
                        "&srsearch=$query&format=json&formatversion=2&srlimit=10"
                ).text
            )
            val exact = search.query?.search.orEmpty().filter { r ->
                val t = r.title.orEmpty()
                !t.startsWith("List of", ignoreCase = true) &&
                    normWikiTitle(t) == normWikiTitle(name)
            }
            val pick = if (year != null) {
                exact.firstOrNull { r ->
                    year in r.snippet.orEmpty().replace(Regex("<[^>]+>"), "")
                } ?: return null
            } else {
                exact.firstOrNull() ?: return null
            }
            val pageTitle = pick.title ?: return null
            val page = URLEncoder.encode(
                pageTitle.replace(" ", "_"), "UTF-8"
            ).replace("+", "%20")
            val summary: WikiSummary = parseJson(
                app.get("https://en.wikipedia.org/api/rest_v1/page/summary/$page").text
            )
            if (summary.type == "disambiguation") return null
            summary.extract?.takeIf { it.isNotBlank() }?.take(2000)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Rich details via Cinemeta (2026-10-30).
    //
    // Karthi asked for TMDB-grade detail pages. TMDB's own API
    // answers 401 without a personal key, and a key would have to
    // live inside this public plugin — so the details come from
    // Cinemeta instead: the free, keyless metadata service (records
    // keyed by IMDb id, carrying poster, backdrop, logo, IMDb
    // rating, runtime, genres, cast and a YouTube trailer). It
    // replaces Wikipedia as the first plot source; Wikipedia and
    // then the post text remain the fallbacks.
    //
    // Matching stays strict, like the Wikipedia flow, with two
    // extra guards learned in simulation: the record's own name
    // must equal the cleaned title (catalogue names can be aliases
    // of a differently-titled film), and when several records
    // verify ("Leo" 2023 is both the Tamil film and an animated
    // film) only an India-country record is trusted — this forum
    // posts Indian releases; a lone verified record is used as-is
    // so the Hollywood/English sections still enrich.
    // ------------------------------------------------------------------

    private data class CinemetaSearch(
        @JsonProperty("metas") val metas: List<CinemetaSearchItem>?
    )

    private data class CinemetaSearchItem(
        @JsonProperty("name") val name: String?,
        @JsonProperty("releaseInfo") val releaseInfo: String?,
        @JsonProperty("imdb_id") val imdbId: String?
    )

    private data class CinemetaMetaResponse(
        @JsonProperty("meta") val meta: CinemetaMeta?
    )

    private data class CinemetaMeta(
        @JsonProperty("name") val name: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("background") val background: String?,
        @JsonProperty("logo") val logo: String?,
        @JsonProperty("imdbRating") val imdbRating: String?,
        @JsonProperty("runtime") val runtime: String?,
        @JsonProperty("genre") val genre: List<String>?,
        @JsonProperty("cast") val cast: List<String>?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("released") val released: String?,
        @JsonProperty("trailers") val trailers: List<CinemetaTrailer>?
    )

    private data class CinemetaTrailer(
        @JsonProperty("source") val source: String?
    )

    private data class RichDetails(
        val plot: String?,
        val poster: String?,
        val background: String?,
        val logo: String?,
        val rating: Double?,
        val durationMinutes: Int?,
        val genres: List<String>,
        val cast: List<String>,
        val trailerUrl: String?,
        val year: Int?
    )

    // Session cache: reopening a title must not refetch its details.
    private val richCache =
        java.util.concurrent.ConcurrentHashMap<String, RichDetails>()

    private suspend fun fetchRichDetails(title: String, series: Boolean): RichDetails? {
        val (name, year) = cleanMovieTitle(title)
        if (name.length < 2) return null
        val cacheKey = (if (series) "series|" else "movie|") +
            normWikiTitle(name) + "|" + (year ?: "")
        richCache[cacheKey]?.let { return it }
        val details = try {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                fetchRichDetailsInner(name, year, series)
            }
        } catch (e: Exception) {
            null
        }
        if (details != null) richCache[cacheKey] = details
        return details
    }

    private suspend fun fetchRichDetailsInner(
        name: String, year: String?, series: Boolean
    ): RichDetails? {
        val type = if (series) "series" else "movie"
        val query = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val search: CinemetaSearch = parseJson(
            app.get(
                "https://v3-cinemeta.strem.io/catalog/$type/top/search=$query.json"
            ).text
        )
        val candidates = search.metas.orEmpty().filter { item ->
            item.imdbId != null &&
                normWikiTitle(item.name.orEmpty()) == normWikiTitle(name) &&
                (year == null || item.releaseInfo?.contains(year) == true)
        }.distinctBy { it.imdbId }.take(3)
        if (candidates.isEmpty()) return null
        val verified = mutableListOf<CinemetaMeta>()
        for (cand in candidates) {
            val meta = try {
                parseJson<CinemetaMetaResponse>(
                    app.get(
                        "https://v3-cinemeta.strem.io/meta/$type/${cand.imdbId}.json"
                    ).text
                ).meta
            } catch (e: Exception) {
                null
            } ?: continue
            if (normWikiTitle(meta.name.orEmpty()) != normWikiTitle(name)) continue
            val metaYear = Regex("(19|20)\\d{2}").find(meta.released.orEmpty())?.value
            if (year != null && metaYear != null && metaYear != year) continue
            verified.add(meta)
        }
        val pick = verified.firstOrNull {
            it.country?.contains("India", ignoreCase = true) == true
        } ?: verified.singleOrNull() ?: return null
        return RichDetails(
            plot = pick.description?.takeIf { it.isNotBlank() }?.take(2000),
            poster = pick.poster?.takeIf { it.isNotBlank() },
            background = pick.background?.takeIf { it.isNotBlank() },
            logo = pick.logo?.takeIf { it.isNotBlank() },
            rating = pick.imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 },
            durationMinutes = Regex("""\d+""").find(pick.runtime.orEmpty())
                ?.value?.toIntOrNull(),
            genres = pick.genre.orEmpty(),
            cast = pick.cast.orEmpty(),
            trailerUrl = pick.trailers.orEmpty().firstOrNull {
                !it.source.isNullOrBlank()
            }?.let { "https://www.youtube.com/watch?v=${it.source}" },
            year = Regex("(19|20)\\d{2}").find(pick.released.orEmpty())
                ?.value?.toIntOrNull() ?: year?.toIntOrNull()
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Homepage / What's New are not reliably paginated — page 1 only.
        if (page > 1) return newHomePageResponse(request, emptyList(), hasNext = false)
        val document = getDocument(fixUrl(request.data))
        // Homepage widgets: Top Releases / Recently Added.
        if (request.name == "Top Releases" || request.name == "Recently Added") {
            val key = if (request.name == "Top Releases") "TOP RELEASES" else "RECENTLY ADDED"
            val container = document.select("div.banger-container").firstOrNull {
                it.selectFirst(".banger-header")?.text()?.contains(key, ignoreCase = true) == true
            }
            val widgetItems = container?.extractBangerThreads(limit = 50)
                ?.toSearchResponses(enrich = 8)
            if (!widgetItems.isNullOrEmpty()) {
                return newHomePageResponse(request, widgetItems, hasNext = false)
            }
            // Fall through to the generic parse if the widget is missing.
        }
        // v3 speedup: a section shows its own page of topics. The old
        // code descended into every sub-forum (up to 20 extra page
        // fetches, up to 300 merged topics) and then borrowed posters
        // for 24 cards per section — with 13 sections the home screen
        // needed 300+ page downloads. Now the first 8 cards borrow
        // posters before the section returns (fast first load), and
        // v5 warms every remaining poster's cache in the background
        // so all thumbnails show on the next load.
        var threads = document.extractThreads()
        if (threads.isEmpty()) {
            // Category-style page with no topics of its own: take a
            // single page from each sub-forum — no deep wandering.
            val seenHrefs = mutableSetOf<String>()
            val merged = mutableListOf<ThreadItem>()
            for (forumUrl in document.extractSubForums().take(12)) {
                try {
                    val subDoc = getDocument(forumUrl)
                    for (t in subDoc.extractThreads()) {
                        if (seenHrefs.add(t.href)) merged.add(t)
                    }
                } catch (e: Exception) {
                    continue
                }
                if (merged.size >= 100) break
            }
            threads = merged
        }
        return newHomePageResponse(
            request, threads.toSearchResponses(enrich = 8), hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // 1) Invision guest search (verified working for guests 2026-10-23).
        try {
            val doc = getDocument("$mainUrl/index.php?/search/&q=$encoded")
            val results = doc.extractThreads()
            if (results.isNotEmpty()) return results.toSearchResponses(enrich = 20)
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
            val title = item.title.lowercase()
            tokens.all { it in title }
        }.toSearchResponses(enrich = 20)
    }

    // ------------------------------------------------------------------
    // Detail page (thread first post)
    // ------------------------------------------------------------------

    data class OneTamilMVLink(
        @JsonProperty("url") val url: String,
        @JsonProperty("label") val label: String
    )

    /**
     * v6: series episode payload. Before v6 every episode of a batch
     * carried the same bare link list, so loadLinks could not know
     * WHICH episode was tapped — the episode number rides along now.
     */
    data class EpisodeLinkData(
        @JsonProperty("ep") val ep: Int?,
        @JsonProperty("links") val links: List<OneTamilMVLink>
    )

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1.p-title-value")?.text()
            ?: document.title()
        val title = cleanThreadTitle(rawTitle)

        // First post = the release post holding poster + links.
        val post = document.firstPost()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        ) ?: post.posterFromPost()
        // v5: a poster fetched for the detail page also fills the
        // list cache, so this card shows its thumbnail at once.
        if (poster != null) posterCache[url] = poster

        // Rich details first (plot, rating, cast, backdrop, trailer);
        // Wikipedia stays as the plot fallback, then the post text.
        val rich = fetchRichDetails(title, isSeriesTitle(title))
        if (rich?.poster != null) posterCache[url] = rich.poster
        val plot = rich?.plot
            ?: fetchWikiSummary(title)
            ?: post.text().take(2000).ifBlank { null }
        val year = rich?.year
            ?: Regex("(19|20)\\d{2}").find(title)?.value?.toIntOrNull()

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
            // v4: screenshot image anchors (pixelbb /image/ pages
            // wrapping a vlcsnap still) are not downloads — without
            // this they were collected as "external host" links and
            // polluted the playable link list.
            if (label == href && a.selectFirst("img") != null &&
                (href.contains("pixelbb", ignoreCase = true) ||
                    href.contains("/image/", ignoreCase = true)) &&
                !href.startsWith("magnet:") && !href.contains("attachment.php")
            ) {
                return@forEach
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

        // Web series: group the links by the episode batch in their file
        // labels and hand CloudStream a real episode list (ascending).
        // Every episode of a batch points at that batch's links — the
        // batch torrent/magnet contains the individual episode files.
        val season = Regex("""\bS(\d{1,2})\b""", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Season\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
        if (isSeriesTitle(title)) {
            val batches = LinkedHashMap<Pair<Int, Int>, MutableList<OneTamilMVLink>>()
            links.values.forEach { link ->
                val range = episodeRangeOf(link.label) ?: return@forEach
                batches.getOrPut(range) { mutableListOf() }.add(link)
            }
            if (batches.isEmpty()) {
                // v4: no link label carries an episode marker (some
                // posts label batches only in the topic title) —
                // fall back to the title's range with all links, so
                // the episodes still get data instead of the series
                // silently degrading to a movie with no episodes.
                episodeRangeOf(title)?.let { range ->
                    if (links.isNotEmpty()) {
                        batches[range] = links.values.toMutableList()
                    }
                }
            }
            val episodes = mutableListOf<Episode>()
            batches.forEach { (range, batchLinks) ->
                for (n in range.first..range.second) {
                    // v6: each episode's data carries its own number so
                    // loadLinks can select that episode's file inside
                    // the batch torrent (see the v6 note at the top).
                    val data = EpisodeLinkData(n, batchLinks).toJson()
                    episodes.add(newEpisode(data) {
                        this.name = "Episode $n"
                        this.season = season
                        this.episode = n
                        if (range.first != range.second) {
                            this.description =
                                "Batch EP ${range.first}–${range.second} — one " +
                                    "torrent/magnet covers these episodes; TML " +
                                    "sets the torrent file index so this " +
                                    "episode plays directly."
                        }
                    })
                }
            }
            if (episodes.isNotEmpty()) {
                episodes.sortBy { it.episode ?: 0 }
                return newTvSeriesLoadResponse(
                    title, url, TvType.TvSeries, episodes
                ) {
                    this.posterUrl = rich?.poster ?: poster
                    this.backgroundPosterUrl = rich?.background
                    this.logoUrl = rich?.logo
                    this.plot = plot
                    this.year = year
                    rich?.rating?.let { this.score = Score.from10(it) }
                    rich?.durationMinutes?.let { this.duration = it }
                    rich?.genres?.takeIf { it.isNotEmpty() }?.let { this.tags = it }
                    rich?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                        this.actors = cast.take(15).map { ActorData(Actor(it)) }
                    }
                    rich?.trailerUrl?.let {
                        this.trailers.add(TrailerData(it, null, false))
                    }
                }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, links.values.toList().toJson()) {
            this.posterUrl = rich?.poster ?: poster
            this.backgroundPosterUrl = rich?.background
            this.logoUrl = rich?.logo
            this.plot = plot
            this.year = year
            rich?.rating?.let { this.score = Score.from10(it) }
            rich?.durationMinutes?.let { this.duration = it }
            rich?.genres?.takeIf { it.isNotEmpty() }?.let { this.tags = it }
            rich?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                this.actors = cast.take(15).map { ActorData(Actor(it)) }
            }
            rich?.trailerUrl?.let {
                this.trailers.add(TrailerData(it, null, false))
            }
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

    // ------------------------------------------------------------------
    // v6: batch-torrent file selection
    // ------------------------------------------------------------------

    private fun isTorrentFileLink(url: String): Boolean =
        url.endsWith(".torrent") || url.contains("/attachments/") ||
            url.contains("attachment.php")

    private fun appendIndexParam(url: String, index: Int): String =
        url + (if (url.contains("?")) "&" else "?") + "index=" + index

    /** Session cache of parsed .torrent file lists, keyed by torrent URL. */
    private val torrentFileCache = ConcurrentHashMap<String, List<String>>()

    private suspend fun torrentFileList(url: String): List<String>? {
        torrentFileCache[url]?.let { return it }
        val bytes = try {
            withContext(Dispatchers.IO) {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                )
                conn.setRequestProperty("Referer", mainUrl)
                conn.inputStream.use { it.readBytes() }
            }
        } catch (e: Exception) {
            return null
        }
        val files = parseTorrentFileList(bytes) ?: return null
        torrentFileCache[url] = files
        return files
    }

    /** Minimal bencode walk of a .torrent: returns the info file paths in metainfo order. */
    private fun parseTorrentFileList(bytes: ByteArray): List<String>? {
        return try {
            val root = BencodeReader(bytes).readValue() as? Map<*, *> ?: return null
            val info = root["info"] as? Map<*, *> ?: return null
            val files = info["files"] as? List<*>
            if (files != null) {
                files.map { entry ->
                    val parts = (entry as? Map<*, *>)?.get("path") as? List<*>
                    parts?.joinToString("/") { part ->
                        (part as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
                    } ?: ""
                }
            } else {
                val singleName = (info["name"] as? ByteArray)
                    ?.toString(Charsets.UTF_8) ?: return null
                listOf(singleName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private class BencodeReader(private val bytes: ByteArray) {
        private var pos = 0

        fun readValue(): Any? {
            if (pos >= bytes.size) return null
            return when (bytes[pos].toInt().toChar()) {
                'i' -> {
                    val end = indexOf('e')
                    val value = String(bytes, pos + 1, end - pos - 1, Charsets.US_ASCII)
                        .toLongOrNull() ?: 0L
                    pos = end + 1
                    value
                }
                'l' -> {
                    pos++
                    val list = mutableListOf<Any?>()
                    while (pos < bytes.size && bytes[pos].toInt().toChar() != 'e') {
                        list.add(readValue())
                    }
                    pos++
                    list
                }
                'd' -> {
                    pos++
                    val map = LinkedHashMap<String, Any?>()
                    while (pos < bytes.size && bytes[pos].toInt().toChar() != 'e') {
                        val key = (readValue() as? ByteArray)
                            ?.toString(Charsets.UTF_8) ?: break
                        map[key] = readValue()
                    }
                    pos++
                    map
                }
                else -> {
                    val colon = indexOf(':')
                    val length = String(bytes, pos, colon - pos, Charsets.US_ASCII)
                        .toIntOrNull() ?: 0
                    val start = colon + 1
                    val end = minOf(start + length, bytes.size)
                    pos = end
                    bytes.copyOfRange(start, end)
                }
            }
        }

        private fun indexOf(c: Char): Int {
            var i = pos
            while (i < bytes.size && bytes[i].toInt().toChar() != c) i++
            return i
        }
    }

    /**
     * Index of the episode's own file inside a batch torrent. The index
     * counts EVERY entry of the file list (.pad fillers included) —
     * that is the numbering CloudStream's TorrServer uses. Returns null
     * when no video file name carries the episode number.
     */
    private fun episodeFileIndex(files: List<String>, episode: Int): Int? {
        val patterns = listOf(
            Regex("""S\d{1,2}E0*${episode}(?!\d)""", RegexOption.IGNORE_CASE),
            Regex("""\bEP\s*\(?\s*0*${episode}\s*\)?(?!\d)""", RegexOption.IGNORE_CASE),
            Regex("""\bEpisode\s*0*${episode}(?!\d)""", RegexOption.IGNORE_CASE)
        )
        val videoFiles = files.withIndex().filter { (_, path) ->
            !path.contains(".pad") &&
                Regex("""\.(mkv|mp4|avi|m4v|ts)$""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(path)
        }
        for (pattern in patterns) {
            videoFiles.firstOrNull { (_, path) -> pattern.containsMatchIn(path) }
                ?.let { return it.index }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v6 payloads are {"ep": n, "links": [...]} objects; movies and
        // pre-v6 payloads are the bare link list. Both are accepted.
        val trimmed = data.trim()
        val episodeNumber: Int?
        val links: List<OneTamilMVLink>
        if (trimmed.startsWith("{")) {
            val wrapper = try {
                parseJson<EpisodeLinkData>(trimmed)
            } catch (e: Exception) {
                null
            }
            episodeNumber = wrapper?.ep
            links = wrapper?.links ?: emptyList()
        } else {
            episodeNumber = null
            links = try {
                parseJson(data)
            } catch (e: Exception) {
                emptyList()
            }
        }

        // v6: work out the torrent file index for the tapped episode,
        // per quality, from the batch's own .torrent files. Magnets are
        // paired with the same-quality torrent of the batch. Anything
        // that cannot be resolved keeps its plain URL (old behaviour).
        val indexedUrls = HashMap<String, String>()
        if (episodeNumber != null) {
            val indexByQuality = HashMap<Int, Int>()
            links.filter { isTorrentFileLink(it.url) }.forEach { torrentLink ->
                val files = torrentFileList(torrentLink.url) ?: return@forEach
                val index = episodeFileIndex(files, episodeNumber) ?: return@forEach
                indexedUrls[torrentLink.url] = appendIndexParam(torrentLink.url, index)
                indexByQuality.putIfAbsent(parseQuality(torrentLink.label), index)
            }
            links.filter { it.url.startsWith("magnet:") }.forEach { magnetLink ->
                val index = indexByQuality[parseQuality(magnetLink.label)]
                    ?: return@forEach
                indexedUrls[magnetLink.url] = appendIndexParam(magnetLink.url, index)
            }
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
                        newExtractorLink(
                            name, "Magnet — ${link.label}",
                            indexedUrls[link.url] ?: link.url,
                            ExtractorLinkType.MAGNET
                        ) {
                            this.quality = quality
                        }
                    )
                    found = true
                }
                link.url.endsWith(".torrent") || link.url.contains("/attachments/") ||
                    link.url.contains("attachment.php") -> {
                    callback.invoke(
                        newExtractorLink(
                            name, "Torrent — ${link.label}",
                            indexedUrls[link.url] ?: link.url,
                            ExtractorLinkType.TORRENT
                        ) {
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
