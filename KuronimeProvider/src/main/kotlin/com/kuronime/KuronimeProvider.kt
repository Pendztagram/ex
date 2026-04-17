package com.kuronime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KuronimeProvider : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    override var name = "Kuronime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "ongoing-anime/page/%d/" to "Ongoing",
        "movies/page/%d/" to "Movies",
        "popular-anime/page/%d/" to "Popular",
        "genres/action/page/%d/" to "Action",
        "genres/fantasy/page/%d/" to "Fantasy",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), referer = mainUrl).document
        val items = document.select("article.bs, div.listupd article.bs, li .imgseries a.series")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = mainUrl).document
        return document.select("article.bs, div.listupd article.bs, li .imgseries a.series")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = mainUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val altTitle = detailValue(document, "Judul")
            ?.takeIf { !it.equals(title, true) }
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizePosterUrl)
            ?: document.selectFirst(".main-info img[itemprop=image], .tb img[itemprop=image], .l img[itemprop=image], img[itemprop=image], .main-info img:not(.dashicons), .tb img:not(.dashicons), .l img:not(.dashicons)")
                ?.imageUrl()
        val posterHeaders = poster?.let(::posterHeaders)
        val plot = document.selectFirst(".conx .const p, .conx p, .entry-content .main-info p")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
        val tags = document.select("div.infodetail li:has(b:matchesOwn(^Genre$)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val year = detailValue(document, "Tayang")
            ?.let(::extractYear)
            ?: detailValue(document, "Season")?.let(::extractYear)
            ?: detailValue(document, "Released on")?.let(::extractYear)
        val score = document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")
            ?.toDoubleOrNull()
            ?: Regex("""Rating\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(document.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
        val status = when {
            detailValue(document, "Status")?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            detailValue(document, "Status").isNullOrBlank() -> null
            else -> ShowStatus.Completed
        }
        val type = getType(detailValue(document, "Tipe"), fixedUrl)
        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        val episodes = document.select("div.bixbox.bxcl li")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedByDescending { it.episode ?: -1 }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.posterHeaders = posterHeaders
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = status
                this.engName = altTitle
                this.recommendations = recommendations
                score?.let { this.score = Score.from10(it) }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val playUrl = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, playUrl, type, playUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.posterHeaders = posterHeaders
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.substringBefore('#')
        val response = app.get(episodeUrl, referer = mainUrl)
        val document = response.document
        val html = response.text

        val encryptedId = Regex("""var\s+_0xa100d42aa\s*=\s*"([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
        val emitted = linkedSetOf<String>()
        suspend fun emitDirect(mediaUrl: String, referer: String, label: String = name) {
            val cleanUrl = mediaUrl.substringBefore('#')
            if (!emitted.add(cleanUrl)) return

            if (cleanUrl.contains(".m3u8", true)) {
                generateM3u8(label, cleanUrl, referer).forEach(callback)
                return
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = cleanUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = qualityFromUrl(cleanUrl)
                    headers = mapOf("Referer" to referer)
                }
            )
        }

        suspend fun inspectPlayerPage(playerUrl: String, referer: String) {
            val page = app.get(playerUrl, referer = referer)
            val pageHtml = page.text.replace("\\/", "/")
            val pageDoc = page.document

            pageDoc.select("iframe[src], iframe[data-src], video[src], video source[src], source[src]")
                .forEach { element ->
                    val nested = element.attr("abs:src").ifBlank {
                        element.attr("src").ifBlank { element.attr("data-src") }
                    }.trim()
                    if (nested.isBlank()) return@forEach

                    when {
                        nested.isDirectMedia() -> emitDirect(nested, playerUrl, "$name Direct")
                        nested.startsWith("http") -> runCatching {
                            loadExtractor(nested, playerUrl, subtitleCallback, callback)
                        }
                    }
                }

            Regex(
                """https?://[^"'\\\s<]+(?:\.m3u8|\.mp4|(?:googlevideo|blogger|blogspot|bloggerusercontent)[^"'\\\s<]*)""",
                RegexOption.IGNORE_CASE
            ).findAll(pageHtml).map { it.value }.distinct().forEach { mediaUrl ->
                if (mediaUrl.isDirectMedia()) {
                    emitDirect(mediaUrl, playerUrl, "$name Direct")
                }
            }

            resolveWithWebView(playerUrl, referer, callback, emitted)
        }

        val sourcePayload = encryptedId?.let { fetchSources(it, episodeUrl) }
        val mirrorPayload = sourcePayload?.mirror?.let(::decodeMirrorPayload)

        val extractorReferer = URI(episodeUrl).let { "${it.scheme}://${it.host}/" }

        suspend fun emitExtractor(url: String) {
            val cleanUrl = url.trim().substringBefore('#')
            if (cleanUrl.isBlank() || !emitted.add(cleanUrl)) return
            runCatching {
                loadExtractor(cleanUrl, extractorReferer, subtitleCallback, callback)
            }.onFailure {
                emitted.remove(cleanUrl)
            }
        }

        mirrorPayload?.filelions?.let {
            emitExtractor(it)
        }
        mirrorPayload?.embed.orEmpty().forEach { (_, hosts) ->
            hosts.forEach { (_, hostUrl) ->
                hostUrl?.takeIf { it.isNotBlank() }?.let {
                    emitExtractor(it)
                }
            }
        }

        if (emitted.isEmpty()) {
            val candidates = linkedSetOf<String>()
            document.select("iframe[src], iframe[data-src]").forEach { frame ->
                frame.attr("abs:src").ifBlank {
                    frame.attr("src").ifBlank { frame.attr("data-src") }
                }.takeIf { it.startsWith("http") }?.let(candidates::add)
            }

            candidates.forEach { playerUrl ->
                runCatching {
                    inspectPlayerPage(playerUrl, episodeUrl)
                }
            }
        }

        if (emitted.isEmpty()) {
            runCatching {
                resolveWithWebView(episodeUrl, episodeUrl, callback, emitted)
            }
        }

        return emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = when {
            tagName().equals("a", true) -> this
            else -> selectFirst("a[href]")
        } ?: return null

        val href = anchor.attr("href").trim()
            .takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
            ?: return null

        if (!href.contains("/anime/", true)) return null

        val title = listOf(
            anchor.attr("title").trim(),
            selectFirst("h2")?.text()?.trim().orEmpty(),
            selectFirst(".limit > img[itemprop=image], .imgseries img[itemprop=image], .bsx img[itemprop=image], .limit > img:not(.dashicons), .imgseries img:not(.dashicons), .bsx img:not(.dashicons)")?.attr("alt")?.trim().orEmpty(),
            anchor.text().trim()
        ).firstOrNull { it.isNotBlank() } ?: return null

        val poster = selectFirst(".limit > img[itemprop=image], .imgseries img[itemprop=image], .bsx img[itemprop=image], .limit > img:not(.dashicons), .imgseries img:not(.dashicons), .bsx img:not(.dashicons)")
            ?.imageUrl()
        val posterHeaders = poster?.let(::posterHeaders)
        val type = getType(
            selectFirst(".type")?.text()?.trim(),
            href
        )

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            this.posterHeaders = posterHeaders
        }
    }

    private fun Element.toEpisode(): Episode? {
        val anchor = selectFirst("a[href*='/nonton-']") ?: return null
        val href = anchor.attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        val rawTitle = anchor.text().trim()
        val episodeNumber = Regex("""Episode\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(rawTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        return newEpisode(href) {
            name = rawTitle.ifBlank { "Episode ${episodeNumber ?: "?"}" }
            episode = episodeNumber?.toInt()
        }
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select("div.infodetail li")
            .firstOrNull { it.selectFirst("b")?.text()?.trim()?.equals(label, true) == true }
            ?.text()
            ?.substringAfter(':', "")
            ?.trim()
            ?.ifBlank { null }
    }

    private fun Element.imageUrl(): String? {
        val raw = listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-lazyloaded"),
            attr("src"),
            attr("abs:src"),
        ).firstOrNull { it.isNotBlank() && !it.contains("controls-play", true) }.orEmpty()

        val fallback = if (raw.isBlank()) {
            Regex("""(?:data-src|data-lazy-src|src)=['"]?([^'"\s>]+)""", RegexOption.IGNORE_CASE)
                .find(outerHtml())
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        } else {
            raw
        }

        return fallback.takeIf { it.isNotBlank() && !it.contains("controls-play", true) }
            ?.let(::normalizePosterUrl)
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http")) path else "$mainUrl/${path.trimStart('/')}"
        return normalized.replace("%d", page.toString())
    }

    private fun getType(typeLabel: String?, url: String): TvType {
        val value = typeLabel.orEmpty()
        return when {
            value.contains("movie", true) || url.contains("/movies/", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun extractYear(text: String): Int? {
        return Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }

    private fun normalizePosterUrl(url: String): String {
        val fixed = fixUrl(url).replace("&amp;", "&")
        val wpProxyMatch = Regex("""https?://i\d+\.wp\.com/([^?]+)(?:\?.*)?""", RegexOption.IGNORE_CASE)
            .find(fixed)
            ?.groupValues
            ?.getOrNull(1)

        return wpProxyMatch?.let { proxiedPath ->
            "https://${proxiedPath.trimStart('/')}"
        } ?: fixed
    }

    private fun posterHeaders(url: String?): Map<String, String>? {
        val posterUrl = url?.takeIf { it.isNotBlank() } ?: return null
        return mapOf("Referer" to URI(posterUrl).let { "${it.scheme}://${it.host}/" })
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains("mime=video/mp4") ||
            lower.contains("mime=video%2fmp4") ||
            lower.contains("googlevideo") ||
            lower.contains("bloggerusercontent")
    }

    private fun qualityFromUrl(url: String): Int {
        return getQualityFromName(url).takeIf { it != Qualities.Unknown.value }
            ?: Regex("""\b(2160|1440|1080|720|480|360|240)\b""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private suspend fun fetchSources(encryptedId: String, referer: String): SourcesResponse? {
        val payload = """{"id":"$encryptedId"}"""
            .toRequestBody("application/json".toMediaType())
        return app.post(
            "https://animeku.org/api/v9/sources",
            requestBody = payload,
            referer = referer,
            headers = mapOf(
                "Origin" to mainUrl,
                "Accept" to "application/json, text/plain, */*",
            )
        ).parsedSafe<SourcesResponse>()
    }

    private fun decodeMirrorPayload(encoded: String): MirrorPayload? {
        return runCatching {
            val wrapperJson = String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8)
            val wrapper = JSONObject(wrapperJson)
            val cipherText = Base64.getDecoder().decode(wrapper.getString("ct"))
            val iv = hexToBytes(wrapper.getString("iv"))
            val salt = hexToBytes(wrapper.getString("s"))
            val key = evpBytesToKey(MIRROR_PASSWORD.toByteArray(Charsets.UTF_8), salt, 32)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )

            val decrypted = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            parseMirrorPayload(JSONObject(decrypted))
        }.getOrNull()
    }

    private fun parseMirrorPayload(json: JSONObject): MirrorPayload {
        fun JSONObject.optNestedMap(): LinkedHashMap<String, LinkedHashMap<String, String?>> {
            val outer = linkedMapOf<String, LinkedHashMap<String, String?>>()
            keys().forEach { qualityKey ->
                val qualityObject = optJSONObject(qualityKey) ?: return@forEach
                val hosts = linkedMapOf<String, String?>()
                qualityObject.keys().forEach { hostKey ->
                    hosts[hostKey] = qualityObject.optString(hostKey).takeIf { it.isNotBlank() && it != "null" }
                }
                if (hosts.isNotEmpty()) outer[qualityKey] = hosts
            }
            return outer
        }

        return MirrorPayload(
            embed = json.optJSONObject("embed")?.optNestedMap().orEmpty(),
            filelions = json.optString("filelions").takeIf { it.isNotBlank() && it != "null" },
            blog = json.optString("blog").takeIf { it.isNotBlank() && it != "null" },
            raw = json.optString("raw").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLength: Int): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        val generated = ArrayList<Byte>()
        var block = ByteArray(0)

        while (generated.size < keyLength) {
            digest.reset()
            digest.update(block)
            digest.update(password)
            digest.update(salt)
            block = digest.digest()
            generated.addAll(block.toList())
        }

        return generated.take(keyLength).toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2)
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()
    }

    private suspend fun resolveWithWebView(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>
    ) {
        val resolved = app.get(
            url,
            referer = referer,
            interceptor = WebViewResolver(
                interceptUrl = Regex(
                    """https?://[^"' ]+(?:\.m3u8|\.mp4|(?:googlevideo|blogger|blogspot|bloggerusercontent)[^"' ]*)""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = listOf(
                    Regex("""https?://[^"' ]+mime=video(?:%2F|/)mp4[^"' ]*""", RegexOption.IGNORE_CASE),
                    Regex("""https?://[^"' ]+\.m3u8[^"' ]*""", RegexOption.IGNORE_CASE),
                ),
                useOkhttp = false,
                timeout = 20_000L
            )
        ).url.substringBefore('#')

        if (!resolved.isDirectMedia() || !emitted.add(resolved)) return

        if (resolved.contains(".m3u8", true)) {
            generateM3u8(name, resolved, url).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name Auto",
                url = resolved,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = URI(url).let { "${it.scheme}://${it.host}/" }
                this.quality = qualityFromUrl(resolved)
                headers = mapOf("Referer" to url)
            }
        )
    }

    private data class SourcesResponse(
        val status: Int? = null,
        val src: String? = null,
        val blog: String? = null,
        val src_sd: String? = null,
        val mirror: String? = null,
    )

    private data class MirrorPayload(
        val embed: Map<String, Map<String, String?>> = emptyMap(),
        val filelions: String? = null,
        val blog: String? = null,
        val raw: String? = null,
    )

    private companion object {
        const val MIRROR_PASSWORD = "3&!Z0M,VIZ;dZW=="
    }
}
