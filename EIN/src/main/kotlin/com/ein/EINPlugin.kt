package com.ein

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EINPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EINProvider())
    }
}
