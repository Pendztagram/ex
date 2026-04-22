
package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SoraStreamPlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("SoraStream", Context.MODE_PRIVATE)
        // All providers should be added in this manner. Please don't edit the providers list directly.
        SoraStream.context = context
        registerMainAPI(SoraStream(sharedPref))
        registerExtractorAPI(Jeniusplay2())
    }
}
