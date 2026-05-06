package com.dubbindo

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DubbindoProvider : MainAPI() {
    override var mainUrl = "https://www.dubbindo.site"
    override var name = "Dubbindo🤐"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AnimeMovie,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos/latest?page_id=%d" to "Latest Videos",
        "$mainUrl/videos/trending?page_id=%d" to "Trending",
        "$mainUrl/videos/top?page_id=%d" to "Top Videos",
        "$mainUrl/videos/category/1?page_id=%d" to "Film Movie",
        "$mainUrl/videos/category/3?page_id=%d" to "TV Series",
        "$mainUrl/videos/category/4?page_id=%d" to "Anime Movie",
        "$mainUrl/videos/category/5?page_id=%d" to "Anime Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page), referer = "$mainUrl/").document
        val items = document.toSearchResults()

        val hasNext = document.select("a[title='Next Page'], ul.pagination a[href*='page_id=${page + 1}']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search?keyword=$encoded", referer = "$mainUrl/").document
        return document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = document.selectFirst("h1[itemprop=title], meta[property=og:title], meta[name=title], title")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("meta[property=og:image], meta[name=thumbnail], video[poster]")
            ?.let { it.attr("content").ifBlank { it.attr("abs:poster").ifBlank { it.attr("poster") } } }
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)

        val plot = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.decodeHtml()
            ?.trim()
            ?.ifBlank { null }

        val category = document.selectFirst(".video-category a, a[href*='/videos/category/']")?.text()?.trim()
        val type = getType(category, title)
        val recommendations = document.toSearchResults()
            .filterNot { it.url == fixedUrl }

        return newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
            posterUrl = poster
            backgroundPosterUrl = poster
            plot?.let { this.plot = it }
            this.tags = listOfNotNull(category).filter { it.isNotBlank() }
            this.recommendations = recommendations
            this.year = extractYear(title)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val links = document.select("video source[src], video[src]")
            .mapNotNull { source ->
                val url = source.attr("abs:src").ifBlank { source.attr("src") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = source.attr("label")
                    .ifBlank { source.attr("title") }
                    .ifBlank { source.attr("data-quality") }
                    .ifBlank { source.attr("res").takeIf { it.isNotBlank() }?.let { "${it}p" }.orEmpty() }
                    .ifBlank { "MP4" }
                fixUrl(url) to label
            }
            .distinctBy { it.first }

        links.forEach { (url, label) ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = data
                    quality = qualityFromLabel(label)
                    headers = mapOf(
                        "Referer" to data,
                        "Range" to "bytes=0-",
                    )
                }
            )
        }

        return links.isNotEmpty()
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("a[href*='/watch/']")
            .mapNotNull { it.toSearchResultFromLink() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResultFromLink(): SearchResponse? {
        val link = this
        val href = link.attr("abs:href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (!href.contains("/watch/", true)) return null
        val card = parents().firstOrNull { parent ->
            parent.`is`(".video-list, .video-wrapper, .video-latest-list, .related-video-wrapper, [data-id], [data-sidebar-video]")
        } ?: parent() ?: this

        val title = listOf(
            link.selectFirst("h4[title]")?.attr("title"),
            link.selectFirst("h4")?.text(),
            card.selectFirst("h4[title]")?.attr("title"),
            card.selectFirst(".video-title a, .video-list-title h4, h4")?.text(),
            card.selectFirst("img[alt]")?.attr("alt"),
            link.attr("title"),
            link.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = card.selectFirst("img")?.imageUrl()
        val category = card.selectFirst(".video-category a, a[href*='/videos/category/']")?.text()?.trim()

        return newMovieSearchResponse(title, href, getType(category, title)) {
            posterUrl = poster
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:src"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun getType(category: String?, title: String): TvType {
        val value = "${category.orEmpty()} $title"
        return when {
            value.contains("anime series", true) || value.contains("episode", true) && value.contains("anime", true) -> TvType.Anime
            value.contains("anime movie", true) -> TvType.AnimeMovie
            value.contains("tv series", true) || value.contains("episode", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun qualityFromLabel(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""")
            .find(value)
            ?.value
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(?:19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun String.decodeHtml(): String {
        return org.jsoup.Jsoup.parse(this).text()
    }

    private fun String.cleanTitle(): String {
        return decodeHtml()
            .replace(Regex("""^\s*[\u200B-\u200D\uFEFF\u2063]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
