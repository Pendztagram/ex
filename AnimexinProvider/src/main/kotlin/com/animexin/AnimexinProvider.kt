package com.animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=%d" to "Latest",
        "anime/?status=ongoing&type=&order=update&page=%d" to "Ongoing",
        "anime/?status=completed&type=&order=update&page=%d" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), referer = "$mainUrl/").document
        val items = document.select("div.listupd div.bsx a[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("div.hpage a, a.next, .pagination .next").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.select("div.listupd div.bsx a[href], article a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url.contains("-episode-", true) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.substringBefore(" Episode ")
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".thumb img, .infox img, img.wp-post-image")?.imageUrl()

        val description = document.selectFirst(".entry-content p, .desc p, .infox .desc")
            ?.text()
            ?.trim()
            ?.ifBlank { null }

        val tags = document.select("a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val status = when {
            detailValue(document, "Status")?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            detailValue(document, "Status").isNullOrBlank() -> null
            else -> ShowStatus.Completed
        }

        val year = detailValue(document, "Released")?.let(::extractYear)
        val type = getType(detailValue(document, "Type"), fixedUrl)

        val episodes = document.select(".eplister li a[href], .epl li a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedByDescending { it.episode ?: -1 }

        val recommendations = document.select(".listupd .bsx a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                plot = description
                this.tags = tags
                showStatus = status
                this.year = year
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val playUrl = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, playUrl, type, playUrl) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
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

        suspend fun emitDirect(linkUrl: String, referer: String, label: String) {
            val clean = linkUrl.substringBefore('#').trim()
            if (clean.isBlank() || !emitted.add(clean)) return

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = clean,
                    type = if (clean.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    quality = qualityFromName(label)
                    headers = mapOf("Referer" to referer)
                }
            )
        }

        val playerFrames = document.select("#pembed iframe[src], .player-embed iframe[src], iframe[src]")
            .mapNotNull { it.attr("abs:src").ifBlank { it.attr("src") }.takeIf(String::isNotBlank) }
            .distinct()

        playerFrames.forEach { frame ->
            runCatching {
                loadExtractor(frame, data, subtitleCallback, callback)
            }
            runCatching {
                val html = app.get(frame, referer = data).text
                Regex("""https?://[^"'\\\s<>]+""")
                    .findAll(html)
                    .map { it.value }
                    .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains("dailymotion", true) }
                    .distinct()
                    .forEach { media ->
                        if (media.contains("dailymotion", true)) {
                            runCatching { loadExtractor(media, frame, subtitleCallback, callback) }
                        } else {
                            emitDirect(media, frame, "$name Direct")
                        }
                    }
            }
        }

        document.select(".dlbox a[href], .download a[href], a[href*='mediafire.com'], a[href*='mirrored.to']")
            .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
            .distinct()
            .forEach { dl ->
                val label = when {
                    dl.contains("mediafire", true) -> "$name Mediafire"
                    dl.contains("mirrored.to", true) -> "$name Mirror"
                    else -> "$name Download"
                }
                emitDirect(dl, data, label)
            }

        return emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (href.contains("/blog/", true)) return null

        val title = listOf(
            attr("title").trim(),
            selectFirst("h2, h3")?.text()?.trim().orEmpty(),
            selectFirst("img")?.attr("alt")?.trim().orEmpty(),
            text().trim()
        ).firstOrNull { it.isNotBlank() }?.substringBefore(" Episode ") ?: return null

        val poster = selectFirst("img")?.imageUrl()

        return newAnimeSearchResponse(title, href, getType(null, href)) {
            posterUrl = poster
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        val epText = text().trim()
        val epNum = Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(epText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

        return newEpisode(href) {
            name = epText.ifBlank { "Episode ${epNum ?: "?"}" }
            episode = epNum?.toInt()
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("src"),
            attr("abs:src")
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun detailValue(document: Document, label: String): String? {
        val regex = Regex("""$label\s*:\s*([^\n<]+)""", RegexOption.IGNORE_CASE)
        return regex.find(document.text())?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
    }

    private fun extractYear(text: String): Int? {
        return Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()
    }

    private fun qualityFromName(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getType(typeLabel: String?, url: String): TvType {
        val value = typeLabel.orEmpty()
        return when {
            value.contains("movie", true) || url.contains("movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http")) path else "$mainUrl/${path.trimStart('/')}"
        return normalized.replace("%d", page.toString())
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }
}
