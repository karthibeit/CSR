package com.fourkhdhub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FourKHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FourKHubProvider())
        // App-global "CringeMDB Guide" option in the player's link
        // menu: opens the movie's CringeMDB page in Chrome (the
        // in-plot link is plain text and cannot be tapped).
        registerVideoClickAction(CringeGuideAction())
    }
}
