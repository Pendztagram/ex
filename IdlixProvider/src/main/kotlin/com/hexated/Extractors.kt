package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

open class Jeniusplay : ExtractorApi() {
    override val name = "Jeniusplay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true
    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = getBaseUrl(url)
        val pageRef = if (url.contains("/video/")) url.substringBefore("#") else "$baseUrl/"
        val document = app.get(
            url,
            referer = referer ?: "$baseUrl/",
            interceptor = cloudflareInterceptor
        ).document
        val hash = url.split("/").last().substringAfter("data=").substringBefore("?")

        val videoApiUrl = "$baseUrl/player/index.php?data=$hash&do=getVideo"
        val videoApiHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to baseUrl,
            "Referer" to pageRef
        )

        val videoText = app.post(
            url = videoApiUrl,
            data = mapOf("hash" to hash, "r" to pageRef),
            referer = pageRef,
            headers = videoApiHeaders,
            interceptor = cloudflareInterceptor
        ).text

        val source = tryParseJson<ResponseSource>(videoText)
            ?: tryParseJson<ResponseWrapper>(videoText)?.data

        val m3uLink = source?.securedLink?.takeIf { it.isNotBlank() }
            ?: source?.videoSource?.takeIf { it.isNotBlank() }
            ?: return

        callback.invoke(
            newExtractorLink(
                name = "Jenius AUTO",
                source = this.name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = pageRef
                this.headers = mapOf(
                    "Origin" to baseUrl,
                    "Referer" to pageRef,
                    "Accept" to "*/*"
                )
            }
        )


        document.select("script").map { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                val subData =
                    getAndUnpack(script.data()).substringAfter("\"tracks\":[").substringBefore("],")
                tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                    subtitleCallback.invoke(
                        SubtitleFile(
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
        @JsonProperty("hls") val hls: Boolean? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null,
    )

    data class ResponseWrapper(
        @JsonProperty("data") val data: ResponseSource? = null,
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )
}
