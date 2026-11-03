package com.tml

import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * CringeMDB Parents Guide — shared by TMLProvider (which
 * renders the guide into the plot) and CringeGuideAction in the 4KHUB plugin (which
 * opens the movie's CringeMDB page in the browser).
 *
 * Data: cringemdb.com, a keyless community database. Its search
 * endpoint returns title candidates with page slugs; a movie page
 * carries a SAFE / NOT SAFE verdict plus one card per flagged
 * scene (start/end times, category, intensity, description).
 * Matching is strict — cleaned title equal, year equal when
 * known; a one-year-off candidate is accepted only when it is
 * the unique exact-title candidate (festival vs release years:
 * Obsession is 2025 on the hub, 2026 on CringeMDB) — so a wrong
 * movie's scenes are never attached. Coverage is mostly
 * English/Hollywood titles; Tamil originals usually have no page.
 *
 * Network: the guide is fetched with a dedicated HTTP client
 * whose resolver falls back to DNS-over-HTTPS (1.1.1.1, then
 * 8.8.8.8, both reached by IP literal) and finally to
 * CringeMDB's Cloudflare anycast addresses, because the phone's
 * system DNS cannot resolve cringemdb.com on Karthi's network
 * (proven on-device by the v9/v11 diagnostic builds:
 * UnknownHostException before any request was sent). TLS still
 * verifies the real hostname throughout.
 *
 * Only successful guides are cached (per session); failures and
 * no-match results are retried on the next open.
 */
object CringeGuide {

    data class Guide(val slug: String, val block: String)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

    private val blockCache = mutableMapOf<String, Guide>()
    private val slugByTitle = mutableMapOf<String, String>()

    // ------------------------------------------------------------ HTTP

    // Short-timeout client used only to reach the DoH resolvers
    // by IP literal, so no local DNS is involved.
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun dohResolve(hostname: String): List<InetAddress> {
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$hostname&type=A",
            "https://8.8.8.8/resolve?name=$hostname&type=A"
        )
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url)
                    .header("accept", "application/dns-json").build()
                val text = dohClient.newCall(req).execute()
                    .use { it.body?.string() }
                val ips = Regex(""""data"\s*:\s*"(\d+\.\d+\.\d+\.\d+)"""")
                    .findAll(text ?: "").map {
                        InetAddress.getByName(it.groupValues[1])
                    }.toList()
                if (ips.isNotEmpty()) return ips
            } catch (e: Exception) {
                // Fall through to the next resolver.
            }
        }
        // Last resort: CringeMDB sits behind Cloudflare; these
        // anycast addresses were its A records when checked from
        // a working network.
        return listOf("104.21.52.25", "172.67.194.80").map {
            InetAddress.getByName(it)
        }
    }

    // System DNS first; DNS-over-HTTPS when the system resolver
    // cannot resolve the host (the on-device cringemdb.com case).
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                dohResolve(hostname)
            }
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent).build()
        return client.newCall(req).execute().use { resp ->
            resp.body?.string()
                ?: throw IllegalStateException("Empty body for $url")
        }
    }

    // ------------------------------------------------------------ matching

    fun cleanTitle(t: String): String =
        t.lowercase().replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ").trim()

    private fun unescapeJsonString(s: String): String =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")

    /** The CringeMDB slug for a title, or null. Throws on network failure. */
    private fun matchSlug(title: String, year: Int?): String? {
        val searchText = get(
            "https://cringemdb.com/search?term=" +
                URLEncoder.encode(title, "UTF-8")
        )
        val pairRegex = Regex(
            """"movie"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"slug"\s*:\s*"([^"]+)""""
        )
        val rawPairs = pairRegex.findAll(searchText).map {
            unescapeJsonString(it.groupValues[1]) to it.groupValues[2]
        }.toList()
        if (rawPairs.isEmpty()) return null
        val cleaned = cleanTitle(title)
        val candidates = rawPairs.mapNotNull { (movie, slug) ->
            val m = Regex("""^(.*?)\s*\((\d{4})""").find(movie)
                ?: return@mapNotNull null
            Triple(cleanTitle(m.groupValues[1]),
                m.groupValues[2].toIntOrNull(), slug)
        }
        var slug = candidates.firstOrNull {
            it.first == cleaned && (year == null || it.second == year)
        }?.third
        if (slug == null && year != null) {
            // Festival-year vs release-year dating often differs
            // by one; accept a one-year-off candidate only when
            // it is the single exact-title candidate there.
            val near = candidates.filter {
                it.first == cleaned && it.second != null &&
                    kotlin.math.abs(it.second!! - year) == 1
            }.map { it.third }.filter { it.isNotBlank() }.distinct()
            if (near.size == 1) slug = near[0]
        }
        return slug?.takeIf { it.isNotBlank() }
    }

    /** Render the guide block for a slug, or null when the page has no verdict. */
    private fun renderBlock(slug: String): String? {
        val doc = Jsoup.parse(get("https://cringemdb.com/movie/$slug"))
        val verdictText = doc.selectFirst("p.help-us")?.text().orEmpty()
        val notSafe = verdictText.contains("NOT SAFE")
        val scenes = doc.select("article.sg-scene")
        if (!notSafe && scenes.isEmpty() && !verdictText.contains("SAFE")) {
            return null
        }
        val sb = StringBuilder()
        if (notSafe) {
            sb.append("⚠ PARENTS GUIDE (CringeMDB) — NOT SAFE to watch with kids")
        } else {
            sb.append("✔ PARENTS GUIDE (CringeMDB) — No flagged scenes reported")
        }
        for (scene in scenes) {
            val start = scene.selectFirst(".sg-time strong")?.text()?.trim().orEmpty()
            val end = scene.selectFirst(".sg-time span")?.text()?.trim()
                ?.removePrefix("to ")?.trim().orEmpty()
            val h3 = scene.selectFirst(".sg-body h3")
            val intensity = h3?.selectFirst("small")?.text()?.trim().orEmpty()
            val category = h3?.ownText()?.trim().orEmpty()
            val desc = scene.selectFirst(".sg-body p")?.text()?.trim().orEmpty()
            val range = if (start.isNotBlank() && end.isNotBlank()) {
                "$start–$end"
            } else {
                start
            }
            sb.append("\n• ").append(range)
            if (category.isNotBlank()) sb.append(" · ").append(category)
            if (intensity.isNotBlank()) sb.append(" (").append(intensity).append(")")
            if (desc.isNotBlank()) sb.append(" — ").append(desc)
        }
        // The full CringeMDB page, for the complete details (the
        // tappable path is CringeGuideAction in the player menu;
        // this line stays as the copyable reference).
        sb.append("\nFull details: https://cringemdb.com/movie/").append(slug)
        return sb.toString()
    }

    // ------------------------------------------------------------ entry points

    /** Full guide (block + slug) for a title/year, or null. */
    fun guideFor(title: String, year: Int?): Guide? {
        val key = "$title|$year"
        blockCache[key]?.let { return it }
        val guide = try {
            val slug = matchSlug(title, year) ?: return null
            val block = renderBlock(slug) ?: return null
            Guide(slug, block)
        } catch (e: Exception) {
            return null // network failure — not cached
        }
        blockCache[key] = guide
        slugByTitle[cleanTitle(title)] = guide.slug
        return guide
    }

    /** Just the slug for a title (used by the browser action). */
    fun findSlug(title: String): String? {
        val cleaned = cleanTitle(title)
        slugByTitle[cleaned]?.let { return it }
        val slug = try {
            matchSlug(title, null)
        } catch (e: Exception) {
            null
        } ?: return null
        slugByTitle[cleaned] = slug
        return slug
    }

    fun pageUrl(slug: String): String = "https://cringemdb.com/movie/$slug"
}
