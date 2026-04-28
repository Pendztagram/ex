package com.cinemacityprovider

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemacityProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinemacityProvider())
    }
}
