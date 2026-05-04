package com.kazefuri

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64

object KazefuriExtractorHelper {
    fun decodeMirror(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val decoded = runCatching {
            String(Base64.getDecoder().decode(value.trim()))
        }.getOrElse { value }

        val document = Jsoup.parse(decoded)
        val links = linkedSetOf<String>()
        document.select("iframe[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("src").ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let(links::add)
        }
        Regex("""https?://[^\s"'<>\\]+""").findAll(decoded).forEach { links.add(it.value) }
        return links.toList()
    }

    suspend fun resolveLink(
        url: String,
        label: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (!emitted.add(url)) return

        if (isDirectMedia(url)) {
            callback(
                newExtractorLink(
                    source = label.substringBefore(" ").ifBlank { "Kazefuri" },
                    name = label,
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = qualityFromName(label)
                    this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
                }
            )
            return
        }

        runCatching { loadExtractor(url, referer, subtitleCallback, callback) }

        val response = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
        }.getOrNull() ?: return
        val nested = linkedSetOf<String>()
        nested.addAll(extractMediaCandidates(response.text, url))

        response.document.select("source[src], video[src], iframe[src], a[href]").forEach { element ->
            element.attr("abs:src").ifBlank { element.attr("abs:href").ifBlank { element.attr("src").ifBlank { element.attr("href") } } }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeUrl(it, url) }
                ?.let(nested::add)
        }

        response.document.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("eval(function(p,a,c,k,e,d)", true)) {
                runCatching { getAndUnpack(data) }
                    .getOrNull()
                    ?.let { nested.addAll(extractMediaCandidates(it, url)) }
            }
        }

        nested.forEach { nestedUrl ->
            resolveLink(
                url = nestedUrl,
                label = label,
                referer = url,
                emitted = emitted,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }
    }

    fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = raw.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    fun isNoiseFrame(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com/plugins") ||
            lower.contains("histats.com") ||
            lower.contains("about:blank")
    }

    private fun extractMediaCandidates(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val results = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|play_url|hls|urlPlay)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:/|//)[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                normalizeUrl(raw, baseUrl)?.let { url ->
                    if (isDirectMedia(url) || shouldFollow(url)) results.add(url)
                }
            }
        }
        return results
    }

    private fun qualityFromName(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240|4K)\b""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.value
            ?.let { if (it.equals("4K", true)) "2160" else it }
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun isDirectMedia(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url)
    }

    private fun shouldFollow(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "dailymotion.com",
            "geo.dailymotion.com",
            "ok.ru",
            "odnoklassniki",
            "rumble.com",
            "rubyvidhub.com",
            "streamruby",
            "turbovidhls.com",
            "short.icu",
        ).any { lower.contains(it) }
    }
}

class KazefuriGeoDailymotion : KazefuriDailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class KazefuriDailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return
        val response = app.get("$baseUrl/player/metadata/video/$id", referer = embedUrl).text
        Regex(""""url"\s*:\s*"([^"]+)"""")
            .findAll(response)
            .map { it.groupValues[1] }
            .filter { it.contains(".m3u8") }
            .distinct()
            .forEach { streamLink -> generateM3u8(name, streamLink, "").forEach(callback) }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    private fun getVideoId(url: String): String? {
        val path = URI(url).path
        val id = path.substringAfter("/video/")
        return if (id.matches(videoIdRegex)) id else null
    }
}

class KazefuriOkRuSSL : KazefuriOdnoklassniki() {
    override var name = "OkRuSSL"
    override var mainUrl = "https://ok.ru"
}

class KazefuriOkRuHTTP : KazefuriOdnoklassniki() {
    override var name = "OkRuHTTP"
    override var mainUrl = "http://ok.ru"
}

open class KazefuriOdnoklassniki : ExtractorApi() {
    override val name = "Odnoklassniki"
    override val mainUrl = "https://odnoklassniki.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )
        val videoReq = app.get(url.replace("/video/", "/videoembed/"), headers = headers).text
            .replace("\\&quot;", "\"")
            .replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Video not found")
        val videos = AppUtils.tryParseJson<List<OkRuVideo>>(videosStr)
            ?: throw ErrorLoadingException("Video not found")

        videos.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "4k")

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }

    data class OkRuVideo(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String,
    )
}

class KazefuriRumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val scriptData = response.document.selectFirst("script:containsData(mp4)")?.data()
            ?.substringAfter("{\"mp4")
            ?.substringBefore("\"evt\":{")
            ?: return

        Regex(""""url":"(.*?)"""").findAll(scriptData)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { it.contains("rumble.com") && it.contains(".m3u8") }
            .distinct()
            .forEach { streamLink ->
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
    }
}

class KazefuriStreamRuby : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""embed-([a-zA-Z0-9]+)\.html""").find(url)?.groupValues?.getOrNull(1) ?: return
        val response = app.post(
            "$mainUrl/dl",
            data = mapOf(
                "op" to "embed",
                "file_code" to id,
                "auto" to "1",
                "referer" to "",
            ),
            referer = referer
        )
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return
        val m3u8 = Regex("""file:\s*"(.*?m3u8.*?)"""").find(script)?.groupValues?.getOrNull(1) ?: return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = mainUrl
            }
        )
    }
}

class KazefuriTurbovid : ExtractorApi() {
    override var name = "Turbovid"
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

        return generated.ifEmpty {
            listOf(
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
}
