package com.anixcafe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64

class AnixCafeVideoplayer : ExtractorApi() {
    override val name = "AnixCafe Videoplayer"
    override val mainUrl = "https://videoplayer.vip"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val emitted = linkedSetOf<String>()
        AnixCafeExtractorHelper.resolveLink(
            url = url,
            label = name,
            referer = referer ?: mainUrl,
            emitted = emitted,
            subtitleCallback = subtitleCallback,
            callback = callback,
            useGenericExtractor = false,
        )
    }
}

object AnixCafeExtractorHelper {
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
        useGenericExtractor: Boolean = true,
    ) {
        if (!emitted.add(url)) return

        if (isDirectMedia(url)) {
            callback(
                newExtractorLink(
                    source = label.substringBefore(" ").ifBlank { "AnixCafe" },
                    name = label,
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = qualityFromName(label)
                    this.headers = mapOf("Referer" to referer)
                }
            )
            return
        }

        if (useGenericExtractor) {
            runCatching { loadExtractor(url, referer, subtitleCallback, callback) }
        }

        val response = runCatching { app.get(url, referer = referer) }.getOrNull() ?: return
        val nested = linkedSetOf<String>()
        nested.addAll(extractMediaCandidates(response.text, url))

        response.document.select("source[src], video[src], iframe[src]").forEach { element ->
            element.attr("abs:src").ifBlank { element.attr("src") }
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
                useGenericExtractor = useGenericExtractor,
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
        return lower.contains("facebook.com/plugins") || lower.contains("histats.com")
    }

    fun isUnsupportedPlayerFrame(url: String): Boolean {
        return url.contains("videoplayer.vip", true)
    }

    private fun extractMediaCandidates(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val results = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|play_url|hls)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
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
            "ok.ru",
            "playmogo.com",
            "luluvdo.com",
            "dood",
            "stream",
        ).any { lower.contains(it) }
    }
}

class Playmogo : DoodLaExtractor() {
    override var mainUrl = "https://playmogo.com"
}
