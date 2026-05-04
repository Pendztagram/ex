package com.anixcafe

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class AnixCafeProvider : MainAPI() {
    override var mainUrl = "https://anixcafe.com"
    override var name = "AnixCafe"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?page=%d" to "Anime List",
        "$mainUrl/?page=%d" to "Update Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page), referer = "$mainUrl/").document
        val items = document.select("div.listupd article.bs, div.listupd div.bs, article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("div.hpage a.r, a.next, .pagination .next").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.select("div.listupd article.bs, div.listupd div.bs, article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.entry-title, .bigcontent h1, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".bigcontent .thumb img, .thumbook img, meta[property=og:image]")
            ?.let { it.attr("content").ifBlank { it.imageUrl() } }

        val type = getType(detailValue(document, "Tipe"), fixedUrl)
        val year = detailValue(document, "Rilis")?.let(::extractYear)
            ?: detailValue(document, "Dirilis pada")?.let(::extractYear)
        val status = getStatus(detailValue(document, "Status"))
        val tags = document.select(".genxed a[href], .infox a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val plot = document.selectFirst(".bigcontent .desc, .info-content .desc, .entry-content p")
            ?.text()
            ?.trim()
            ?.ifBlank { null }

        val episodes = document.select(".eplister a[href], .episodelist a[href], ul.episodios a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedByDescending { it.episode ?: -1 }

        val recommendations = document.select(".serieslist a.series[href], .listupd .bsx a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val emitted = linkedSetOf<String>()
        val candidates = linkedSetOf<Pair<String, String>>()

        document.select("#pembed iframe[src], .player-embed iframe[src], .megavid iframe[src]").forEach { iframe ->
            iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.let { candidates.add(it to "Default") }
        }

        document.select("select.mirror option[value]").forEach { option ->
            val label = option.text().trim().ifBlank { "Mirror" }
            decodeMirror(option.attr("value")).forEach { mirror ->
                candidates.add(mirror to label)
            }
        }

        candidates
            .filterNot { (url, _) -> isNoiseFrame(url) }
            .amap { (url, label) ->
                resolveLink(
                    url = normalizeUrl(url, data) ?: return@amap,
                    label = label,
                    referer = data,
                    emitted = emitted,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

        document.select(".soraddlx a[href], .dlbox a[href], .download a[href], a[href*='mirrored.to'], a[href*='terabox']")
            .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
            .distinct()
            .forEach { runCatching { loadExtractor(it, data, subtitleCallback, callback) } }

        return true
    }

    private suspend fun resolveLink(
        url: String,
        label: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!emitted.add(url)) return

        if (isDirectMedia(url)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $label",
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

        runCatching { loadExtractor(url, referer, subtitleCallback, callback) }

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
            resolveLink(nestedUrl, label, url, emitted, subtitleCallback, callback)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val fixedHref = getProperAnimeLink(fixUrl(href))

        val title = listOf(
            link.attr("title"),
            selectFirst(".tt h2, .tt, h2, h3")?.text(),
            selectFirst("img")?.attr("alt")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst("img")?.imageUrl()
        val type = getType(selectFirst(".typez")?.text(), fixedHref)
        val episode = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(link.attr("title").ifBlank { text() })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, fixedHref, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = attr("abs:href").ifBlank { attr("href") }.takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        val rawTitle = selectFirst(".epl-title")?.text()?.trim().orEmpty()
            .ifBlank { attr("title").trim() }
            .ifBlank { text().trim() }
        val epNum = selectFirst(".epl-num")?.text()?.trim()?.toDoubleOrNull()
            ?: Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(rawTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        return newEpisode(href) {
            name = rawTitle.cleanTitle().ifBlank { "Episode ${epNum ?: "?"}" }
            episode = epNum?.toInt()
        }
    }

    private fun decodeMirror(value: String): List<String> {
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

    private fun getProperAnimeLink(url: String): String {
        if (url.contains("/anime/", true)) return url

        val rel = Regex("""rel=["'](\d+)["']""").find(url)?.groupValues?.getOrNull(1)
        if (!rel.isNullOrBlank()) return url

        var slug = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        slug = slug
            .substringBefore("-episode-")
            .substringBefore("-subtitle-indonesia")
            .replace(Regex("""-season-(\d+)"""), "-$1th-season")
        return "$mainUrl/anime/$slug/"
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
            attr("abs:src")
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select(".spe span, .infox .spe span")
            .firstOrNull { span ->
                span.selectFirst("b")?.text()?.replace(":", "")?.trim()?.equals(label, true) == true
            }
            ?.ownText()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun getType(typeLabel: String?, url: String): TvType {
        val value = typeLabel.orEmpty()
        return when {
            value.contains("movie", true) || url.contains("movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value.isNullOrBlank() -> null
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("hiatus", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
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
            "videoplayer.vip",
            "dailymotion.com",
            "ok.ru",
            "playmogo.com",
            "luluvdo.com",
            "dood",
            "stream",
        ).any { lower.contains(it) }
    }

    private fun isNoiseFrame(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com/plugins") || lower.contains("histats.com")
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
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

    private fun String.cleanTitle(): String {
        return replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+(?:\.\d+)?.*$"""), "")
            .trim()
    }
}
