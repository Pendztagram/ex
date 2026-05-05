package com.nimegami

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NimegamiProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NimegamiProvider())
        registerExtractorAPI(NimegamiHalahgan())
    }
}
