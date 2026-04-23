package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Jeniusplay2 : ExtractorApi() {
    override val name = "Jeniusplay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true
    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRef = if (url.contains("/video/")) url.substringBefore("#") else "$mainUrl/"
        val document = app.get(
            url,
            referer = referer ?: pageRef,
            interceptor = cloudflareInterceptor
        ).document
        val hash = url.split("/").last().substringAfter("data=")

        val response = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf("hash" to hash, "r" to pageRef),
            referer = pageRef,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to pageRef
            ),
            interceptor = cloudflareInterceptor
        ).parsed<ResponseSource>()
            ?: app.post(
                url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                data = mapOf("hash" to hash, "r" to pageRef),
                referer = pageRef,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                interceptor = cloudflareInterceptor
            ).parsed<ResponseSource>()
            ?: return

        val streamUrl = (response.securedLink ?: response.videoSource)
            ?.replace(".txt", ".m3u8")
            ?.takeIf { it.isNotBlank() }
            ?: return
        val streamHeaders = mapOf(
            "Origin" to mainUrl,
            "Referer" to pageRef,
            "Accept" to "*/*"
        )

        if (streamUrl.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(
                name,
                streamUrl,
                pageRef,
                headers = streamHeaders
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    name = "Jenius AUTO",
                    source = this.name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageRef
                    this.headers = streamHeaders
                }
            )
        }


        document.select("script").map { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                val subData =
                    getAndUnpack(script.data()).substringAfter("\"tracks\":[").substringBefore("],")
                tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            getLanguage(subtitle.label ?: ""),
                            subtitle.file
                        )
                    )
                }
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str
                .contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }

    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String?,
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )
}
