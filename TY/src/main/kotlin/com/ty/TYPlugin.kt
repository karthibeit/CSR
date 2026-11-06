package com.ty

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TYPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TYProvider())
    }
}
