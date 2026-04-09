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

        val apiRes = apiGet(apiUrl, referer = mainUrl)
        mainUrl = getBaseUrl(apiRes.url)

        val detail = apiRes.parsedSafe<DetailResponse>()
            ?: apiRes.parsedSafe<ApiData<DetailResponse>>()?.data
            ?: throw ErrorLoadingException("Failed to load detail")

        val title = detail.title ?: slug.replace("-", " ").trim()
        val poster = detail.posterPath?.let { tmdbImage(it, "w500") }
        val backdrop = detail.backdropPath?.let { tmdbImage(it, "w780") }
        val logourl = detail.logoPath?.let { tmdbImage(it, "w300") }
        val year = (detail.releaseDate ?: detail.firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val tags = detail.genres?.mapNotNull { it.name }.orEmpty()
        val rating = detail.voteAverage?.toString()?.toDoubleOrNull()
        val trailer = detail.trailerUrl
        val actors = detail.cast?.mapNotNull { c ->
            val name = c.name ?: return@mapNotNull null
            Actor(name, c.profilePath?.let { tmdbImage(it, "w185") })
        }.orEmpty()

        // TV Series detail
        if (detail.seasons != null) {
            val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

            detail.firstSeason?.episodes?.forEach { ep ->
                val epId = ep.id ?: return@forEach
                episodes.add(
                    newEpisode(LoadData(id = epId, type = "episode", ref = url).toJson()) {
                        this.name = ep.name
                        this.season = detail.firstSeason.seasonNumber
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        addDate(ep.airDate)
                        this.posterUrl = ep.stillPath?.let { tmdbImage(it, "w300") }
                    }
                )
            }

            detail.seasons.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == detail.firstSeason?.seasonNumber) return@forEach
                val seasonUrl = "$mainUrl/api/series/${detail.slug ?: slug}/season/$seasonNum"
                val seasonData = runCatching {
                    apiGet(seasonUrl, referer = mainUrl).parsedSafe<SeasonWrapper>()?.season
                }.getOrNull()
                seasonData?.episodes?.forEach { ep ->
                    val epId = ep.id ?: return@forEach
                    episodes.add(
                        newEpisode(LoadData(id = epId, type = "episode", ref = url).toJson()) {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.description = ep.overview
                            this.runTime = ep.runtime
                            this.score = Score.from10(ep.voteAverage?.toString())
                            addDate(ep.airDate)
                            this.posterUrl = ep.stillPath?.let { tmdbImage(it, "w300") }
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = detail.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                this.comingSoon = episodes.isEmpty()
            }
        }

        // Movie detail
        return newMovieLoadResponse(title, url, TvType.Movie, LoadData(id = detail.id ?: "", type = "movie", ref = url).toJson()) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.logoUrl = logourl
            this.year = year
            this.plot = detail.overview
            this.tags = tags
            this.score = Score.from10(rating?.toString())
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = tryParseJson<LoadData>(data) ?: return false
        val contentId = parsed.id
        val refererUrl = parsed.ref ?: mainUrl
        val contentTypes = buildList {
            add(parsed.type)
            if (parsed.type.equals("episode", true)) {
                // Idlix sometimes expects a different contentType for episode playback.
                addAll(listOf("tv", "series"))
            }
        }.distinct()

        val jsonHeaders = mapOf(
            "accept" to "*/*",
            "content-type" to "application/json",
            "origin" to mainUrl,
            "user-agent" to USER_AGENT,
        )
        val htmlHeaders = mapOf(
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "origin" to mainUrl,
            "user-agent" to USER_AGENT,
        )

        Log.d("Idlix", "loadLinks id=$contentId types=$contentTypes ref=$refererUrl")

        // Required: clearance token
        val ts = System.currentTimeMillis()
        val aclrRes = runCatching {
            app.get(
                "$mainUrl/pagead/ad_frame.js?_=$ts",
                headers = htmlHeaders,
                referer = refererUrl,
                interceptor = cloudflareInterceptor
            ).text
        }.getOrNull().orEmpty()
        val aclr = Regex("""__aclr\s*=\s*["']([0-9a-fA-F]+)["']""")
            .find(aclrRes)?.groupValues?.get(1)
            ?: run {
                Log.d("Idlix", "loadLinks: failed to extract __aclr")
                return false
            }

        // warm up session (matches browser behavior)
        runCatching {
            app.get(
                "$mainUrl/api/homepage/ads/surface/preroll",
                headers = htmlHeaders,
                referer = refererUrl,
                interceptor = cloudflareInterceptor
            )
        }

        val embedPath = contentTypes.firstNotNullOfOrNull { type ->
            getEmbedPath(type, contentId, aclr, jsonHeaders, refererUrl)
        } ?: return false

        val embedUrl = if (embedPath.startsWith("http")) embedPath else "$mainUrl$embedPath"
        Log.d("Idlix", "loadLinks: embedUrl=$embedUrl")
        val embedDoc = app.get(embedUrl, headers = htmlHeaders, referer = refererUrl, interceptor = cloudflareInterceptor).document
        val iframeUrl = extractIframeUrl(embedDoc) ?: return false
        Log.d("Idlix", "loadLinks: iframeUrl=$iframeUrl")
        val fixedFinalUrl = unwrapIframeUrl(fixUrl(iframeUrl) ?: return false, htmlHeaders, refererUrl) ?: return false
        Log.d("Idlix", "loadLinks: finalUrl=$fixedFinalUrl")

        return when {
            fixedFinalUrl.contains("jeniusplay", ignoreCase = true) -> {
                // Jeniusplay often expects its own-domain referer; pass null to let extractor set it.
                Jeniusplay().getUrl(fixedFinalUrl, null, subtitleCallback, callback)
                true
            }
            else -> {
                loadExtractor(fixedFinalUrl, mainUrl, subtitleCallback, callback)
                true
            }
        }
    }

    private fun extractIframeUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src]") ?: document.selectFirst("iframe[data-src]")
        val src = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") }?.trim()
        return src?.takeIf { it.isNotBlank() }
    }

    private suspend fun getEmbedPath(
        contentType: String,
        contentId: String,
        clearance: String,
        headers: Map<String, String>,
        refererUrl: String,
    ): String? {
        val challengeJson = """
            {
              "contentType": "$contentType",
              "contentId": "$contentId",
              "clearance": "$clearance"
            }
        """.trimIndent()

        val challengeHttp = app.post(
            "$mainUrl/api/watch/challenge",
            requestBody = challengeJson.toRequestBody("application/json".toMediaType()),
            headers = headers,
            referer = refererUrl,
            interceptor = cloudflareInterceptor
        )
        val challengeRes = challengeHttp.parsedSafe<ChallengeResponse>() ?: run {
            Log.d("Idlix", "watch/challenge failed type=$contentType id=$contentId")
            return null
        }

        val nonce = solvePow(challengeRes.challenge, challengeRes.difficulty)
        val solveJson = """
            {
              "challenge": "${challengeRes.challenge}",
              "signature": "${challengeRes.signature}",
              "nonce": $nonce
            }
        """.trimIndent()

        val solveHttp = app.post(
            "$mainUrl/api/watch/solve",
            requestBody = solveJson.toRequestBody("application/json".toMediaType()),
            headers = headers,
            referer = refererUrl,
            interceptor = cloudflareInterceptor
        )
        val solveRes = solveHttp.parsedSafe<SolveResponse>() ?: run {
            Log.d("Idlix", "watch/solve failed type=$contentType id=$contentId")
            return null
        }

        return solveRes.embedUrlResolved
    }

    private suspend fun unwrapIframeUrl(url: String, headers: Map<String, String>, refererUrl: String): String? {
        var current = url
        val base = getBaseUrl(mainUrl)
        repeat(4) {
            if (!current.startsWith(base)) return current
            val doc = app.get(current, headers = headers, referer = refererUrl, interceptor = cloudflareInterceptor).document
            val next = extractIframeUrl(doc) ?: return current
            current = fixUrl(next) ?: return null
        }
        return current
    }

    private fun solvePow(challenge: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty)
        var nonce = 0
        while (true) {
            val hash = sha256(challenge + nonce)
            if (hash.startsWith(target)) return nonce
            nonce++
        }
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        digest.forEach { b -> sb.append(String.format("%02x", b)) }
        return sb.toString()
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

    data class DetailResponse(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("imdbId") val imdbId: String? = null,
        @JsonProperty("tmdbId") val tmdbId: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("tagline") val tagline: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("logoPath") val logoPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("voteAverage") val voteAverage: Any? = null,
        @JsonProperty("trailerUrl") val trailerUrl: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("director") val director: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("cast") val cast: List<Cast>? = null,
        @JsonProperty("seasons") val seasons: List<ApiSeason>? = null,
        @JsonProperty("firstSeason") val firstSeason: ApiSeason? = null,
    )

    data class Genre(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("slug") val slug: String? = null,
    )

    data class Cast(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profilePath") val profilePath: String? = null,
    )

    data class ApiSeason(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("episodes") val episodes: List<ApiEpisodeData>? = null,
    )

    data class ApiEpisodeData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("stillPath") val stillPath: String? = null,
        @JsonProperty("airDate") val airDate: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("voteAverage") val voteAverage: Any? = null,
    )

    data class SeasonWrapper(
        @JsonProperty("season") val season: ApiSeason? = null,
    )

    data class ChallengeResponse(
        @JsonProperty("challenge") val challenge: String,
        @JsonProperty("signature") val signature: String,
        @JsonProperty("difficulty") val difficulty: Int,
    )

    data class SolveResponse(
        @JsonProperty("embedUrl") val embedUrlRaw: String? = null,
        @JsonProperty("embed_url") val embedUrlAlt: String? = null,
    ) {
        val embedUrlResolved: String?
            get() = embedUrlAlt ?: embedUrlRaw
    }

    data class LoadData(
        @JsonProperty("id") val id: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("ref") val ref: String? = null,
    )

}
