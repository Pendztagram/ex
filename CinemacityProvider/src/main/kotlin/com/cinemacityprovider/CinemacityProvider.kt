package com.cinemacityprovider

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CinemacityProvider : MainAPI() {
    override var mainUrl = "https://cinemacity.cc"
    override var name = "Cinemacity"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/%d/" to "Movie",
        "$mainUrl/tv-series/page/%d/" to "TV Series",
        "$mainUrl/xfsearch/genre/anime/page/%d/" to "Anime",
        "$mainUrl/xfsearch/genre/asian/page/%d/" to "Asian",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val document = app.get(url).document
        val home = document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.post(
            "$mainUrl/index.php",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query,
            ),
        ).document

        val results = document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return results.toNewSearchResponseList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a[href*=\"/movies/\"], a[href*=\"/tv-series/\"]")?.attr("href") ?: return null
        val title = selectFirst("a.e-nowrap")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img.poster")?.attr("src"))
        val year = Regex("\\((\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return if (href.contains("/tv-series/")) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("div.dar-full_poster img")?.attr("src"))
        val background = fixUrlNull(document.selectFirst("div.dar-full_bg img")?.attr("src"))
        val plot = document.selectFirst("div.ta-full_text1")?.text()?.trim()
        val tags = document.select("div.ta-full_meta a[href*=\"/xfsearch/genre/\"]").map { it.text().trim() }.distinct()
        val year = Regex("\\((\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val trailer = document.selectFirst("a[href*=\"youtube.com/watch\"]")?.attr("href")
        val recs = document.select("div.ta-rel_item").mapNotNull { it.toRecommendation() }

        val isSeries = url.contains("/tv-series/")
        return if (isSeries) {
            val episodes = extractEpisodes(document, url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                addTrailer(trailer)
                this.recommendations = recs
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, LinkData(url).toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                addTrailer(trailer)
                this.recommendations = recs
            }
        }
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seen = hashSetOf<String>()
        val regex = Regex("S(\\d+)E(\\d+)", RegexOption.IGNORE_CASE)

        document.select("div#download div.dar-tr_title > div").forEach { row ->
            val text = row.text().trim()
            val match = regex.find(text) ?: return@forEach
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            val key = "${season}_${episode}"
            if (!seen.add(key)) return@forEach

            episodes.add(
                newEpisode(LinkData(url).toJson()) {
                    this.name = "Episode $episode"
                    this.season = season
                    this.episode = episode
                }
            )
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(LinkData(url).toJson()) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.season = 1
                }
            )
        }
        return episodes
    }

    private fun Element.toRecommendation(): SearchResponse? {
        val href = selectFirst("a.e-nowrap")?.attr("href") ?: return null
        val title = selectFirst("a.e-nowrap")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img.poster")?.attr("src"))
        return if (href.contains("/tv-series/")) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = AppUtils.parseJson<LinkData>(data)
        val document = app.get(parsed.url).document
        val html = document.html()

        val rawCandidates = mutableListOf<String>()
        rawCandidates += Regex("""file\s*:\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()
        rawCandidates += Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html).map { it.value }.toList()
        rawCandidates += Regex("""https?://[^\s"'<>]+""").findAll(html).map { it.value }.filter {
            it.contains("stream", true) || it.contains("embed", true) || it.contains("m3u8", true)
        }.toList()
        val candidates = rawCandidates.map { fixUrl(it) }.distinct()

        var found = false
        candidates.forEach { link ->
            try {
                if (link.contains(".m3u8")) {
                    generateM3u8(name, link, parsed.url).forEach(callback)
                    found = true
                } else {
                    loadExtractor(link, parsed.url, subtitleCallback, callback)
                    found = true
                }
            } catch (_: Throwable) {
            }
        }
        return found
    }
}

data class LinkData(
    val url: String,
)
