package com.netmirror

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetMirrorProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NetMirrorProvider())
    }
}
