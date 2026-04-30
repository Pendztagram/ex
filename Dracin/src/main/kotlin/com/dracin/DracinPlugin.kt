package com.dracin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DracinPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(Dracin())
    }
}
