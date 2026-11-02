package com.tdl

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TDLPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TDLProvider())
    }
}
