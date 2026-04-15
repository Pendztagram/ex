package com.drakorid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DrakoridProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DrakoridProvider())
    }
}
