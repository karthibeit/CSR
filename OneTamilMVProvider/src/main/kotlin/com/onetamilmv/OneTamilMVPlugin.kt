package com.onetamilmv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OneTamilMVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OneTamilMVProvider())
    }
}
