package com.tml

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
 * "Play in Kodi" action (2026-10-30, Karthi's request).
 *
 * CloudStream's built-in external-player list (Play in VLC, MPV,
 * Next Player, ...) has no Kodi entry — but plugins may register
 * their own video-click actions, which is what this does. Once
 * TML is installed, the action appears for every provider's links
 * (the list is app-global), and it can also be picked as the
 * default player under Settings → Player.
 *
 * How Kodi is launched: the standard Android player intent,
 * confirmed against Kodi's own forum — ACTION_VIEW aimed at
 * component org.xbmc.kodi/.Splash with data = the stream URL and
 * a video mime type makes Kodi play the file. Kodi accepts no
 * subtitle/header extras over this intent, so this action is
 * one-source (you pick the quality/link first, like VLC/MPV),
 * and it only offers itself for direct stream types — Kodi
 * cannot do anything with a magnet/torrent link this way.
 */
class KodiAction : OpenInAppAction(
    appName = txt("Kodi"),
    packageName = "org.xbmc.kodi",
    intentClass = "org.xbmc.kodi.Splash"
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
        val link = index?.let { result.links.getOrNull(it) }
            ?: result.links.firstOrNull()
            ?: return
        intent.setDataAndType(Uri.parse(link.url), "video/*")
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        // Kodi returns no playback position, so there is no
        // resume point to record when the user comes back.
    }
}
