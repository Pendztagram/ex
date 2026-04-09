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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data.contains("/api/")) {
            if (page > 1 && request.data.contains("/api/trending/top")) {
                return newHomePageResponse(request.name, emptyList())
            }
            val url = getApiPageUrl(request.data, page)
            val res = app.get(url, interceptor = cloudflareInterceptor)
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
        val request = app.get(url, interceptor = cloudflareInterceptor)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val title =
            document.selectFirst("div.data > h1")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")
                ?.trim().toString()
        val images = document.select("div.g-item")

        val poster = images
            .shuffled()
            .firstOrNull()
            ?.selectFirst("a")
            ?.attr("href")
            ?: document.select("div.poster > img").attr("src")
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex(",\\s?(\\d+)").find(
            document.select("span.date").text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (document.select("ul#section > li:nth-child(1)").text().contains("Episodes")
        ) TvType.TvSeries else TvType.Movie
        val description = if (tvType == TvType.Movie) document.select("div.wp-content > p").text().trim() else document.select("div.content > center > p:nth-child(3)").text().trim()
        val trailer = document.selectFirst("div.embed iframe")?.attr("src")
        val ratingValue = document.selectFirst("span.dt_rating_vgs")?.text()?.toDoubleOrNull()
        val actors = document.select("div.persons > div[itemprop=actor]").map {
            Actor(it.select("meta[itemprop=name]").attr("content"), it.select("img").attr("src"))
        }

        val recommendations = document.select("div.owl-item").map {
            val recName =
                it.selectFirst("a")!!.attr("href").removeSuffix("/").split("/").last()
            val recHref = it.selectFirst("a")!!.attr("href")
            val recPosterUrl = it.selectFirst("img")?.attr("src").toString()
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li").map {
                val href = it.select("a").attr("href")
                val name = fixTitle(it.select("div.episodiotitle > a").text().trim())
                val image = it.select("div.imagen > img").attr("src")
                val episode = it.select("div.numerando").text().replace(" ", "").split("-").last()
                    .toIntOrNull()
                val season = it.select("div.numerando").text().replace(" ", "").split("-").first()
                    .toIntOrNull()
                newEpisode(href)
                {
                        this.name=name
                        this.season=season
                        this.episode=episode
                        this.posterUrl=image
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingValue != null) this.score = Score.from10(ratingValue)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (ratingValue != null) this.score = Score.from10(ratingValue)
                addActors(actors)
                this.recommendations = recommendations
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

        val document = app.get(data, interceptor = cloudflareInterceptor).document
        document.select("ul#playeroptionsul > li").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ),
                referer = data,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
                interceptor = cloudflareInterceptor
            ).parsedSafe<ResponseHash>() ?: return@amap
            val metrix = AppUtils.parseJson<AesData>(json.embed_url).m
            val password = generateKey(json.key, metrix)
            val decrypted =
                AesHelper.cryptoAESHandler(json.embed_url, password.toByteArray(), false)
                    ?.fixBloat() ?: return@amap

          when {
                !decrypted.contains("youtube") ->
                    loadExtractor(decrypted,directUrl,subtitleCallback,callback)
                else -> return@amap
            }
        }

        return true
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

}
