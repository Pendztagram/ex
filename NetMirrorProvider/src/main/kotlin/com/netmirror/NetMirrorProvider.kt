package com.netmirror

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

class NetMirrorProvider : MainAPI() {
    override var mainUrl = "https://net22.cc"
    private val streamUrl = "https://net52.cc"
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    override var name = "NetMirror"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val ajaxHeaders = mapOf(
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "User-Agent" to browserUserAgent
    )

    override val mainPage = mainPageOf(
        "top" to "Top Searches",
        "tmdb_trending_movie" to "Trending Movies",
        "tmdb_trending_tv" to "Trending Series",
        "tmdb_popular_movie" to "Popular Movies",
        "tmdb_popular_tv" to "Popular Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(emptyList(), false)

        val lists = when (request.data) {
            "top" -> listOf(HomePageList(request.name, fetchTopSearches()))
            "tmdb_trending_movie" -> listOf(HomePageList(request.name, fetchTmdbSection("trending/movie/week", TvType.Movie)))
            "tmdb_trending_tv" -> listOf(HomePageList(request.name, fetchTmdbSection("trending/tv/week", TvType.TvSeries)))
            "tmdb_popular_movie" -> listOf(HomePageList(request.name, fetchTmdbSection("movie/popular", TvType.Movie)))
            "tmdb_popular_tv" -> listOf(HomePageList(request.name, fetchTmdbSection("tv/popular", TvType.TvSeries)))
            else -> emptyList()
        }.filter { it.list.isNotEmpty() }

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val payload = app.get(
            "$mainUrl/search.php?s=${query.urlEncoded()}&t=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).parsedSafe<SearchData>() ?: return emptyList()

        return payload.searchResult.map { item ->
            newMovieSearchResponse(
                item.t,
                LoadPayload(item.id, item.t).toJson(),
                TvType.Movie
            ) {
                this.posterUrl = posterUrl(item.id)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val payload = tryParseJson<LoadPayload>(url) ?: throw ErrorLoadingException("Invalid NetMirror payload")
        val resolvedId = payload.id ?: resolveNetMirrorId(payload.title)
        val postData = resolvedId?.let { fetchPostData(it) }
        if (postData != null) {
            return buildDetailedLoad(url, payload.copy(id = resolvedId), postData)
        }

        val modalData = resolvedId?.let { fetchMiniModal(it) }
        val tmdb = fetchTmdbMetadata(payload.title, payload.tmdbType, payload.year)
        val fallbackId = resolvedId ?: payload.id
        return newMovieLoadResponse(payload.title, url, TvType.Movie, payload.copy(id = resolvedId).toJson()) {
            this.posterUrl = fallbackId?.let(::posterUrl) ?: tmdb?.posterPath.toTmdbPosterUrl()
            this.backgroundPosterUrl = fallbackId?.let(::backgroundPosterUrl) ?: tmdb?.backdropPath.toTmdbBackdropUrl()
            this.plot = tmdb?.overview ?: "Plot tidak ditemukan dari endpoint publik NetMirror."
            this.tags = modalData?.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: tmdb?.genres?.mapNotNull { it.name?.trim() }?.filter { it.isNotBlank() }
            this.contentRating = modalData?.ua
            this.year = tmdb?.yearOrNull() ?: payload.year
            (modalData?.match.toScoreOrNull() ?: tmdb?.voteAverage?.let { Score.from10(it) })?.let { this.score = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<LoadPayload>(data) ?: return false
        val resolvedId = payload.id ?: resolveNetMirrorId(payload.title) ?: return false
        val playlist = fetchPlaylist(resolvedId, payload.title)
        if (playlist.isNullOrEmpty()) return false

        playlist.forEach { item ->
            item.tracks.orEmpty()
                .filter { it.kind.equals("captions", true) }
                .forEach { track ->
                    val file = track.file?.cleanJsonUrl() ?: return@forEach
                    val label = track.label?.takeIf { it.isNotBlank() } ?: "Subtitle"
                    subtitleCallback(
                        newSubtitleFile(label, file) {
                            this.headers = mapOf("Referer" to "$streamUrl/")
                        }
                    )
                }

            item.sources.forEach { source ->
                val path = source.file ?: return@forEach
                callback(
                    newExtractorLink(
                        name,
                        source.label ?: "Stream",
                        path.toAbsoluteStreamUrl(),
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$streamUrl/"
                        this.quality = source.label.toNetMirrorQuality()
                        this.headers = mapOf(
                            "Referer" to "$streamUrl/",
                            "User-Agent" to exoPlayerUserAgent,
                            "Accept" to "*/*",
                            "Accept-Encoding" to "identity",
                            "Connection" to "keep-alive"
                        )
                    }
                )
            }
        }

        return true
    }

    private suspend fun buildDetailedLoad(
        url: String,
        payload: LoadPayload,
        postData: PostData
    ): LoadResponse {
        val resolvedId = requireNotNull(payload.id) { "NetMirror id missing" }
        val title = postData.title ?: payload.title
        val plot = postData.desc
        val score = postData.match.toScoreOrNull()
        val tags = postData.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val cast = postData.cast?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.map { ActorData(Actor(it)) }
        val poster = posterUrl(resolvedId)
        val background = backgroundPosterUrl(resolvedId)
        val year = postData.year?.toIntOrNull()
        val contentRating = postData.ua
        val duration = postData.runtime.toMinutesOrNull()
        val postEpisodes = postData.episodes.orEmpty().filterNotNull()
        val isSeries = postData.type?.equals("m", true) == false || postEpisodes.isNotEmpty()

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, LoadPayload(payload.id, title).toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.year = year
                this.tags = tags
                this.actors = cast
                this.contentRating = contentRating
                this.duration = duration
                score?.let { this.score = it }
            }
        }

        val episodes = mutableListOf<Episode>()
        postEpisodes.mapTo(episodes) { episode ->
            newEpisode(LoadPayload(episode.id, title).toJson()) {
                this.name = episode.t ?: "Episode"
                this.posterUrl = episodePosterUrl(episode.id)
                this.episode = episode.ep?.removePrefix("E")?.toIntOrNull()
                this.season = episode.s?.removePrefix("S")?.toIntOrNull()
                this.runTime = episode.time?.removeSuffix("m")?.trim()?.toIntOrNull()
            }
        }

        postData.nextPageSeason?.let { seasonId ->
            if (postData.nextPageShow == 1) {
                episodes += fetchEpisodesPage(title, resolvedId, seasonId, 2)
            }
        }

        postData.season.orEmpty()
            .dropLast(1)
            .forEach { season ->
                val seasonId = season.id ?: return@forEach
                episodes += fetchEpisodesPage(title, resolvedId, seasonId, 1)
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.year = year
            this.tags = tags
            this.actors = cast
            this.contentRating = contentRating
            this.duration = duration
            score?.let { this.score = it }
        }
    }

    private suspend fun fetchTopSearches(): List<SearchResponse> {
        val payload = app.get(
            "$mainUrl/search.php?t=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).parsedSafe<SearchData>() ?: return emptyList()

        return payload.searchResult
            .take(12)
            .mapNotNull { item ->
                val title = item.t.takeIf { it.isNotBlank() } ?: resolveTitleFromPlaylist(item.id)
                title?.takeIf { it.isNotBlank() }?.let {
                    buildSearchResponse(
                        title = it,
                        payload = LoadPayload(item.id, it),
                        type = TvType.Movie,
                        poster = posterUrl(item.id)
                    )
                }
            }
    }

    private suspend fun fetchPostData(id: String): PostData? {
        return app.get(
            "$mainUrl/post.php?id=$id&t=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).parsedSafe<PostData>()
    }

    private suspend fun fetchMiniModal(id: String): MiniModalInfo? {
        return app.get(
            "$mainUrl/mini-modal-info.php?pid=$id&t=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).parsedSafe<MiniModalInfo>()
    }

    private suspend fun fetchTmdbSection(path: String, type: TvType): List<SearchResponse> {
        val response = app.get("$tmdbApi/$path?api_key=$tmdbApiKey").parsedSafe<TmdbListResponse>() ?: return emptyList()
        return response.results.orEmpty()
            .take(12)
            .mapNotNull { item ->
                val title = item.title.orEmpty().ifBlank { item.name.orEmpty() }
                if (title.isBlank()) return@mapNotNull null
                buildSearchResponse(
                    title = title,
                    payload = LoadPayload(
                        id = null,
                        title = title,
                        tmdbId = item.id,
                        tmdbType = if (type == TvType.TvSeries) "tv" else "movie",
                        year = item.yearOrNull(),
                        poster = item.posterPath.toTmdbPosterUrl()
                    ),
                    type = type,
                    poster = item.posterPath.toTmdbPosterUrl(),
                    year = item.yearOrNull(),
                    score = item.voteAverage?.let { Score.from10(it) }
                )
            }
    }

    private suspend fun fetchEpisodesPage(
        title: String,
        seriesId: String,
        seasonId: String,
        startPage: Int
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        var page = startPage
        while (true) {
            val response = app.get(
                "$mainUrl/episodes.php?s=$seasonId&series=$seriesId&t=$unixTime&page=$page",
                headers = ajaxHeaders,
                referer = "$mainUrl/home"
            ).parsedSafe<EpisodesPage>()
                ?: break

            response.episodes.orEmpty().forEach { episode ->
                val episodeId = episode.id ?: return@forEach
                episodes += newEpisode(LoadPayload(episodeId, title).toJson()) {
                    this.name = episode.t ?: "Episode"
                    this.posterUrl = episodePosterUrl(episodeId)
                    this.episode = episode.ep?.removePrefix("E")?.toIntOrNull()
                    this.season = episode.s?.removePrefix("S")?.toIntOrNull()
                    this.runTime = episode.time?.removeSuffix("m")?.trim()?.toIntOrNull()
                }
            }

            if (response.nextPageShow != 1) break
            page += 1
        }
        return episodes
    }

    private suspend fun fetchPlaylist(id: String, title: String): List<PlaylistItem>? {
        val postToken = app.post(
            "$mainUrl/play.php",
            headers = ajaxHeaders,
            referer = "$mainUrl/",
            data = mapOf("id" to id)
        ).parsedSafe<PlayToken>()?.h
        val stageTwoToken = postToken?.let { fetchStageTwoToken(id, it) }
        if (stageTwoToken != null) {
            val validated = app.get(
                "$streamUrl/playlist.php?id=$id&t=${title.urlEncoded()}&h=${stageTwoToken.urlEncoded()}&tm=$unixTime",
                headers = ajaxHeaders,
                referer = "$mainUrl/"
            ).parsedSafe<Array<PlaylistItem>>()?.toList()
            if (!validated.isNullOrEmpty() && isPlayablePlaylist(validated)) return validated
        }

        val direct = app.get(
            "$streamUrl/playlist.php?id=$id&t=${title.urlEncoded()}&h=x&tm=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/"
        ).parsedSafe<Array<PlaylistItem>>()?.toList()

        return direct?.takeIf { it.isNotEmpty() && isPlayablePlaylist(it) }
    }

    private suspend fun fetchStageTwoToken(id: String, token: String): String? {
        val doc = app.get(
            "$streamUrl/play.php?id=$id&$token",
            headers = iframeHeaders,
            referer = "$mainUrl/"
        ).document
        return doc.selectFirst("body[data-h]")?.attr("data-h")?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveTitleFromPlaylist(id: String): String? {
        return app.get(
            "$streamUrl/playlist.php?id=$id&t=NetMirror&h=x&tm=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/"
        ).parsedSafe<Array<PlaylistItem>>()?.firstOrNull()?.title?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveNetMirrorId(title: String): String? {
        return app.get(
            "$mainUrl/search.php?s=${title.urlEncoded()}&t=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).parsedSafe<SearchData>()?.searchResult?.firstOrNull()?.id
    }

    private suspend fun fetchTmdbMetadata(title: String, hintType: String?, year: Int?): TmdbItem? {
        val paths = buildList {
            when (hintType) {
                "tv" -> add("search/tv")
                "movie" -> add("search/movie")
                else -> {
                    add("search/movie")
                    add("search/tv")
                }
            }
        }

        for (path in paths) {
            val yearPart = when (path) {
                "search/movie" -> year?.let { "&year=$it" }.orEmpty()
                "search/tv" -> year?.let { "&first_air_date_year=$it" }.orEmpty()
                else -> ""
            }
            val result = app.get(
                "$tmdbApi/$path?api_key=$tmdbApiKey&query=${title.urlEncoded()}$yearPart"
            ).parsedSafe<TmdbListResponse>()?.results?.firstOrNull()
            if (result != null) return result
        }
        return null
    }

    private suspend fun isPlayablePlaylist(playlist: List<PlaylistItem>): Boolean {
        val sourceUrl = playlist.firstOrNull()?.sources?.firstOrNull()?.file?.toAbsoluteStreamUrl() ?: return false
        val master = runCatching {
            app.get(
                sourceUrl,
                referer = "$streamUrl/",
                headers = mapOf("Referer" to "$streamUrl/", "User-Agent" to exoPlayerUserAgent)
            ).text
        }.getOrNull() ?: return false
        if (!master.contains("#EXTM3U")) return false

        val variantUrl = master.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http", true) && it.contains(".m3u8", true) }
            ?: return false

        val variant = runCatching {
            app.get(
                variantUrl,
                referer = "$streamUrl/",
                headers = mapOf("Referer" to "$streamUrl/", "User-Agent" to exoPlayerUserAgent)
            ).text
        }.getOrNull() ?: return false
        if (!variant.contains("#EXTM3U")) return false

        val firstSegment = variant.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?: return false
        val segmentUrl = resolveRelativeUrl(variantUrl, firstSegment) ?: return false

        return runCatching {
            app.get(
                segmentUrl,
                referer = "$streamUrl/",
                headers = mapOf("Referer" to "$streamUrl/", "User-Agent" to exoPlayerUserAgent)
            )
            true
        }.getOrDefault(false)
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String? {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        val httpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        return httpUrl.resolve(relative)?.toString()
    }

    private fun posterUrl(id: String): String = "https://imgcdn.kim/poster/v/$id.jpg"

    private fun backgroundPosterUrl(id: String): String = "https://imgcdn.kim/poster/h/$id.jpg"

    private fun episodePosterUrl(id: String): String = "https://imgcdn.kim/epimg/150/$id.jpg"

    private fun String?.toScoreOrNull(): Score? {
        val percent = this?.substringBefore("%")?.trim()?.toDoubleOrNull() ?: return null
        return Score.from10(percent / 10.0)
    }

    private fun String?.toMinutesOrNull(): Int? {
        val runtime = this?.trim().orEmpty()
        if (runtime.isBlank()) return null
        var total = 0
        runtime.split(" ").forEach { part ->
            when {
                part.endsWith("h") -> total += (part.removeSuffix("h").toIntOrNull() ?: 0) * 60
                part.endsWith("m") -> total += part.removeSuffix("m").toIntOrNull() ?: 0
            }
        }
        return total.takeIf { it > 0 }
    }

    private fun String?.toNetMirrorQuality(): Int {
        return when (this?.trim()?.lowercase()) {
            "full hd" -> Qualities.P1080.value
            "mid hd" -> Qualities.P720.value
            "low hd" -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.cleanJsonUrl(): String = replace("\\/", "/")

    private fun String.toAbsoluteStreamUrl(): String =
        if (startsWith("http://") || startsWith("https://")) this else "$streamUrl$this"

    private fun String?.toTmdbPosterUrl(): String? =
        this?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }

    private fun String?.toTmdbBackdropUrl(): String? =
        this?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w780$it" }

    private fun buildSearchResponse(
        title: String,
        payload: LoadPayload,
        type: TvType,
        poster: String? = null,
        year: Int? = null,
        score: Score? = null
    ): SearchResponse {
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, payload.toJson(), type) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = it }
            }
        } else {
            newMovieSearchResponse(title, payload.toJson(), type) {
                this.posterUrl = poster
                this.year = year
                score?.let { this.score = it }
            }
        }
    }

    companion object {
        private const val browserUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        private const val exoPlayerUserAgent = "Mozilla/5.0 (Android) ExoPlayer"
        private val iframeHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "en-GB,en;q=0.9",
            "Referer" to "https://net22.cc/",
            "sec-ch-ua" to "\"Chromium\";v=\"147\", \"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Upgrade-Insecure-Requests" to "1",
            "User-Agent" to browserUserAgent
        )
    }
}

data class LoadPayload(
    val id: String?,
    val title: String,
    val tmdbId: Int? = null,
    val tmdbType: String? = null,
    val year: Int? = null,
    val poster: String? = null
)

data class SearchData(
    @JsonProperty("searchResult")
    val searchResult: List<SearchItem> = emptyList()
)

data class SearchItem(
    val id: String,
    val t: String
)

data class MiniModalInfo(
    val runtime: String? = null,
    val hdsd: String? = null,
    val ua: String? = null,
    val match: String? = null,
    val genre: String? = null
)

data class PostData(
    val title: String? = null,
    val year: String? = null,
    val ua: String? = null,
    val match: String? = null,
    val runtime: String? = null,
    val hdsd: String? = null,
    val type: String? = null,
    val director: String? = null,
    val writer: String? = null,
    val cast: String? = null,
    val genre: String? = null,
    val desc: String? = null,
    val episodes: List<PostEpisode?>? = null,
    val season: List<PostSeason>? = null,
    val nextPageShow: Int? = null,
    val nextPageSeason: String? = null
)

data class PostEpisode(
    val id: String,
    val t: String? = null,
    val ep: String? = null,
    val s: String? = null,
    val time: String? = null
)

data class PostSeason(
    val id: String? = null
)

data class EpisodesPage(
    val episodes: List<PostEpisode>? = null,
    val nextPageShow: Int? = null
)

data class PlayToken(
    val h: String? = null
)

data class PlaylistItem(
    val title: String? = null,
    val image2: String? = null,
    val sources: List<PlaylistSource> = emptyList(),
    val tracks: List<PlaylistTrack>? = null
)

data class PlaylistSource(
    val file: String? = null,
    val label: String? = null,
    val type: String? = null
)

data class PlaylistTrack(
    val kind: String? = null,
    val file: String? = null,
    val label: String? = null
)

data class TmdbListResponse(
    val results: List<TmdbItem>? = null
)

data class TmdbItem(
    val id: Int? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @JsonProperty("poster_path")
    val posterPath: String? = null,
    @JsonProperty("backdrop_path")
    val backdropPath: String? = null,
    @JsonProperty("vote_average")
    val voteAverage: Double? = null,
    @JsonProperty("release_date")
    val releaseDate: String? = null,
    @JsonProperty("first_air_date")
    val firstAirDate: String? = null,
    val genres: List<TmdbGenre>? = null
) {
    fun yearOrNull(): Int? = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
}

data class TmdbGenre(
    val name: String? = null
)
