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
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/%d/" to "Movie",
        "$mainUrl/tv-series/page/%d/" to "TV Series",
        "filter://anime/%d" to "Anime",
        "filter://asian/%d" to "Asian",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val home = when {
            data.startsWith("filter://anime/") -> getFilteredByGenre(page, "anime")
            data.startsWith("filter://asian/") -> getFilteredByGenre(page, "asian")
            else -> {
                val url = if (data.contains("%d")) data.format(page) else data
                val document = app.get(url).document
                document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
            }
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getFilteredByGenre(page: Int, genre: String): List<SearchResponse> {
        val movieDoc = app.get("$mainUrl/movies/page/$page/").document
        val seriesDoc = app.get("$mainUrl/tv-series/page/$page/").document
        val cards = movieDoc.select("div.dar-short_item") + seriesDoc.select("div.dar-short_item")
        return cards.mapNotNull { it.toSearchResultByGenre(genre) }
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

    private fun Element.toSearchResultByGenre(genre: String): SearchResponse? {
        val genresText = selectFirst("div.dar-short_meta")?.text()?.lowercase().orEmpty()
        if (!genresText.contains(genre.lowercase())) return null
        return toSearchResult()
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
        val out = linkedMapOf<String, Episode>()

        fun addEpisode(season: Int?, episode: Int?, name: String? = null) {
            if (episode == null) return
            val key = "${season ?: 1}_$episode"
            if (out.containsKey(key)) return
            out[key] = newEpisode(LinkData(url).toJson()) {
                this.name = name ?: "Episode $episode"
                this.season = season ?: 1
                this.episode = episode
            }
        }

        // 1) Direct selectors in section download (closest to site structure)
        val seasons = document.select("select[name=dar-dl_season] option")
            .mapNotNull { Regex("(\\d+)").find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .distinct()
        val eps = document.select("select[name=dar-dl_episode] option")
            .mapNotNull { Regex("(\\d+)").find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .distinct()
        if (seasons.isNotEmpty() && eps.isNotEmpty()) {
            seasons.forEach { s -> eps.forEach { e -> addEpisode(s, e) } }
        }

        // 2) Parse download filenames: FROM.S1E1.1080p...
        val fileRegex = Regex("S(\\d+)E(\\d+)", RegexOption.IGNORE_CASE)
        document.select("div#download div.dar-tr_title > div").forEach { row ->
            val text = row.text().trim()
            val match = fileRegex.find(text) ?: return@forEach
            val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
            addEpisode(season, episode, "Episode ${episode ?: "?"}")
        }

        // 3) Parse schedule block: "Episode 3 - May 3, 2026"
        val schedRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        document.select("div.ta-full_text1").forEach { block ->
            block.text().lineSequence().forEach { line ->
                val ep = schedRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
                addEpisode(1, ep)
            }
        }

        val episodes = out.values.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 })).toMutableList()
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
