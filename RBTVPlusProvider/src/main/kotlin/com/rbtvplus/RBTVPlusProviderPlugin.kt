package com.rbtvplus

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RBTVPlusProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RBTVPlusProvider())
    }
}
