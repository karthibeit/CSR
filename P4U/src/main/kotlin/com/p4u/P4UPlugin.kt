package com.p4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class P4UPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(P4UProvider())
    }
}
