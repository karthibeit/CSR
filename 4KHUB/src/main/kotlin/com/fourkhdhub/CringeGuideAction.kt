package com.fourkhdhub

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * "CringeMDB Guide" action (2026-11-03, Karthi's request).
 *
 * The Parents Guide block in the plot ends with the movie's
 * CringeMDB URL, but CloudStream renders the plot as plain text,
 * so the link is not tappable there and a plugin cannot change
 * that. This action is the tappable path: it appears in the
 * player's link menu (next to "Play in Kodi" from TML) for every
 * provider's links, and opens the movie's CringeMDB page in
 * Chrome, looked up from the tapped item's title via the same
 * strict matching as the in-plot guide (year-aware: slugs the
 * plot guide already matched are reused, and a title shared by
 * several movies with no known year is treated as ambiguous).
 * When no page matches, it opens the CringeMDB home page.
 *
 * Like KodiAction it is app-global and only shows when Chrome
 * is installed (standard on the phone; Android TV may not have
 * Chrome, where it simply stays hidden).
 */
class CringeGuideAction : OpenInAppAction(
    appName = txt("CringeMDB Guide"),
    packageName = "com.android.chrome",
    intentClass = "com.google.android.apps.chrome.Main"
) {
    override val oneSource = true

    override val sourceTypes: Set<ExtractorLinkType> = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.M3U8,
        ExtractorLinkType.DASH
    )

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        // headerName is the movie's title; name can be an episode or
        // link label. findSlug also reuses slugs (year-matched) that
        // the plot guide already resolved, and parses a "(2016)"
        // suffix, so same-title remakes open the right page.
        val title = video.headerName.trim().ifBlank {
            video.name?.trim().orEmpty()
        }
        val slug = if (title.isBlank()) {
            null
        } else {
            try {
                CringeGuide.findSlug(title)
            } catch (e: Exception) {
                null
            }
        }
        intent.data = Uri.parse(
            slug?.let { CringeGuide.pageUrl(it) } ?: "https://cringemdb.com"
        )
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        // Nothing to record when the user comes back from Chrome.
    }
}
