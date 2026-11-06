package com.ctv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CTVProvider())
    }
}
