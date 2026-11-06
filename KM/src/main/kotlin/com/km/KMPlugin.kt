package com.km

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KMPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KMProvider())
    }
}
