package com.bstation

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BstationProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BstationProvider())
    }
}
