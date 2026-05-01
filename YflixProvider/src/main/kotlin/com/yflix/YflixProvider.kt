package com.yflix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLEncoder

class YflixProvider : MainAPI() {
    override var mainUrl = "https://yflix.to"
    override var name = "Yflix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override val mainPage = mainPageOf(
        "$mainUrl/browser?type[]=movie&sort=trending" to "Trending Movies",
        "$mainUrl/browser?type[]=tv&sort=trending" to "Trending TV Shows",
        "$mainUrl/browser?type[]=movie&type[]=tv&sort=imdb" to "Top IMDb",
        "$mainUrl/browser?type[]=movie&type[]=tv&sort=release_date" to "Latest Release",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(buildPagedUrl(request.data, page), referer = "$mainUrl/").document
        val items = doc.select("div.film-section div.item").mapNotNull { it.toSearchResponse() }
        val hasNext = doc.select("ul.pagination a[rel=next]").isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/browser?keyword=${query.urlEncoded()}", referer = "$mainUrl/").document
        return doc.select("div.film-section div.item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1.title")?.text()?.trim().orEmpty()
        if (title.isBlank()) throw ErrorLoadingException("Missing title")

        val isTv = doc.select("#filmCtrl .prev-next").isNotEmpty()
        val poster = doc.selectFirst("div.poster img")?.attr("abs:src")
            ?.ifBlank { doc.selectFirst("div.poster img")?.attr("src") }
        val background = doc.selectFirst("div.detail-bg, div.player-bg")
            ?.attr("style")
            ?.substringAfter("url('")
            ?.substringBefore("')")
            ?.takeIf { it.isNotBlank() }
        val plot = doc.selectFirst("div.description")?.text()?.trim()
        val year = doc.select("div.metadata.set span").map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d{4}""")) }
            ?.toIntOrNull()
        val rating = doc.selectFirst("div.metadata.set span.IMDb")?.ownText()?.trim()?.toDoubleOrNull()
        val contentRating = doc.selectFirst("div.metadata.set span.ratingR")?.text()?.trim()
        val tags = doc.select("ul.mics li:contains(Genres:) a").map { it.text().trim() }
        val recommendations = doc.select(".movie-related .item").mapNotNull { it.toSearchResponse() }

        val tmdbMeta = fetchTmdbMeta(title, year, isTv)
        val trailer = tmdbMeta?.videos?.results.orEmpty()
            .firstOrNull { it.site.equals("YouTube", true) && !it.key.isNullOrBlank() }
            ?.key
            ?.let { "https://www.youtube.com/watch?v=$it" }

        return if (isTv) {
            val episodes = tmdbMeta?.id?.let { buildEpisodes(it, tmdbMeta.externalIds?.imdbId, title, year, url) }.orEmpty()
            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background ?: poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                this.contentRating = contentRating
                rating?.let { this.score = Score.from10(it) }
                trailer?.let { addTrailer(it, addRaw = false) }
            }
        } else {
            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = LinkData(
                    watchUrl = url,
                    tmdbId = tmdbMeta?.id,
                    imdbId = tmdbMeta?.externalIds?.imdbId,
                    title = title,
                    year = year,
                    isTv = false
                ).toJson()
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background ?: poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                this.contentRating = contentRating
                rating?.let { this.score = Score.from10(it) }
                trailer?.let { addTrailer(it, addRaw = false) }
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
        val nativeEmbeds = resolveNativeEmbeds(info)
        if (nativeEmbeds.isNotEmpty()) {
            nativeEmbeds.forEach { native ->
                runCatching {
                    loadExtractor(native.url, info.watchUrl ?: baseReferer(native.url), subtitleCallback, callback)
                }
            }
            return true
        }

        var found = false
        buildFallbackEmbeds(info).forEach { embed ->
            runCatching {
                loadExtractor(embed, baseReferer(embed), subtitleCallback, callback)
            }.onSuccess {
                found = true
            }
        }

        return found
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val href = selectFirst("a.poster, a.title")?.attr("abs:href").orEmpty()
        val title = selectFirst("a.title")?.text()?.trim().orEmpty()
        if (href.isBlank() || title.isBlank()) return null

        val poster = selectFirst("a.poster img")?.attr("abs:data-src")
            ?.ifBlank { selectFirst("a.poster img")?.attr("abs:src") }
            ?.ifBlank { selectFirst("a.poster img")?.attr("data-src") }
            ?.ifBlank { selectFirst("a.poster img")?.attr("src") }
        val quality = selectFirst("div.quality")?.text()?.trim()
        val meta = select("div.metadata span").map { it.text().trim() }
        val isTv = meta.firstOrNull().equals("TV", true)
        val year = meta.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.quality = getQualityFromString(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.quality = getQualityFromString(quality)
            }
        }
    }

    private suspend fun fetchTmdbMeta(title: String, year: Int?, isTv: Boolean): TmdbDetail? {
        val typePath = if (isTv) "tv" else "movie"
        val searchKey = if (isTv) "first_air_date_year" else "year"
        val yearPart = year?.let { "&$searchKey=$it" }.orEmpty()
        val searchUrl = "$tmdbApi/search/$typePath?api_key=$tmdbApiKey&query=${title.urlEncoded()}$yearPart"

        val picked = app.get(searchUrl).parsedSafe<TmdbSearchResults>()?.results.orEmpty()
            .firstOrNull()
            ?: return null

        return app.get(
            "$tmdbApi/$typePath/${picked.id}?api_key=$tmdbApiKey&append_to_response=external_ids,videos"
        ).parsedSafe()
    }

    private suspend fun buildEpisodes(
        tmdbId: Int,
        imdbId: String?,
        title: String,
        year: Int?,
        watchUrl: String
    ): List<Episode> {
        val detail = app.get("$tmdbApi/tv/$tmdbId?api_key=$tmdbApiKey").parsedSafe<TmdbDetail>() ?: return emptyList()
        val episodes = mutableListOf<Episode>()

        detail.seasons.orEmpty().forEach { season ->
            val seasonNumber = season.seasonNumber ?: return@forEach
            if (seasonNumber <= 0) return@forEach

            val seasonData = app.get("$tmdbApi/tv/$tmdbId/season/$seasonNumber?api_key=$tmdbApiKey")
                .parsedSafe<TmdbSeasonDetail>() ?: return@forEach

            seasonData.episodes.orEmpty().forEach { episode ->
                val episodeNumber = episode.episodeNumber ?: return@forEach
                episodes += newEpisode(
                    LinkData(
                        tmdbId = tmdbId,
                        imdbId = imdbId,
                        watchUrl = watchUrl,
                        title = title,
                        year = year,
                        isTv = true,
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

        return episodes
    }

    private fun buildFallbackEmbeds(info: LinkData): List<String> {
        return buildList {
            info.tmdbId?.let { tmdb ->
                if (info.isTv) {
                    val season = info.season ?: return@let
                    val episode = info.episode ?: return@let
                    add("https://vidsrc.to/embed/tv/$tmdb/$season/$episode")
                    add("https://vidsrc.xyz/embed/tv/$tmdb/$season/$episode")
                    add("https://vidsrc.net/embed/tv/$tmdb/$season/$episode")
                    add("https://vidsrc.su/embed/tv/$tmdb/$season/$episode")
                    add("https://vidsrc.cc/v2/embed/tv/$tmdb/$season/$episode")
                    add("https://www.2embed.cc/embedtv/$tmdb&s=$season&e=$episode")
                    add("https://www.2embed.skin/embedtv/$tmdb&s=$season&e=$episode")
                } else {
                    add("https://vidsrc.to/embed/movie/$tmdb")
                    add("https://vidsrc.xyz/embed/movie/$tmdb")
                    add("https://vidsrc.net/embed/movie/$tmdb")
                    add("https://vidsrc.su/embed/movie/$tmdb")
                    add("https://vidsrc.cc/v2/embed/movie/$tmdb")
                    add("https://www.2embed.cc/embed/$tmdb")
                    add("https://www.2embed.skin/embed/$tmdb")
                }
            }

            info.imdbId?.let { imdb ->
                if (info.isTv) {
                    val season = info.season ?: return@let
                    val episode = info.episode ?: return@let
                    add("https://vidsrc.xyz/embed/tv?imdb=$imdb&season=$season&episode=$episode")
                    add("https://vidsrc.net/embed/tv?imdb=$imdb&season=$season&episode=$episode")
                    add("https://vidsrc.su/embed/tv?imdb=$imdb&season=$season&episode=$episode")
                } else {
                    add("https://vidsrc.xyz/embed/movie?imdb=$imdb")
                    add("https://vidsrc.net/embed/movie?imdb=$imdb")
                    add("https://vidsrc.su/embed/movie?imdb=$imdb")
                }
            }
        }.distinct()
    }

    private suspend fun resolveNativeEmbeds(info: LinkData): List<NativeEmbed> {
        val watchUrl = info.watchUrl ?: return emptyList()
        return runCatching {
            val watchDoc = app.get(watchUrl, referer = "$mainUrl/").document
            val keyword = watchUrl.substringAfter("/watch/").substringBefore(".")
            val dataId = watchDoc.selectFirst("#movie-rating")?.attr("data-id")?.trim().orEmpty()
            if (dataId.isBlank()) return emptyList()

            val episodeToken = decodeToken(dataId)
            if (episodeToken.isBlank()) return emptyList()

            val episodeResponse = app.get(
                "$mainUrl/ajax/episodes/list?keyword=${keyword.urlEncoded()}&id=${dataId.urlEncoded()}&_=${episodeToken.urlEncoded()}",
                referer = watchUrl
            ).parsedSafe<YflixAjaxResponse>() ?: return emptyList()

            val episodeDoc = Jsoup.parse(episodeResponse.result.orEmpty())
            val episodeNode = selectEpisodeNode(episodeDoc, info) ?: return emptyList()
            val eid = episodeNode.attr("eid").trim()
            if (eid.isBlank()) return emptyList()

            val linksToken = decodeToken(eid)
            if (linksToken.isBlank()) return emptyList()

            val linksResponse = app.get(
                "$mainUrl/ajax/links/list?eid=${eid.urlEncoded()}&_=${linksToken.urlEncoded()}",
                referer = watchUrl
            ).parsedSafe<YflixAjaxResponse>() ?: return emptyList()

            Jsoup.parse(linksResponse.result.orEmpty()).select("li.server,div.server,[data-lid]").take(8).mapNotNull { server ->
                val lid = server.attr("data-lid").trim()
                if (lid.isBlank()) return@mapNotNull null

                val viewToken = decodeToken(lid)
                if (viewToken.isBlank()) return@mapNotNull null

                val viewResponse = app.get(
                    "$mainUrl/ajax/links/view?id=${lid.urlEncoded()}&_=${viewToken.urlEncoded()}",
                    referer = watchUrl
                ).parsedSafe<YflixAjaxResponse>() ?: return@mapNotNull null

                val decoded = decodePayload(viewResponse.result.orEmpty())
                val url = runCatching { JSONObject(decoded).optString("url").trim() }.getOrDefault("")
                if (url.isBlank()) return@mapNotNull null

                NativeEmbed(
                    name = server.selectFirst("span")?.text()?.trim().orEmpty()
                        .ifBlank { server.text().trim().ifBlank { "Server" } },
                    url = url
                )
            }.distinctBy { it.url }
        }.getOrDefault(emptyList())
    }

    private suspend fun decodeToken(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val res = app.get("$ENC_MOVIES_FLIX${value.urlEncoded()}").text
            JSONObject(res).optString("result")
        }.getOrDefault("")
    }

    private suspend fun decodePayload(value: String): String {
        if (value.isBlank()) return ""
        val body = """{"text":${JSONObject.quote(value)}}""".toRequestBody(jsonMediaType)
        return runCatching {
            val res = app.post(DEC_MOVIES_FLIX, requestBody = body).text
            JSONObject(res).optString("result")
        }.getOrDefault("")
    }

    private fun selectEpisodeNode(
        doc: org.jsoup.nodes.Document,
        info: LinkData
    ): org.jsoup.nodes.Element? {
        if (!info.isTv) return doc.selectFirst("a[eid]")

        val season = info.season ?: return null
        val episode = info.episode ?: return null
        val hash = "#ep=$season,$episode"

        return doc.selectFirst("""a[eid][href$="$hash"]""")
            ?: doc.select("ul.episodes[data-season] a[eid]").firstOrNull { node ->
                val seasonNode = node.closest("ul.episodes[data-season]")
                seasonNode?.attr("data-season")?.toIntOrNull() == season &&
                    node.attr("num").toIntOrNull() == episode
            }
    }

    private fun buildPagedUrl(url: String, page: Int): String {
        if (page <= 1) return url
        return if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
    }

    private fun baseReferer(url: String): String {
        return runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        }.getOrDefault("$mainUrl/")
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    private fun String?.toPosterUrl(): String? {
        if (this.isNullOrBlank()) return null
        return if (startsWith("/")) "https://image.tmdb.org/t/p/w500/$this" else this
    }

    data class LinkData(
        val watchUrl: String? = null,
        val tmdbId: Int? = null,
        val imdbId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val isTv: Boolean = false,
        val season: Int? = null,
        val episode: Int? = null,
    )

    data class NativeEmbed(
        val name: String,
        val url: String,
    )

    data class YflixAjaxResponse(
        @JsonProperty("result") val result: String? = null,
    )

    data class TmdbSearchResults(
        @JsonProperty("results") val results: List<TmdbSearchItem>? = null,
    )

    data class TmdbSearchItem(
        @JsonProperty("id") val id: Int? = null,
    )

    data class TmdbDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds? = null,
        @JsonProperty("videos") val videos: TmdbVideos? = null,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>? = null,
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )

    data class TmdbVideos(
        @JsonProperty("results") val results: List<TmdbVideo>? = null,
    )

    data class TmdbVideo(
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("site") val site: String? = null,
    )

    data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class TmdbSeasonDetail(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
    )

    companion object {
        private const val ENC_MOVIES_FLIX = "https://enc-dec.app/api/enc-movies-flix?text="
        private const val DEC_MOVIES_FLIX = "https://enc-dec.app/api/dec-movies-flix"
    }
}
