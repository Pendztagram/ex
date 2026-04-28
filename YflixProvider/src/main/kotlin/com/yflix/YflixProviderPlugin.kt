package com.yflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YflixProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YflixProvider())
    }
}
