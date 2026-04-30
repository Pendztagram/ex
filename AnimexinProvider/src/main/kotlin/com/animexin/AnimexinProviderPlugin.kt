package com.animexin

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimexinProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimexinProvider())
    }
}
