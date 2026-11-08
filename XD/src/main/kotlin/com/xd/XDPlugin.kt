package com.xd

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XDProvider())
    }
}
