package com.yflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class Fourspromax : MegaUp() {
    override var mainUrl = "https://4spromax.site"
    override val requiresReferer = true
}

class Rapidairmax : MegaUp() {
    override var mainUrl = "https://rapidairmax.site"
    override val requiresReferer = true
}

class Rapidshare : MegaUp() {
    override var mainUrl = "https://rapidshare.cc"
    override val requiresReferer = true
}

open class MegaUp : ExtractorApi() {
    override var name = "MegaUp"
    override var mainUrl = "https://megaup.live"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mediaUrl = url.replace("/e/", "/media/").replace("/e2/", "/media/")
        val displayName = referer ?: name

        val encodedResult = app.get(mediaUrl, headers = HEADERS)
            .parsedSafe<YflixMediaResponse>()
            ?.result
            ?: return

        val body = """
            {
              "text": ${JSONObject.quote(encodedResult)},
              "agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
            }
        """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val m3u8Data = app.post(DEC_MEGA, requestBody = body).text
        if (m3u8Data.isBlank()) return

        try {
            val root = JSONObject(m3u8Data)
            val result = root.optJSONObject("result") ?: return
            val sources = result.optJSONArray("sources") ?: JSONArray()

            if (sources.length() > 0) {
                val firstSourceObj = sources.optJSONObject(0)
                val m3u8File = when {
                    firstSourceObj != null -> firstSourceObj.optString("file").takeIf { it.isNotBlank() }
                    else -> sources.optString(0).takeIf { it.isNotBlank() }
                }
                if (m3u8File != null) {
                    M3u8Helper.generateM3u8(displayName, m3u8File, mainUrl).forEach(callback)
                }
            }

            val tracks = result.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until tracks.length()) {
                val trackObj = tracks.optJSONObject(i) ?: continue
                val label = trackObj.optString("label").trim().takeIf { it.isNotEmpty() }
                val file = trackObj.optString("file").takeIf { it.isNotBlank() }
                if (label != null && file != null) {
                    subtitleCallback(newSubtitleFile(label, file))
                }
            }

            runCatching {
                val subtitleUrl = URLDecoder.decode(
                    url.substringAfter("sub.list="),
                    StandardCharsets.UTF_8.name()
                )
                val response = app.get(subtitleUrl).text
                val subtitles = tryParseJson<List<Map<String, Any>>>(response)
                subtitles?.forEach { sub ->
                    val file = sub["file"]?.toString()
                    val label = sub["label"]?.toString()
                    if (!file.isNullOrBlank() && !label.isNullOrBlank()) {
                        subtitleCallback(newSubtitleFile(label, file))
                    }
                }
            }
        } catch (_: JSONException) {
            Log.e("Yflix", "Failed to parse MegaUp payload")
        }
    }

    data class YflixMediaResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null,
    )

    companion object {
        private const val DEC_MEGA = "https://enc-dec.app/api/dec-mega"
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0",
            "Accept" to "text/html, */*; q=0.01",
            "Accept-Language" to "en-US,en;q=0.5",
            "Sec-GPC" to "1",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "Priority" to "u=0",
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
            "referer" to "https://yflix.to/",
        )
    }
}
