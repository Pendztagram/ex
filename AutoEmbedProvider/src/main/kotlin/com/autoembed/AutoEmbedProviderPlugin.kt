package com.autoembed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AutoEmbedProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AutoEmbedProvider())
    }
}
