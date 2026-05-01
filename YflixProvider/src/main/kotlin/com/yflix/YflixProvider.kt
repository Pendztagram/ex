package com.yflix

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class YflixProvider : MainAPI() {
    override var mainUrl = "https://yflix.to"
    override var name = "Yflix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbApiKey = "b030404650f279792a8d3287232358e3"

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
        var found = false

        resolveNativeEmbeds(info).forEach { native ->
            runCatching {
                loadExtractor(native.url, info.watchUrl ?: baseReferer(native.url), subtitleCallback, callback)
            }.onSuccess {
                found = true
            }
        }

        if (found) return true

        val embeds = buildFallbackEmbeds(info)

        for (embed in embeds) {
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

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveNativeEmbeds(info: LinkData): List<NativeEmbed> {
        val watchUrl = info.watchUrl ?: return emptyList()
        val context = AcraApplication.context ?: return emptyList()
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var resultJson: String? = null

        val script = buildNativeResolveScript(info)

        handler.post {
            try {
                val wv = WebView(context).also { webView = it }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadsImagesAutomatically = true
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        fun pollResult(view: WebView, attempt: Int = 0) {
                            val resultScript = """
                                (function() {
                                  return window.__YFLIX_DONE === true ? (window.__YFLIX_RESULT || "[]") : null;
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(resultScript) { raw ->
                                if (raw != "null") {
                                    resultJson = raw.decodeJsString()
                                    latch.countDown()
                                } else if (attempt >= 30) {
                                    latch.countDown()
                                } else {
                                    handler.postDelayed({ pollResult(view, attempt + 1) }, 500L)
                                }
                            }
                        }
                        fun probe(attempt: Int = 0) {
                            val readinessScript = """
                                (function() {
                                  return !!(window.jQuery && window.x && typeof window.x.G === "function" && document.querySelector("#movie-rating"));
                                })();
                            """.trimIndent()
                            view.evaluateJavascript(readinessScript) { ready ->
                                if (ready == "true") {
                                    view.evaluateJavascript(script, null)
                                    pollResult(view)
                                } else if (attempt >= 20) {
                                    latch.countDown()
                                } else {
                                    handler.postDelayed({ probe(attempt + 1) }, 500L)
                                }
                            }
                        }
                        probe()
                    }
                }
                wv.loadUrl(watchUrl, mapOf("Referer" to "$mainUrl/"))
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        latch.await(18, TimeUnit.SECONDS)

        handler.post {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }

        val payload = resultJson
            ?.takeUnless { it.isBlank() || it == "null" }
            ?: return emptyList()

        return runCatching {
            JSONArray(payload).let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val url = item.optString("url").trim()
                        if (url.isBlank()) continue
                        add(
                            NativeEmbed(
                                name = item.optString("name").ifBlank { "Server ${index + 1}" },
                                url = url
                            )
                        )
                    }
                }.distinctBy { it.url }
            }
        }.getOrDefault(emptyList())
    }

    private fun buildNativeResolveScript(info: LinkData): String {
        val isTv = info.isTv
        val season = info.season ?: 0
        val episode = info.episode ?: 0
        val episodeHash = if (isTv) "#ep=$season,$episode" else null

        return """
            (function() {
              window.__YFLIX_DONE = false;
              window.__YFLIX_RESULT = null;
              (async function() {
              const parseHtml = (html) => new DOMParser().parseFromString(html || "", "text/html");
              const requestJson = (path) => new Promise((resolve, reject) => {
                window.jQuery.get(path)
                  .done((data) => resolve(data))
                  .fail((xhr, status, err) => reject(new Error(err || status || (xhr && xhr.status) || "request_failed")));
              });
              try {
                const root = document.querySelector("#movie-rating");
                const id = root ? root.getAttribute("data-id") : "";
                if (!id || !window.x || typeof window.x.G !== "function") {
                  window.__YFLIX_RESULT = "[]";
                  return;
                }

                const episodeResponse = await requestJson("/ajax/episodes/list?id=" + encodeURIComponent(id) + "&_=strict" + encodeURIComponent(id));
                const episodeDoc = parseHtml(episodeResponse && episodeResponse.result);
                let episodeNode = null;
                if (${if (isTv) "true" else "false"}) {
                  const hash = ${episodeHash?.let { "\"$it\"" } ?: "null"};
                  episodeNode = hash ? episodeDoc.querySelector('a[eid][href$="' + hash + '"]') : null;
                  if (!episodeNode) {
                    episodeNode = [...episodeDoc.querySelectorAll("ul.episodes[data-season] a[eid]")].find((node) => {
                      const seasonNode = node.closest("ul.episodes[data-season]");
                      const season = Number(seasonNode ? seasonNode.getAttribute("data-season") : 0);
                      const episode = Number(node.getAttribute("num") || 0);
                      return season === $season && episode === $episode;
                    }) || null;
                  }
                } else {
                  episodeNode = episodeDoc.querySelector("a[eid]");
                }
                if (!episodeNode) {
                  window.__YFLIX_RESULT = "[]";
                  return;
                }

                const eid = episodeNode.getAttribute("eid");
                if (!eid) {
                  window.__YFLIX_RESULT = "[]";
                  return;
                }

                const linksResponse = await requestJson("/ajax/links/list?eid=" + encodeURIComponent(eid) + "&_=strict" + encodeURIComponent(eid));
                const linksDoc = parseHtml(linksResponse && linksResponse.result);
                const servers = [...linksDoc.querySelectorAll("[data-lid]")].slice(0, 8);
                const resolved = [];

                for (const server of servers) {
                  const lid = server.getAttribute("data-lid");
                  if (!lid) continue;
                  try {
                    const viewResponse = await requestJson("/ajax/links/view?id=" + encodeURIComponent(lid) + "&_=strict" + encodeURIComponent(lid));
                    const decoded = window.x.G((viewResponse && viewResponse.result) || "");
                    const json = JSON.parse(decoded || "{}");
                    if (!json.url) continue;
                    resolved.push({
                      name: (server.textContent || "").trim() || server.getAttribute("title") || "Server",
                      url: json.url
                    });
                  } catch (_) {
                  }
                }

                window.__YFLIX_RESULT = JSON.stringify(resolved);
              } catch (_) {
                window.__YFLIX_RESULT = "[]";
              } finally {
                window.__YFLIX_DONE = true;
              }
              })();
            })();
        """.trimIndent()
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

    private fun String?.decodeJsString(): String? {
        if (this == null || this == "null") return null
        return runCatching { JSONArray("[$this]").getString(0) }.getOrDefault(this)
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
}
