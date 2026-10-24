package com.tml

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TMLPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TMLProvider())
    }
}
