package com.nekokun

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI

class NekokunNekoLions : ExtractorApi() {
    override val name = "NekoLions"
    override val mainUrl = "https://nekolions.my.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        NekokunExtractorHelper.resolveLink(
            url = url,
            label = name,
            referer = referer ?: mainUrl,
            emitted = linkedSetOf(),
            subtitleCallback = subtitleCallback,
            callback = callback,
            useGenericExtractor = false,
        )
    }
}

class NekokunNekoWish : ExtractorApi() {
    override val name = "NekoWish"
    override val mainUrl = "https://nekowish.my.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        NekokunExtractorHelper.resolveLink(
            url = url,
            label = name,
            referer = referer ?: mainUrl,
            emitted = linkedSetOf(),
            subtitleCallback = subtitleCallback,
            callback = callback,
            useGenericExtractor = false,
        )
    }
}

object NekokunExtractorHelper {
    fun decodeMirror(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        val decoded = runCatching {
            String(Base64.decode(value.trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrElse { value }

        val links = linkedSetOf<String>()
        val document = Jsoup.parse(decoded)
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
        val cleanUrl = normalizeUrl(url, referer) ?: return
        if (!emitted.add(cleanUrl)) return

        if (isDirectMedia(cleanUrl)) {
            callback(
                newExtractorLink(
                    source = label.substringBefore(" ").ifBlank { "Nekokun" },
                    name = label,
                    url = cleanUrl,
                    type = if (cleanUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = qualityFromName(label.ifBlank { cleanUrl })
                    headers = mapOf(
                        "Referer" to referer,
                        "Range" to "bytes=0-",
                    )
                }
            )
            return
        }

        if (useGenericExtractor) {
            runCatching { loadExtractor(cleanUrl, referer, subtitleCallback, callback) }
        }

        val response = runCatching { app.get(cleanUrl, referer = referer) }.getOrNull() ?: return
        val nested = linkedSetOf<String>()
        nested.addAll(extractMediaCandidates(response.text, cleanUrl))

        response.document.select("source[src], video[src], iframe[src]").forEach { element ->
            element.attr("abs:src").ifBlank { element.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeUrl(it, cleanUrl) }
                ?.let(nested::add)
        }

        response.document.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("eval(function(p,a,c,k,e,d)", true)) {
                runCatching { getAndUnpack(data) }
                    .getOrNull()
                    ?.let { nested.addAll(extractMediaCandidates(it, cleanUrl)) }
            }
        }

        nested.forEach { nestedUrl ->
            resolveLink(
                url = nestedUrl,
                label = label,
                referer = cleanUrl,
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
        return lower.contains("facebook.com/plugins") ||
            lower.contains("histats.com") ||
            lower.contains("chat.nekostream.my.id")
    }

    private fun extractMediaCandidates(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val results = linkedSetOf<String>()
        val normalized = text.replace("\\/", "/")
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|play_url|hls)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:/|//)[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                normalizeUrl(raw, baseUrl)?.let { candidate ->
                    if (isDirectMedia(candidate) || shouldFollow(candidate)) results.add(candidate)
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
        return Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url) ||
            url.contains("mime=video/mp4", true) ||
            url.contains("mime=video%2fmp4", true) ||
            url.contains("googlevideo", true)
    }

    private fun shouldFollow(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "nekolions.my.id",
            "nekowish.my.id",
            "dailymotion.com",
            "ok.ru",
            "stream",
            "dood",
        ).any { lower.contains(it) }
    }
}
