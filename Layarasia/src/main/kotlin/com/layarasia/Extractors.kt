package com.layarasia

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType


class Nunaupns : VidStack() {
    override var name = "Nunaupns"
    override var mainUrl = "https://nuna.upns.pro"
    override var requiresReferer = true
}

class Nunap2p : VidStack() {
    override var name = "Nunap2p"
    override var mainUrl = "https://nuna.p2pstream.vip"
    override var requiresReferer = true
}

class Nunastrp : VidStack() {
    override var name = "Nunastrp"
    override var mainUrl = "https://nuna.strp2p.site"
    override var requiresReferer = true
}

class Nunaxyz : VidStack() {
    override var name = "Nunaxyz"
    override var mainUrl = "https://nuna.upns.xyz"
    override var requiresReferer = true
}

class Smoothpre: VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val qualityText = app.get(url).documentLarge.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)
            val response = app.get("$url/download", referer = url, allowRedirects = false)
            val redirectUrl = response.headers["hx-redirect"] ?: ""

            if (redirectUrl.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        "BuzzServer",
                        "BuzzServer",
                        redirectUrl,
                    ) {
                        this.quality = quality
                    }
                )
            } else {
                Log.w("BuzzServer", "No redirect URL found in headers.")
            }
        } catch (e: Exception) {
            Log.e("BuzzServer", "Exception occurred: ${e.message}")
        }
    }
}

open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val page = app.get(url, referer = referer ?: "$mainUrl/")
        val embedReferer = page.url

        val headers = mapOf(
            "Referer" to embedReferer,
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*"
        )

        var masterUrl = page.document.selectFirst("#video_player[data-hash]")?.attr("data-hash")
            ?.trim()
            .orEmpty()

        if (masterUrl.isBlank()) {
            val playerScript = page.document.select("script").joinToString("\n") { it.data() }
            masterUrl = Regex("""var\s+urlPlay\s*=\s*['"]([^'"]+)['"]""")
                .find(playerScript)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .trim()
        }

        if (masterUrl.isBlank()) return null

        masterUrl = when {
            masterUrl.startsWith("//") -> "https:$masterUrl"
            masterUrl.startsWith("/") -> "$mainUrl$masterUrl"
            else -> masterUrl
        }

        val generated = generateM3u8(
            source = name,
            name = name,
            streamUrl = masterUrl,
            referer = embedReferer,
            headers = headers
        ).distinctBy { it.url }

        if (generated.isNotEmpty()) return generated

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedReferer
                this.headers = headers
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class Minochinos : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://minochinos.com"
}

open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

 override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
	        "User-Agent" to USER_AGENT,
        )
        
        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if(result.contains("var links")){
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        // m3u8 urls could be prefixed by 'file:', 'hls2:' or 'hls4:', so we just match ':'
        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
		return when {
			url.contains("/d/") -> url.replace("/d/", "/v/")
			url.contains("/download/") -> url.replace("/download/", "/v/")
			url.contains("/file/") -> url.replace("/file/", "/v/")
			else -> url.replace("/f/", "/v/")
		}
	}

}

