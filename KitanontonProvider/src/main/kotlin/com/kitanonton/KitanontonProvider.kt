package com.kitanonton

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class KitanontonProvider : MainAPI() {
    override var mainUrl = "https://kitanonton2.guru"
    override var name = "Kitanonton"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime,
    )

    override val mainPage = mainPageOf(
        "movies/page/%d/" to "Movie Terbaru",
        "series/page/%d/" to "Series Terbaru",
        "genre/action/page/%d/" to "Action",
        "genre/drama/page/%d/" to "Drama",
        "genre/horror/page/%d/" to "Horror",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest) =
        newHomePageResponse(
            request.name,
            app.get(fixPagedUrl(request.data, page), referer = mainUrl)
                .document
                .select("div.ml-item, article.item, div.items article")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        )

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = mainUrl).document
        return document.select(
            "div.result-item, div.ml-item, article.item, div.items article, div.movies-list-wrap div.ml-item"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val title = document.selectFirst("div.mvi-content h3, h1.entry-title, h1")
            ?.text()
            ?.cleanTitle()
            .orEmpty()
        val poster = document.selectFirst("div.mvic-thumb, div.thumb.mvic-thumb, img.mvi-cover, .mvi-cover")
            .extractPoster()
        val tags = document.select("div.mvic-info p:contains(Genre:) a, #mv-keywords a h5")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val actors = document.select("div.mvic-info p:contains(Actors:) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val description = document.selectFirst("div.desc, div[itemprop=description]")
            ?.text()
            ?.replace(Regex("^Nonton\\s+(Film|Series).*?\\|\\s*KITA\\s+NONTON", RegexOption.IGNORE_CASE), "")
            ?.trim()
        val year = document.selectFirst("meta[itemprop=datePublished]")?.attr("content")
            ?.take(4)
            ?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val rating = document.selectFirst(".imdb-rating .rating, .irank-voters, .mli-rating")
            ?.text()
            ?.replace(",", ".")
            ?.trim()
            ?.toDoubleOrNull()
        val recommendations = document.select("div#mv-label a.keywordss, div.ml-item")
            .mapNotNull { it.toRecommendation() }
            .distinctBy { it.url }

        val isSeries = url.contains("/series/", ignoreCase = true) ||
            document.selectFirst("a[href$=/watch], a[href*=/watch]") != null ||
            tags.any { it.contains("series", true) || it.contains("drama", true) || it.contains("anime", true) }

        if (isSeries) {
            val watchUrl = document.selectFirst("a[href$=/watch], a[href*=/watch]")
                ?.attr("href")
                ?.let(::fixUrl)
                ?: url
            val detailEpisodes = extractEpisodes(document, watchUrl)
            val episodes = detailEpisodes
                .takeUnless { it.isWatchPlaceholder(watchUrl) }
                ?: extractEpisodes(app.get(watchUrl, referer = url).document, watchUrl)
                .ifEmpty {
                    listOf(
                        newEpisode(watchUrl) {
                            this.name = "Episode 1"
                            this.episode = 1
                            this.season = 1
                        }
                    )
                }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addActors(actors)
                this.recommendations = recommendations
                rating?.let { this.score = Score.from10(it) }
            }
        }

        val playUrl = document.selectFirst("a[href$=/play], a[href*=/play]")
            ?.attr("href")
            ?.let(::fixUrl)
            ?: url

        return newMovieLoadResponse(title, playUrl, TvType.Movie, playUrl) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addActors(actors)
            this.recommendations = recommendations
            rating?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.substringBefore("#")
        val response = app.get(cleanData, referer = getBaseUrl(cleanData))
        val document = response.document
        val html = response.text
        val referer = response.url
        val pageBaseUrl = extractBaseUrl(html).ifBlank { mainUrl }
        val movieId = extractMovieId(html)
        val requestedEpisode = extractRequestedEpisode(data)
        val isSeries = html.contains("load_episode_iframe(", true) || cleanData.contains("/watch", true)

        val directUrls = linkedSetOf<String>()
        val extractorUrls = linkedSetOf<String>()

        suspend fun addCandidate(raw: String?, candidateReferer: String = referer) {
            val candidate = raw
                ?.trim()
                ?.removeSurrounding("\"")
                ?.removeSurrounding("'")
                ?.takeIf { it.isNotBlank() }
                ?: return

            val fixed = when {
                candidate.startsWith("//") -> "https:$candidate"
                candidate.startsWith("/") -> fixUrl(candidate)
                candidate.startsWith("http") -> candidate
                else -> return
            }.substringBefore("#")

            if (fixed.contains("youtube.com", true) || fixed.contains("youtu.be", true)) return
            if (fixed.contains("/wp-content/", true) || fixed.contains("/wp-json/", true)) return

            if (fixed.isVideoLike()) {
                directUrls += fixed
                return
            }

            if (fixed.looksLikeExtractorUrl()) {
                val embedded = extractEmbeddedUrl(fixed, candidateReferer)
                if (embedded != null && embedded != fixed) {
                    extractorUrls += embedded
                } else {
                    extractorUrls += fixed
                }
            }
        }

        extractPlayerUrls(document, isSeries, pageBaseUrl, movieId, requestedEpisode).forEach { url ->
            addCandidate(url, referer)
        }

        if (directUrls.isEmpty() && extractorUrls.isEmpty()) {
            document.select(
                "iframe[src], iframe[data-src], video[src], video source[src], source[src], a[href]"
            ).forEach { el ->
                addCandidate(
                    el.attr("abs:src").ifBlank {
                        el.attr("src").ifBlank {
                            el.attr("data-src").ifBlank {
                                el.attr("abs:href").ifBlank { el.attr("href") }
                            }
                        }
                    }
                )
            }

            Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value }
                .forEach { addCandidate(it) }
        }

        directUrls.forEachIndexed { index, link ->
            callback(
                newExtractorLink(
                    name,
                    "$name ${index + 1}",
                    link,
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                        ?: Qualities.Unknown.value
                }
            )
        }

        extractorUrls
            .filterNot { it == data }
            .forEach { link ->
                runCatching {
                    loadExtractor(link, referer, subtitleCallback, callback)
                }
            }

        return directUrls.isNotEmpty() || extractorUrls.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href].ml-mask, h2 a[href], h3 a[href], a[href]") ?: return null
        val href = anchor.attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        val title = listOf(
            anchor.attr("title").trim(),
            selectFirst("h2, h3")?.text()?.trim().orEmpty(),
            selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty(),
        ).firstOrNull { it.isNotBlank() }?.cleanTitle() ?: return null

        val poster = selectFirst("img").extractPoster()
        val quality = selectFirst(".mli-quality, .quality")?.text()?.trim()
        val rating = selectFirst(".mli-rating, .rating")?.text()
            ?.replace(",", ".")
            ?.trim()
            ?.toDoubleOrNull()
        val isSeries = href.contains("/series/", true) || selectFirst(".mli-eps") != null

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun Element.toRecommendation(): SearchResponse? {
        return when {
            tagName().equals("a", true) -> {
                val href = attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
                val title = attr("title").ifBlank { text() }.cleanTitle()
                if (title.isBlank()) return null
                newMovieSearchResponse(title, href, TvType.Movie)
            }
            else -> toSearchResult()
        }
    }

    private fun extractEpisodes(document: Document, fallbackWatchUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val links = linkedMapOf<String, Pair<String, Pair<Int?, Int?>>>()

        document.select(
            "a.btn-eps, #list-eps a, [id^=episode-], a[href*='/episode/'], a[href*='ep-'], a[href*='/watch/']"
        ).forEachIndexed { index, el ->
            val label = el.text().cleanTitle().ifBlank { "Episode ${index + 1}" }
            val rawHref = el.attr("href").trim().takeIf {
                it.isNotBlank() && !it.equals("javascript:void(0);", true) && !it.equals("javascript:void(0)", true)
            }
            val season = Regex("""season\D+(\d+)""", RegexOption.IGNORE_CASE).find(label)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""season\D+(\d+)""", RegexOption.IGNORE_CASE).find(rawHref.orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val episode = Regex("""load_(?:movie|episode)_iframe\(\s*\d+\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
                .find(el.attr("onclick"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?:episode|ep)\D*(\d+)""", RegexOption.IGNORE_CASE).find(label)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""episode-(\d+)(\d+)""", RegexOption.IGNORE_CASE).find(el.id())
                    ?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(el.id())
                    ?.groupValues?.getOrNull(1)
                    ?.drop(1)
                    ?.toIntOrNull()
                ?: Regex("""(?:episode|ep)[-_/ ]?(\d+)""", RegexOption.IGNORE_CASE).find(rawHref.orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (index + 1)
            val href = rawHref?.let(::fixUrl)
                ?: "$fallbackWatchUrl?episode=$episode"
            val insideMain = href.contains(mainUrl, ignoreCase = true) || href.startsWith(mainUrl)
            if (!insideMain) return@forEachIndexed
            links[href] = label to (season to episode)
        }

        if (links.isEmpty() && fallbackWatchUrl != mainUrl) {
            links[fallbackWatchUrl] = "Episode 1" to (1 to 1)
        }

        return links.map { (href, data) ->
            val (label, numbers) = data
            newEpisode(href) {
                this.name = label
                this.season = numbers.first
                this.episode = numbers.second
            }
        }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))
    }

    private fun List<com.lagradost.cloudstream3.Episode>.isWatchPlaceholder(watchUrl: String): Boolean {
        if (isEmpty()) return true
        if (size > 1) return false

        val episode = first()
        val normalizedData = episode.data.substringBefore("#")

        return normalizedData == watchUrl && (episode.episode ?: 1) == 1
    }

    private fun fixPagedUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http")) path else "$mainUrl/${path.trimStart('/')}"
        return normalized.replace("%d", page.toString())
    }

    private fun Element?.extractPoster(): String? {
        if (this == null) return null
        val raw = when {
            hasAttr("data-original") -> attr("data-original")
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("src") -> attr("src")
            else -> attr("style").substringAfter("url(").substringBefore(")")
        }.trim().removeSurrounding("\"").removeSurrounding("'")

        return fixUrlNull(raw.removeImageSizeSuffix())
    }

    private fun String.cleanTitle(): String = this
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""(?i)^nonton\s+(film|series|anime|drama)\s+"""), "")
        .replace(Regex("""(?i)\s+sub\s+indo(?:nesia)?\s*$"""), "")
        .trim()

    private fun String.removeImageSizeSuffix(): String =
        replace(Regex("-\\d+x\\d+(?=\\.(jpg|jpeg|png|webp)$)", RegexOption.IGNORE_CASE), "")

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("http")) return url
        return "$mainUrl/${url.trimStart('/')}"
    }

    private fun fixUrlNull(url: String?): String? = url?.let(::fixUrl)

    private fun String.isVideoLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.endsWith(".m3u8") ||
            lower.endsWith(".mp4") ||
            lower.endsWith(".mkv") ||
            lower.contains(".m3u8?") ||
            lower.contains(".mp4?")
    }

    private fun String.looksLikeExtractorUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        val host = runCatching { URI(this).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        if (host.isBlank()) return false
        if (host.contains("kitanonton2.guru") && !lower.contains("/play") && !lower.contains("/watch")) return false

        return lower.contains("/embed") ||
            lower.contains("/player") ||
            lower.contains("/stream") ||
            lower.contains("/video") ||
            lower.contains("/play") ||
            host.contains("filemoon") ||
            host.contains("streamtape") ||
            host.contains("dood") ||
            host.contains("vid") ||
            host.contains("mixdrop") ||
            host.contains("uqload") ||
            host.contains("streamwish")
    }

    private fun getBaseUrl(url: String): String {
        val uri = URI(url)
        return "${uri.scheme}://${uri.host}"
    }

    private suspend fun extractPlayerUrls(
        document: Document,
        isSeries: Boolean,
        baseUrl: String,
        movieId: String?,
        requestedEpisode: Int?
    ): Set<String> {
        val urls = linkedSetOf<String>()
        val pattern = Regex(
            """id=["']episode-(\d+)(\d+)["'][^>]*?(?:data-drive|data-mp4|data-strgo|data-iframe|data-openload)=["'][^"']+["'][^>]*""",
            RegexOption.IGNORE_CASE
        )

        document.select("[id^=episode-]").forEach { element ->
            buildInternalPlayerUrl(element.outerHtml(), isSeries, baseUrl, movieId, requestedEpisode)?.let(urls::add)
        }

        if (urls.isEmpty()) {
            pattern.findAll(document.outerHtml()).forEach { match ->
                buildInternalPlayerUrl(match.value, isSeries, baseUrl, movieId, requestedEpisode)?.let(urls::add)
            }
        }

        return urls
    }

    private fun buildInternalPlayerUrl(
        raw: String,
        isSeries: Boolean,
        baseUrl: String,
        movieId: String?,
        requestedEpisode: Int?
    ): String? {
        val server = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val episode = Regex("""load_(?:movie|episode)_iframe\(\s*\d+\s*,\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: server.drop(1).ifBlank { "1" }
        if (requestedEpisode != null && episode.toIntOrNull() != requestedEpisode) return null

        val drive = Regex("""data-drive=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)
        val mp4 = Regex("""data-mp4=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)
        val strgo = Regex("""data-strgo=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)
        val iframe = Regex("""data-iframe=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)
        val openload = Regex("""data-openload=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)

        val seriesType = if (isSeries) "type=series&" else ""
        val movieQuery = movieId?.let { "&id=$it" }.orEmpty()
        val episodeQuery = if (isSeries) "&ep=$episode" else ""

        return when (server) {
            "10" -> drive?.let { "$baseUrl/googledrive/?${seriesType}source=$it$movieQuery$episodeQuery" }
            "8" -> drive?.let { "$baseUrl/gdrive/?${seriesType}url=$it$movieQuery$episodeQuery" }
            "7" -> mp4?.let { "$baseUrl/player/?${seriesType}source=$it$movieQuery$episodeQuery" }
            "6" -> strgo?.let { "$baseUrl/stremagoembed/?${seriesType}source=$it" }
            "2" -> iframe?.let { "$baseUrl/iembed/?source=$it" }
            "1" -> openload?.let { "$baseUrl/openloadembed/?${seriesType}source=$it" }
            else -> iframe?.let { "$baseUrl/iembed/?source=$it" }
                ?: drive?.let { "$baseUrl/googledrive/?${seriesType}source=$it$movieQuery$episodeQuery" }
                ?: mp4?.let { "$baseUrl/player/?${seriesType}source=$it$movieQuery$episodeQuery" }
        }
    }

    private suspend fun extractEmbeddedUrl(url: String, referer: String): String? {
        val response = app.get(url, referer = referer)
        val document = response.document
        return document.selectFirst("iframe[src], iframe[data-src]")
            ?.let { iframe ->
                iframe.attr("abs:src").ifBlank {
                    iframe.attr("src").ifBlank { iframe.attr("data-src") }
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?.let(::fixUrl)
    }

    private fun extractBaseUrl(html: String): String =
        Regex("""base_url\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

    private fun extractMovieId(html: String): String? =
        Regex("""movieid\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)

    private fun extractRequestedEpisode(data: String): Int? =
        Regex("""[?&](?:episode|ep)=(\d+)""", RegexOption.IGNORE_CASE)
            .find(data)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
}
