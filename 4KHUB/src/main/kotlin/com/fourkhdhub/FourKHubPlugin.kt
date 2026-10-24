package com.fourkhdhub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FourKHubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FourKHubProvider())
    }
}
