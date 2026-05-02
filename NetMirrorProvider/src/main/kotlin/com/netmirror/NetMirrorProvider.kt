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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class NetMirrorProvider : MainAPI() {
    override var mainUrl = "https://net22.cc"
    private val streamUrl = "https://net52.cc"
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
        "" to "Top Searches"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(emptyList(), false)

        val payload = app.get(
            "$mainUrl/search.php?t=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/home"
        ).parsedSafe<SearchData>() ?: return newHomePageResponse(emptyList(), false)

        val items = payload.searchResult
            .take(12)
            .mapNotNull { item ->
                val title = item.t.takeIf { it.isNotBlank() } ?: resolveTitleFromPlaylist(item.id)
                title?.takeIf { it.isNotBlank() }?.let {
                    newMovieSearchResponse(
                        it,
                        LoadPayload(item.id, it).toJson(),
                        TvType.Movie
                    ) {
                        this.posterUrl = posterUrl(item.id)
                    }
                }
            }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = false
        )
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
        val postData = fetchPostData(payload.id)
        if (postData != null) {
            return buildDetailedLoad(url, payload, postData)
        }

        val modalData = fetchMiniModal(payload.id)
        return newMovieLoadResponse(payload.title, url, TvType.Movie, LoadPayload(payload.id, payload.title).toJson()) {
            this.posterUrl = posterUrl(payload.id)
            this.backgroundPosterUrl = backgroundPosterUrl(payload.id)
            this.plot = "NetMirror detail endpoint currently requires an authenticated browser session."
            this.tags = modalData?.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            this.contentRating = modalData?.ua
            modalData?.match.toScoreOrNull()?.let { this.score = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = tryParseJson<LoadPayload>(data) ?: return false
        val playlist = fetchPlaylist(payload.id, payload.title)
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
        val title = postData.title ?: payload.title
        val plot = postData.desc
        val score = postData.match.toScoreOrNull()
        val tags = postData.genre?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val cast = postData.cast?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.map { ActorData(Actor(it)) }
        val poster = posterUrl(payload.id)
        val background = backgroundPosterUrl(payload.id)
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
                episodes += fetchEpisodesPage(title, payload.id, seasonId, 2)
            }
        }

        postData.season.orEmpty()
            .dropLast(1)
            .forEach { season ->
                val seasonId = season.id ?: return@forEach
                episodes += fetchEpisodesPage(title, payload.id, seasonId, 1)
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
        val direct = app.get(
            "$streamUrl/playlist.php?id=$id&t=${title.urlEncoded()}&h=x&tm=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/"
        ).parsedSafe<List<PlaylistItem>>()
        if (!direct.isNullOrEmpty()) return direct

        val postToken = app.post(
            "$mainUrl/play.php",
            headers = ajaxHeaders,
            referer = "$mainUrl/",
            data = mapOf("id" to id)
        ).parsedSafe<PlayToken>()?.h
            ?: return null

        return app.get(
            "$streamUrl/playlist.php?id=$id&t=${title.urlEncoded()}&h=${postToken.urlEncoded()}&tm=$unixTime",
            headers = ajaxHeaders,
            referer = "$mainUrl/"
        ).parsedSafe()
    }

    private suspend fun resolveTitleFromPlaylist(id: String): String? {
        return fetchPlaylist(id, "NetMirror")
            ?.firstOrNull()
            ?.title
            ?.takeIf { it.isNotBlank() }
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

    companion object {
        private const val browserUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        private const val exoPlayerUserAgent = "Mozilla/5.0 (Android) ExoPlayer"
    }
}

data class LoadPayload(
    val id: String,
    val title: String
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
