package com.nimegami

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink

class NimegamiHalahgan : ExtractorApi() {
    override val name = "Halahgan"
    override val mainUrl = "https://stordl.halahgan.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = url.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return

        val apiUrl = "$mainUrl/streaming//$id?action=stream-url&id=$id"
        val response = app.get(
            apiUrl,
            referer = "$mainUrl/",
            headers = mapOf(
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to USER_AGENT,
            )
        ).text
        val video = tryParseJson<Source>(response)?.url
            ?: Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(response)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
            ?: return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = video,
                type = INFER_TYPE,
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf("Range" to "bytes=0-", "User-Agent" to USER_AGENT)
            }
        )
    }

    private data class Source(
        val url: String? = null,
    )
}
