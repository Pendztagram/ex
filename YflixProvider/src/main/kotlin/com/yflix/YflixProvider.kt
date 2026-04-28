package com.yflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class YflixProvider : MainAPI() {
    override var mainUrl = "https://www.themoviedb.org"
    private val yflixApi = "https://yflix.to"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"

    override var name = "Yflix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/movie/day?api_key=$tmdbApiKey" to "Trending Movies",
        "$tmdbApi/movie/popular?api_key=$tmdbApiKey" to "Popular Movies",
        "$tmdbApi/movie/now_playing?api_key=$tmdbApiKey" to "Now Playing Movies",
        "$tmdbApi/trending/tv/day?api_key=$tmdbApiKey" to "Trending TV",
        "$tmdbApi/tv/popular?api_key=$tmdbApiKey" to "Popular TV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val response = app.get(url).parsedSafe<TmdbResults>() ?: throw ErrorLoadingException("Invalid response")
        val items = response.results.orEmpty().mapNotNull { it.toSearchResponse() }
        val hasNext = (response.page ?: 1) < (response.totalPages ?: 1)
        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$tmdbApi/search/multi?api_key=$tmdbApiKey&query=${query.urlEncoded()}&page=1"
        return app.get(url).parsedSafe<TmdbResults>()?.results.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val media = parseSiteMediaUrl(url)
        val detailUrl = when (media.type) {
            "movie" -> "$tmdbApi/movie/${media.id}?api_key=$tmdbApiKey&append_to_response=external_ids,recommendations,videos"
            else -> "$tmdbApi/tv/${media.id}?api_key=$tmdbApiKey&append_to_response=external_ids,recommendations,videos"
        }

        val detail = app.get(detailUrl).parsedSafe<TmdbDetail>() ?: throw ErrorLoadingException("Invalid detail")
        val title = detail.title ?: detail.name ?: throw ErrorLoadingException("Missing title")

        val trailer = detail.videos?.results.orEmpty().firstOrNull {
            it.site.equals("YouTube", true) && !it.key.isNullOrBlank()
        }?.key?.let { "https://www.youtube.com/watch?v=$it" }

        return if (media.type == "movie") {
            newMovieLoadResponse(
                title,
                "$mainUrl/movie/${media.id}",
                TvType.Movie,
                LinkData(
                    tmdbId = media.id,
                    imdbId = detail.externalIds?.imdbId,
                    type = "movie",
                    title = title,
                    year = detail.releaseDate?.substringBefore("-")?.toIntOrNull()
                ).toJson()
            ) {
                this.posterUrl = detail.posterPath.toPosterUrl()
                this.plot = detail.overview
                this.year = detail.releaseDate?.substringBefore("-")?.toIntOrNull()
                detail.voteAverage?.let { this.score = Score.from10(it) }
                this.tags = detail.genres.orEmpty().mapNotNull { it.name }
                trailer?.let { addTrailer(it, addRaw = true) }
            }
        } else {
            val episodes = buildEpisodes(media.id, detail.externalIds?.imdbId)
            newTvSeriesLoadResponse(
                title,
                "$mainUrl/tv/${media.id}",
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = detail.posterPath.toPosterUrl()
                this.plot = detail.overview
                this.year = detail.firstAirDate?.substringBefore("-")?.toIntOrNull()
                detail.voteAverage?.let { this.score = Score.from10(it) }
                this.tags = detail.genres.orEmpty().mapNotNull { it.name }
                trailer?.let { addTrailer(it, addRaw = true) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = tryParseJson<LinkData>(data) ?: return false
        val ids = listOfNotNull(info.tmdbId?.toString(), info.imdbId).distinct()
        if (ids.isEmpty()) return false

        val patterns = listOf(
            if (info.season == null) "embed/movie/%s" else "embed/tv/%s/${info.season}/${info.episode}",
            if (info.season == null) "watch/movie/%s" else "watch/tv/%s/${info.season}/${info.episode}",
            if (info.season == null) "movie/%s" else "tv/%s/${info.season}/${info.episode}",
        )

        val emitted = mutableSetOf<String>()

        fun emitLink(link: ExtractorLink) {
            val key = "${link.name}|${link.url}"
            if (emitted.add(key)) callback(link)
        }

        for (id in ids) {
            for (pattern in patterns) {
                val url = "$yflixApi/${pattern.format(id)}"
                val res = runCatching { app.get(url) }.getOrNull() ?: continue
                val picked = extractPlayableUrl(res.text, res.document) ?: continue
                if (tryEmitPicked(picked, "$yflixApi/", subtitleCallback, ::emitLink, emitted)) return true
            }
        }

        for (watchUrl in resolveWatchCandidates(info)) {
            val res = runCatching { app.get(watchUrl, referer = "$yflixApi/") }.getOrNull() ?: continue
            val picked = extractPlayableUrl(res.text, res.document) ?: continue
            if (tryEmitPicked(picked, watchUrl, subtitleCallback, ::emitLink, emitted)) return true
        }

        val fallbackEmbeds = buildList {
            info.tmdbId?.let { tmdb ->
                if (info.season == null) {
                    add("https://vidsrc.to/embed/movie/$tmdb")
                    add("https://vidsrc.xyz/embed/movie/$tmdb")
                } else {
                    add("https://vidsrc.to/embed/tv/$tmdb/${info.season}/${info.episode}")
                    add("https://vidsrc.xyz/embed/tv/$tmdb/${info.season}/${info.episode}")
                }
            }
            info.imdbId?.let { imdb ->
                if (info.season == null) {
                    add("https://vidsrc.xyz/embed/movie?imdb=$imdb")
                } else {
                    add("https://vidsrc.xyz/embed/tv?imdb=$imdb&season=${info.season}&episode=${info.episode}")
                }
            }
        }.distinct()

        for (embed in fallbackEmbeds) {
            runCatching {
                loadExtractor(embed, yflixApi, subtitleCallback, ::emitLink)
            }
            if (emitted.isNotEmpty()) return true
        }

        return emitted.isNotEmpty()
    }

    private suspend fun tryEmitPicked(
        picked: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: Set<String>
    ): Boolean {
        when {
            picked.contains(".m3u8", true) -> {
                M3u8Helper.generateM3u8("Yflix", picked, "$yflixApi/").forEach(callback)
                return emitted.isNotEmpty()
            }

            picked.contains(".mp4", true) -> {
                callback(
                    newExtractorLink("Yflix", "Yflix", picked, INFER_TYPE) {
                        this.referer = referer
                    }
                )
                return emitted.isNotEmpty()
            }

            else -> {
                runCatching {
                    loadExtractor(picked, referer, subtitleCallback, callback)
                }
                return emitted.isNotEmpty()
            }
        }
    }

    private suspend fun resolveWatchCandidates(info: LinkData): List<String> {
        val title = info.title?.takeIf { it.isNotBlank() } ?: return emptyList()
        val doc = runCatching {
            app.get("$yflixApi/filter?keyword=${title.urlEncoded()}").document
        }.getOrNull() ?: return emptyList()

        val target = normalizeTitle(title)
        return doc.select("a[href*=/watch/]")
            .mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (href.isBlank()) return@mapNotNull null
                val cardTitle = a.attr("title")
                    .ifBlank { a.selectFirst(".title")?.text().orEmpty() }
                    .ifBlank { a.text() }
                Triple(href, normalizeTitle(cardTitle), cardTitle)
            }
            .filter { (href, _, _) -> href.contains("/watch/") }
            .sortedBy { (_, norm, _) ->
                when {
                    norm == target -> 0
                    norm.contains(target) || target.contains(norm) -> 1
                    else -> 2 + kotlin.math.abs(norm.length - target.length)
                }
            }
            .map { it.first }
            .distinct()
            .take(8)
    }

    private fun extractPlayableUrl(html: String, doc: org.jsoup.nodes.Document): String? {
        val scripts = doc.select("script").joinToString("\n") { it.data() } + "\n" + html
        return listOfNotNull(
            doc.selectFirst("iframe[src]")?.attr("abs:src"),
            doc.selectFirst("iframe[data-src]")?.attr("abs:data-src"),
            doc.selectFirst("video source[src], source[src]")?.attr("abs:src"),
            doc.selectFirst("video[src]")?.attr("abs:src"),
            Regex("""https?://[^"'\\s]+\\.(?:m3u8|mp4)[^"'\\s]*""", RegexOption.IGNORE_CASE).find(scripts)?.value,
            Regex("""https?://[^"'\\s]+/(?:embed|watch)/[^"'\\s]+""", RegexOption.IGNORE_CASE).find(scripts)?.value,
        ).map { it.trim() }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .firstOrNull { it.startsWith("http", true) }
    }

    private suspend fun buildEpisodes(tmdbId: Int, imdbId: String?): List<Episode> {
        val detail = app.get("$tmdbApi/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetail>() ?: return emptyList()
        val allEpisodes = mutableListOf<Episode>()

        detail.seasons.orEmpty().forEach { season ->
            val seasonNumber = season.seasonNumber ?: return@forEach
            val seasonData = app.get("$tmdbApi/tv/$tmdbId/season/$seasonNumber?api_key=$tmdbApiKey")
                .parsedSafe<TmdbSeasonDetail>() ?: return@forEach

            seasonData.episodes.orEmpty().forEach { episode ->
                val episodeNumber = episode.episodeNumber ?: return@forEach
                allEpisodes += newEpisode(
                    LinkData(
                        tmdbId = tmdbId,
                        imdbId = imdbId,
                        type = "tv",
                        season = seasonNumber,
                        episode = episodeNumber,
                        title = detail.name,
                        year = detail.firstAirDate?.substringBefore("-")?.toIntOrNull()
                    ).toJson()
                ) {
                    this.name = episode.name ?: "Episode $episodeNumber"
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = episode.stillPath.toPosterUrl()
                    this.description = episode.overview
                }
            }
        }

        return allEpisodes
    }

    private fun buildPagedUrl(url: String, page: Int): String {
        if (page <= 1) return url
        val joiner = if (url.contains("?")) "&" else "?"
        return "$url${joiner}page=$page"
    }

    private fun parseSiteMediaUrl(url: String): SiteMedia {
        val clean = url.substringBefore("?").trimEnd('/')
        val parts = clean.split("/")
        val type = parts.getOrNull(parts.lastIndex - 1) ?: throw ErrorLoadingException("Invalid type")
        val id = parts.lastOrNull()?.toIntOrNull() ?: throw ErrorLoadingException("Invalid ID")
        return SiteMedia(type = type, id = id)
    }

    private fun TmdbMedia.toSearchResponse(): SearchResponse? {
        val mediaType = mediaType ?: when {
            !title.isNullOrBlank() -> "movie"
            !name.isNullOrBlank() -> "tv"
            else -> null
        } ?: return null

        val id = id ?: return null
        val resolvedTitle = title ?: name ?: return null
        val year = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()

        return if (mediaType == "movie") {
            newMovieSearchResponse(resolvedTitle, "$mainUrl/movie/$id", TvType.Movie) {
                posterUrl = posterPath.toPosterUrl()
                this.year = year
                voteAverage?.let { this.score = Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(resolvedTitle, "$mainUrl/tv/$id", TvType.TvSeries) {
                posterUrl = posterPath.toPosterUrl()
                this.year = year
                voteAverage?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun String?.toPosterUrl(): String? {
        if (this.isNullOrBlank()) return null
        return if (startsWith('/')) "https://image.tmdb.org/t/p/w500/$this" else this
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private fun normalizeTitle(value: String): String {
        return value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    data class SiteMedia(val type: String, val id: Int)

    data class LinkData(
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
    )

    data class TmdbResults(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("results") val results: List<TmdbMedia>? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
    )

    data class TmdbMedia(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class TmdbDetail(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("genres") val genres: List<TmdbGenre>? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
    )

    data class TmdbGenre(@JsonProperty("name") val name: String? = null)

    data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String? = null)

    data class TmdbVideo(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
    )

    data class TmdbVideos(@JsonProperty("results") val results: List<TmdbVideo>? = null)

    data class TmdbSeason(@JsonProperty("season_number") val seasonNumber: Int? = null)

    data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)

    data class TmdbEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
    )
}
