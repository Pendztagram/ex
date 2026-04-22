package com.zoronime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ZoronimeProvider : MainAPI() {
    override var mainUrl = "https://zoronime.online"
    override var name = "Zoronime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "ongoing?page=%d" to "Ongoing",
        "completed?page=%d" to "Completed",
        "anime?page=%d" to "Anime A-Z",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("a[href^=/anime/], a[href^=$mainUrl/anime/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        val hasNext = document.select("a[href*=\"?page=${page + 1}\"], a[href$=\"/ongoing?page=${page + 1}\"], a[href$=\"/completed?page=${page + 1}\"], a[href$=\"/anime?page=${page + 1}\"]").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search?q=$encoded", referer = "$mainUrl/").document
        return document.select("a[href^=/anime/], a[href^=$mainUrl/anime/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(fixUrl(url), referer = "$mainUrl/")
        val document = response.document
        val html = response.text
        val fixedUrl = response.url

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - ")
                ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("img")?.imageUrl()
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.substringBefore(" subtitle Indonesia")
            ?.trim()
        val genres = document.select("a[href^=/genres/], a[href^=$mainUrl/genres/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val recommendations = document.select("a[href^=/anime/], a[href^=$mainUrl/anime/]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        val metaDescription = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val type = when {
            metaDescription.contains("Movie", true) -> TvType.AnimeMovie
            metaDescription.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }
        val status = when {
            metaDescription.contains("Ongoing", true) -> ShowStatus.Ongoing
            metaDescription.contains("Completed", true) -> ShowStatus.Completed
            else -> null
        }
        val score = Regex("""Skor:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(metaDescription)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val year: Int? = null

        val episodes = document.select("a[href^=/episode/], a[href^=$mainUrl/episode/]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                plot = description
                this.tags = genres
                this.recommendations = recommendations
                this.showStatus = status
                this.year = year
                score?.let { this.score = Score.from10(it) }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val playUrl = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, playUrl, type, playUrl) {
                posterUrl = poster
                plot = description
                this.tags = genres
                this.recommendations = recommendations
                this.year = year
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
        val response = app.get(data, referer = "$mainUrl/")
        val html = response.text
        val emitted = linkedSetOf<String>()
        val episodeReferer = data
        val serverApi = "https://wg-anime-api-v2-production.up.railway.app/Otakudesu/server"

        suspend fun emitDirect(mediaUrl: String, referer: String, label: String = name) {
            val cleanUrl = decodeEscaped(mediaUrl).substringBefore('#').trim()
            if (cleanUrl.isBlank() || !emitted.add(cleanUrl)) return

            val type = when {
                cleanUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = cleanUrl,
                    type = type
                ) {
                    this.referer = referer
                    this.quality = qualityFromName(label)
                    headers = mapOf("Referer" to referer)
                }
            )
        }

        suspend fun inspectIframe(iframeUrl: String, label: String = "$name Direct") {
            val cleanUrl = decodeEscaped(iframeUrl)
            if (cleanUrl.isBlank()) return

            runCatching {
                loadExtractor(cleanUrl, episodeReferer, subtitleCallback, callback)
            }

            val iframePage = app.get(cleanUrl, referer = episodeReferer).text
            Regex("""https?://[^"'\\\s<>]+""", setOf(RegexOption.IGNORE_CASE))
                .findAll(iframePage)
                .map { decodeEscaped(it.value) }
                .filter { candidate ->
                    candidate.contains(".m3u8", true) ||
                        candidate.contains(".mp4", true) ||
                        candidate.contains("googlevideo", true) ||
                        candidate.contains("blogger.com/video.g", true) ||
                        candidate.contains("bloggerusercontent", true)
                }
                .distinct()
                .forEach { mediaUrl ->
                    emitDirect(mediaUrl, cleanUrl, label)
                }
        }

        val serverLinks = extractServerLinks(html)
        serverLinks.forEach { server ->
            runCatching {
                val resolved = app.get(
                    "$serverApi/${server.serverId}",
                    referer = episodeReferer,
                    headers = mapOf("Accept" to "application/json")
                ).parsedSafe<ZoronimeServerResponse>()?.data?.url
                    ?: return@runCatching

                val serverLabel = buildString {
                    append(name)
                    append(" [")
                    append(server.quality.ifBlank { "Source" })
                    append(" - ")
                    append(server.title)
                    append("]")
                }

                if (resolved.contains(".m3u8", true) ||
                    resolved.contains(".mp4", true) ||
                    resolved.contains("googlevideo", true) ||
                    resolved.contains("blogger", true)
                ) {
                    emitDirect(resolved, episodeReferer, serverLabel)
                } else {
                    inspectIframe(resolved, serverLabel)
                }
            }
        }

        val iframeCandidates = linkedSetOf<String>()
        Regex("""defaultStreamingUrl\\?":\\?"([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(iframeCandidates::add)
        Regex("""<iframe[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach(iframeCandidates::add)

        if (emitted.isEmpty()) {
            iframeCandidates.forEach { inspectIframe(it) }
        }

        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http")) path else "$mainUrl/${path.trimStart('/')}"
        return normalized.replace("%d", page.toString())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (!href.contains("/anime/")) return null

        val title = selectFirst("h3")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.substringBefore(" poster")?.trim()
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst("img")?.imageUrl()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (!href.contains("/episode/")) return null

        val label = selectFirst("span")?.text()?.trim()
            ?: text().trim()
            ?: return null
        val number = Regex("""(\d+)""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newEpisode(href) {
            name = if (label.startsWith("Episode", true)) label else "Episode ${number ?: "?"}"
            episode = number
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("src"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("abs:src"),
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun decodeEscaped(value: String): String {
        return value
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
    }

    private fun qualityFromName(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun extractServerLinks(html: String): List<ZoronimeServerLink> {
        val normalized = decodeEscaped(html)
        val matches = Regex(
            """"title":"\s*([^"]+?)\s*","serverList":\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(normalized)

        return matches.flatMap { qualityMatch ->
            val quality = qualityMatch.groupValues.getOrNull(1).orEmpty().trim()
            val block = qualityMatch.groupValues.getOrNull(2).orEmpty()
            Regex(
                """"title":"([^"]+)","serverId":"([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).findAll(block).mapNotNull { serverMatch ->
                val title = serverMatch.groupValues.getOrNull(1)?.trim().orEmpty()
                val serverId = serverMatch.groupValues.getOrNull(2)?.trim().orEmpty()
                if (title.isBlank() || serverId.isBlank()) null
                else ZoronimeServerLink(title, quality, serverId)
            }
        }.distinctBy { "${it.quality}:${it.title}:${it.serverId}" }.toList()
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }

    private data class ZoronimeServerLink(
        val title: String,
        val quality: String,
        val serverId: String,
    )

    private data class ZoronimeServerResponse(
        @JsonProperty("data") val data: ZoronimeServerData? = null,
    )

    private data class ZoronimeServerData(
        @JsonProperty("url") val url: String? = null,
    )
}
