package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.toNewSearchResponseList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import kotlin.math.ceil


class IdlixProvider : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://z1.idlixku.com"
    private var directUrl = mainUrl
    private val cloudflareInterceptor by lazy { CloudflareKiller() }
    override var name = "Idlix🎄"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )


    override val mainPage = mainPageOf(
        "$mainUrl/api/trending/top?limit=24&period=7d&contentType=movie" to "Trending Movies",
        "$mainUrl/api/trending/top?limit=24&period=7d&contentType=series" to "Trending TV Series",
        "$mainUrl/api/movies?limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?limit=36&sort=createdAt" to "TV Series Terbaru",
    )

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun getMainPageUrl(data: String, page: Int): String {
        val (base, query) = data.split("?", limit = 2).let {
            it.first() to it.getOrNull(1)
        }

        val uri = runCatching { URI(base) }.getOrNull()
        val isRootPath = uri?.path.isNullOrBlank() || uri?.path == "/"

        val pagedBase = if (isRootPath) {
            base.trimEnd('/') + "/page/"
        } else {
            base
        }

        val normalizedBase = if (pagedBase.endsWith("/")) pagedBase else "$pagedBase/"
        return if (query != null) {
            "${normalizedBase}${page}/?$query"
        } else {
            "${normalizedBase}${page}/"
        }
    }

    private fun inferTvTypeFromUrl(url: String): TvType {
        val lower = url.lowercase()
        return when {
            lower.contains("/tvseries/") || lower.contains("/tv-series/") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun tmdbImage(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/$size$path"
    }

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl,
    )

    private suspend fun apiGet(url: String, referer: String? = null) = app.get(
            url,
            headers = apiHeaders(),
            referer = referer,
            interceptor = cloudflareInterceptor
        )

    private fun slugFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path.trimEnd('/').substringAfterLast('/').trim()
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("/")) "$mainUrl$url" else url
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data.contains("/api/")) {
            if (page > 1 && request.data.contains("/api/trending/top")) {
                return newHomePageResponse(request.name, emptyList())
            }
            val url = getApiPageUrl(request.data, page)
            val res = apiGet(url, referer = "$mainUrl/")
            mainUrl = getBaseUrl(res.url)
            val home = parseApiItems(res.text).mapNotNull { it.toApiSearchResult(request.data) }
            return newHomePageResponse(request.name, home)
        }

        val nonPaged = request.name == "Featured" && page <= 1
        val req = if (nonPaged) {
            app.get(request.data, interceptor = cloudflareInterceptor)
        } else {
            app.get(getMainPageUrl(request.data, page), interceptor = cloudflareInterceptor)
        }
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = (if (nonPaged) {
            document.select("div.items.featured article")
        } else {
            document.select("div.items.full article, div#archive-content article")
        }).mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getApiPageUrl(data: String, page: Int): String {
        if (data.contains("/api/trending/top")) return data
        return when {
            data.contains(Regex("[?&]page=\\d+")) ->
                data.replace(Regex("([?&]page=)\\d+"), "$1$page")
            data.contains("?") -> "$data&page=$page"
            else -> "$data?page=$page"
        }
    }

    private fun parseApiItems(text: String): List<ApiItem> {
        return tryParseJson<ApiListResponse>(text)?.data
            ?: tryParseJson<List<ApiItem>>(text)
            ?: emptyList()
    }

    private fun ApiItem.toApiSearchResult(requestData: String): SearchResponse? {
        val resolvedTitle = title ?: name ?: return null
        val resolvedSlug = slug ?: return null

        val resolvedType = (contentType ?: type ?: "").lowercase()
        val isSeries = resolvedType == "series" ||
            requestData.contains("/api/series") ||
            requestData.contains("contentType=series")

        val href = if (isSeries) "$mainUrl/series/$resolvedSlug" else "$mainUrl/movie/$resolvedSlug"
        val poster = posterPath?.let {
            if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/w342$it"
        }
        val q = getQualityFromString(quality)

        return if (isSeries) {
            newTvSeriesSearchResponse(resolvedTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = q
            }
        } else {
            newMovieSearchResponse(resolvedTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = q
            }
        }
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episode/") -> {
                var title = uri.substringAfter("$mainUrl/episode/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvseries/$title"
            }

            uri.contains("/season/") -> {
                var title = uri.substringAfter("$mainUrl/season/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.get(1).toString()
                "$mainUrl/tvseries/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("h3 > a")!!.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(this.selectFirst("h3 > a")!!.attr("href"))
        val posterUrl = this.select("div.poster > img").attr("src")
        val quality = getQualityFromString(this.select("span.quality").text())
        return when (inferTvTypeFromUrl(href)) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }

    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val req = app.get("$mainUrl/search/$query/page/$page", interceptor = cloudflareInterceptor)
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        
        val results = document.select("div.result-item").mapNotNull {
            val titleElement = it.selectFirst("div.title > a") ?: return@mapNotNull null
            val titleWithYear = titleElement.text().trim()

            if (!titleWithYear.contains(Regex("\\(\\d{4}\\)"))) return@mapNotNull null

            val title = titleWithYear.replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(titleElement.attr("href"))
            var posterUrl = it.selectFirst("img")?.attr("src")

            if (posterUrl?.contains("image.tmdb.org/t/p") == true) {
                posterUrl = posterUrl.replace(Regex("/w\\d+/"), "/w200/")
            }

            when (inferTvTypeFromUrl(href)) {
                TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
                else -> newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = slugFromUrl(url)
        val isSeries = url.contains("/series/")
        val apiUrl = if (isSeries) "$mainUrl/api/series/$slug" else "$mainUrl/api/movies/$slug"

        val pageReferer = if (isSeries) "$mainUrl/series/$slug" else "$mainUrl/movie/$slug"
        val apiRes = apiGet(apiUrl, referer = pageReferer)
        mainUrl = getBaseUrl(apiRes.url)

        return if (isSeries) {
            val detail = apiRes.parsedSafe<ApiSeriesDetail>()
                ?: apiRes.parsedSafe<ApiData<ApiSeriesDetail>>()?.data
                ?: throw ErrorLoadingException("Failed to load series detail")

            val title = detail.name ?: detail.title ?: slug.replace("-", " ").trim()
            val poster = tmdbImage(detail.posterPath, "w500")
            val year = detail.firstAirDate?.take(4)?.toIntOrNull()
            val tags = detail.genres?.mapNotNull { it.name }.orEmpty()
            val trailer = detail.trailerUrl

            val seriesId = detail.idResolved ?: throw ErrorLoadingException("Missing series id")
            val seasonsFromDetail = detail.seasons?.mapNotNull { it.seasonNumberResolved }.orEmpty()
            val seasons = if (seasonsFromDetail.isNotEmpty()) {
                seasonsFromDetail.distinct()
            } else {
                // Some responses don't include seasons list, so probe sequentially.
                (1..30).toList()
            }

            val seasonDetails = seasons.amap { seasonNumber ->
                runCatching {
                    val sRes = apiGet("$mainUrl/api/series/$slug/season/$seasonNumber", referer = pageReferer)
                    val parsed = sRes.parsedSafe<ApiSeasonDetail>()
                        ?: sRes.parsedSafe<ApiData<ApiSeasonDetail>>()?.data
                    if (seasonNumber <= 3) {
                        Log.d(
                            "IdlixProvider",
                            "season=$seasonNumber code=${sRes.code} eps=${parsed?.episodes?.size ?: 0}"
                        )
                    }
                    parsed
                }.getOrNull()
            }.filterNotNull()
                .filter { it.episodes.orEmpty().isNotEmpty() }

            val episodes = seasonDetails.flatMap { season ->
                season.episodes.orEmpty().mapNotNull { ep ->
                    val seasonNumber = season.seasonNumberResolved
                    val epNumber = ep.episodeNumberResolved
                    val epName = ep.name ?: ep.title ?: (if (epNumber != null) "Episode $epNumber" else null)
                    val watchData = WatchData(
                        contentType = "tv",
                        contentId = seriesId,
                        slug = slug,
                        season = seasonNumber,
                        episode = epNumber,
                        episodeId = ep.id
                    ).toJson()
                    newEpisode(watchData) {
                        this.name = epName
                        this.season = seasonNumber
                        this.episode = epNumber
                        this.posterUrl = tmdbImage(ep.stillPathResolved, "w500") ?: tmdbImage(detail.backdropPath, "w780")
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = detail.overview
                this.tags = tags
                addTrailer(trailer)
            }
        } else {
            val detail = apiRes.parsedSafe<ApiMovieDetail>()
                ?: apiRes.parsedSafe<ApiData<ApiMovieDetail>>()?.data
                ?: throw ErrorLoadingException("Failed to load movie detail")

            val title = detail.title ?: detail.name ?: slug.replace("-", " ").trim()
            val poster = tmdbImage(detail.posterPath, "w500")
            val year = detail.releaseDate?.take(4)?.toIntOrNull()
            val tags = detail.genres?.mapNotNull { it.name }.orEmpty()
            val ratingValue = detail.voteAverageResolved
            val trailer = detail.trailerUrl

            val watchData = WatchData(
                contentType = "movie",
                contentId = detail.idResolved ?: slug
            ).toJson()

            newMovieLoadResponse(title, url, TvType.Movie, watchData) {
                this.posterUrl = poster
                this.year = year
                this.plot = detail.overview
                this.tags = tags
                if (ratingValue != null) this.score = Score.from10(ratingValue)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchData = tryParseJson<WatchData>(data) ?: run {
            val slug = slugFromUrl(data)
            val isSeries = data.contains("/series/")
            val apiUrl = if (isSeries) "$mainUrl/api/series/$slug" else "$mainUrl/api/movies/$slug"
            val referer = if (isSeries) "$mainUrl/series/$slug" else "$mainUrl/movie/$slug"
            val res = apiGet(apiUrl, referer = referer)
            val id = (res.parsedSafe<ApiMovieDetail>()?.idResolved
                ?: res.parsedSafe<ApiSeriesDetail>()?.idResolved
                ?: res.parsedSafe<ApiData<ApiMovieDetail>>()?.data?.idResolved
                ?: res.parsedSafe<ApiData<ApiSeriesDetail>>()?.data?.idResolved) ?: slug
            WatchData(
                contentType = if (isSeries) "tv" else "movie",
                contentId = id,
                slug = if (isSeries) slug else null
            )
        }

        val resolved = resolveWatchData(watchData)
        val embedUrl = fixUrl(getEmbedUrl(resolved)) ?: return false
        return when {
            embedUrl.contains("jeniusplay.com") -> {
                Jeniusplay().getUrl(embedUrl, "$mainUrl/", subtitleCallback, callback)
                true
            }
            else -> {
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                true
            }
        }
    }

    private suspend fun resolveWatchData(data: WatchData): WatchData {
        if (data.contentType.lowercase() != "tv") return data
        if (!data.episodeId.isNullOrBlank()) return data
        val slug = data.slug ?: return data
        val season = data.season ?: return data
        val episode = data.episode ?: return data

        val epRes = runCatching {
            apiGet(
                "$mainUrl/api/series/$slug/season/$season/episode/$episode",
                referer = "$mainUrl/series/$slug"
            )
        }.getOrNull() ?: return data

        val ep = epRes.parsedSafe<ApiEpisode>()
            ?: epRes.parsedSafe<ApiData<ApiEpisode>>()?.data
            ?: return data

        return data.copy(episodeId = ep.id)
    }

    private suspend fun getEmbedUrl(data: WatchData): String? {
        val contentTypes = when (data.contentType.lowercase()) {
            "series" -> listOf("tv", "series")
            "tv" -> listOf("tv", "series")
            else -> listOf(data.contentType)
        }

        for (contentType in contentTypes) {
            val payload = mutableMapOf(
                "contentType" to contentType,
                "contentId" to data.contentId
            ).toJson()

            val challengeRes = app.post(
                url = "$mainUrl/api/watch/challenge",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Origin" to mainUrl,
                ),
                referer = "$mainUrl/",
                requestBody = payload.toRequestBody("application/json".toMediaType()),
                interceptor = cloudflareInterceptor
            )
            Log.d("IdlixProvider", "watch/challenge code=${challengeRes.code} type=$contentType")

            val challenge = challengeRes.parsedSafe<WatchChallengeResponse>()
                ?: challengeRes.parsedSafe<ApiData<WatchChallengeResponse>>()?.data
                ?: continue

            val nonce = solveChallenge(challenge.challenge, challenge.difficulty)
            val solvePayload = mapOf(
                "challenge" to challenge.challenge,
                "signature" to challenge.signature,
                "nonce" to nonce
            ).toJson()

            val solveRes = app.post(
                url = "$mainUrl/api/watch/solve",
                headers = mapOf(
                    "Accept" to "application/json",
                    "Origin" to mainUrl,
                ),
                referer = "$mainUrl/",
                requestBody = solvePayload.toRequestBody("application/json".toMediaType()),
                interceptor = cloudflareInterceptor
            )
            Log.d("IdlixProvider", "watch/solve code=${solveRes.code} type=$contentType")
            val solved = solveRes.parsedSafe<WatchSolveResponse>()
                ?: solveRes.parsedSafe<ApiData<WatchSolveResponse>>()?.data
                ?: continue

            val embed = solved.embedUrlResolved
            if (!embed.isNullOrBlank()) return embed
        }

        return null
    }

    private fun solveChallenge(challenge: String, difficulty: Int): Int {
        val prefix = "0".repeat(difficulty.coerceAtLeast(0))
        val bytesToCheck = (ceil(difficulty / 2.0) + 1).toInt().coerceAtLeast(1)
        val digest = MessageDigest.getInstance("SHA-256")

        for (i in 0 until 10_000_000) {
            digest.reset()
            val input = (challenge + i).toByteArray()
            val out = digest.digest(input)
            val sb = StringBuilder(bytesToCheck * 2)
            for (idx in 0 until bytesToCheck) {
                sb.append(out[idx].toUByte().toString(16).padStart(2, '0'))
            }
            if (sb.startsWith(prefix)) return i
        }
        throw ErrorLoadingException("Challenge too difficult")
    }

    private fun generateKey(r: String, m: String): String {
        val rList = r.split("\\x").toTypedArray()
        var n = ""
        val decodedM = safeBase64Decode(m.reversed())
        for (s in decodedM.split("|")) {
            n += "\\x" + rList[Integer.parseInt(s) + 1]
        }
        return n
    }

    private fun safeBase64Decode(input: String): String {
        var paddedInput = input
        val remainder = input.length % 4
        if (remainder != 0) {
            paddedInput += "=".repeat(4 - remainder)
        }
        return base64Decode(paddedInput)
    }

    private fun String.fixBloat(): String {
        return this.replace("\"", "").replace("\\", "")
    }


    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )

    data class ApiData<T>(
        @JsonProperty("data") val data: T? = null,
    )

    data class ApiListResponse(
        @JsonProperty("data") val data: List<ApiItem>? = null,
    )

    data class ApiItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("contentType") val contentType: String? = null,
    )

    data class ApiGenre(
        @JsonProperty("name") val name: String? = null,
    )

    data class ApiMovieDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("movieId") val movieId: String? = null,
        @JsonProperty("contentId") val contentId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("genres") val genres: List<ApiGenre>? = null,
        @JsonProperty("vote_average") val voteAverageSnake: Double? = null,
        @JsonProperty("voteAverage") val voteAverageCamel: Double? = null,
        @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    ) {
        val voteAverageResolved: Double?
            get() = voteAverageCamel ?: voteAverageSnake

        val idResolved: String?
            get() = id ?: movieId ?: contentId
    }

    data class ApiSeasonRef(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumberAlt: Int? = null,
        @JsonProperty("name") val name: String? = null,
    ) {
        val seasonNumberResolved: Int?
            get() = seasonNumber ?: seasonNumberAlt
    }

    data class ApiEpisode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("episode_number") val episodeNumberAlt: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("stillPath") val stillPath: String? = null,
        @JsonProperty("still_path") val stillPathAlt: String? = null,
    ) {
        val episodeNumberResolved: Int?
            get() = episodeNumber ?: episodeNumberAlt
        val stillPathResolved: String?
            get() = stillPath ?: stillPathAlt
    }

    data class ApiSeasonDetail(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumberAlt: Int? = null,
        @JsonProperty("episodes") val episodes: List<ApiEpisode>? = null,
    ) {
        val seasonNumberResolved: Int?
            get() = seasonNumber ?: seasonNumberAlt
    }

    data class ApiSeriesDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("tvSeriesId") val tvSeriesId: String? = null,
        @JsonProperty("contentId") val contentId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("genres") val genres: List<ApiGenre>? = null,
        @JsonProperty("seasons") val seasons: List<ApiSeasonRef>? = null,
        @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    ) {
        val idResolved: String?
            get() = id ?: tvSeriesId ?: contentId
    }

    data class WatchData(
        @JsonProperty("contentType") val contentType: String,
        @JsonProperty("contentId") val contentId: String,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("episodeId") val episodeId: String? = null,
    )

    data class WatchChallengeResponse(
        @JsonProperty("challenge") val challenge: String,
        @JsonProperty("signature") val signature: String,
        @JsonProperty("difficulty") val difficulty: Int,
    )

    data class WatchSolveResponse(
        @JsonProperty("embedUrl") val embedUrl: String? = null,
        @JsonProperty("embed_url") val embedUrlAlt: String? = null,
    ) {
        val embedUrlResolved: String?
            get() = embedUrl ?: embedUrlAlt
    }

}
