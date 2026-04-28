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
                LinkData(tmdbId = media.id, imdbId = detail.externalIds?.imdbId, type = "movie").toJson()
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
                val doc = res.document

                val picked = listOfNotNull(
                    doc.selectFirst("iframe[src]")?.attr("abs:src"),
                    doc.selectFirst("iframe[data-src]")?.attr("abs:data-src"),
                    doc.selectFirst("video source[src], source[src]")?.attr("abs:src"),
                    doc.selectFirst("video[src]")?.attr("abs:src"),
                    Regex("""https?://[^"'\\s]+\\.(?:m3u8|mp4)[^"'\\s]*""", RegexOption.IGNORE_CASE)
                        .find(doc.select("script").joinToString("\n") { it.data() })?.value,
                ).map { it?.trim().orEmpty() }
                    .map { if (it.startsWith("//")) "https:$it" else it }
                    .firstOrNull { it.startsWith("http", true) }
                    ?: continue

                when {
                    picked.contains(".m3u8", true) -> {
                        M3u8Helper.generateM3u8("Yflix", picked, "$yflixApi/").forEach(::emitLink)
                        return emitted.isNotEmpty()
                    }

                    picked.contains(".mp4", true) -> {
                        emitLink(
                            newExtractorLink("Yflix", "Yflix", picked, INFER_TYPE) {
                                referer = "$yflixApi/"
                            }
                        )
                        return emitted.isNotEmpty()
                    }

                    else -> {
                        loadExtractor(picked, "$yflixApi/", subtitleCallback, ::emitLink)
                        if (emitted.isNotEmpty()) return true
                    }
                }
            }
        }

        return emitted.isNotEmpty()
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
                        episode = episodeNumber
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

    data class SiteMedia(val type: String, val id: Int)

    data class LinkData(
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
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
