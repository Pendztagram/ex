package com.klikxxi

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


class Klikxxi : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://klikxxi.me"
    override var name = "Klikxxi🎭"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    private val turnstileInterceptor = KlikxxiTurnstileInterceptor()
    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    )

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = turnstileInterceptor,
            headers = defaultHeaders,
            referer = ref
        )
    }

    private suspend fun requestPost(
        url: String,
        data: Map<String, String>,
        ref: String? = null
    ): NiceResponse {
        return app.post(
            url,
            interceptor = turnstileInterceptor,
            headers = defaultHeaders,
            referer = ref,
            data = data
        )
    }
    

    /** Main page: Film Terbaru & Series Terbaru */
    override val mainPage = mainPageOf(
        "?s=&search=advanced&post_type=movie&index=&orderby=&genre=&movieyear=&country=&quality=&paged=%d" to "Film Terbaru",
        "tv/page/%d/" to "Series Terbaru",
        "category/western-series/page/%d/" to "Western Series",
        "category/india-series/page/%d/" to "Indian Series",  
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val requestData = when {
        request.data.contains("%d") -> request.data.format(page)
        else -> request.data
    }

    val url = if (page == 1 && request.data.contains("page/%d/")) {
        // Untuk kategori path-style, page pertama cukup pakai base path tanpa suffix page.
        "$mainUrl/${request.data.replace("page/%d/", "")}"
    } else {
        "$mainUrl/$requestData"
    }.replace("//", "/")
     .replace(":/", "://")

    val document = request(url).document

    val items = document.select(
        "article.has-post-thumbnail, article.item, article.item-infinite, div.latestMovie article, div.latestSeri article"
    )
        .mapNotNull { it.toSearchResult() }

    return newHomePageResponse(request.name, items)
}


    /* =======================
       Search & List Handling
       ======================= */

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst(
            "h2.entry-title > a, h3.entry-title > a, h2 > a, h3 > a, a[rel=bookmark], a[href][title], a[href]"
        ) ?: return null

        val href = fixUrl(linkElement.attr("href").ifBlank {
            selectFirst("a")?.attr("href") ?: return null
        })

        val rawTitle = listOfNotNull(
            selectFirst("h2.entry-title")?.text(),
            selectFirst("h3.entry-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            linkElement.attr("title").takeIf { it.isNotBlank() },
            select("a[href]")
                .map { it.text().trim() }
                .filter {
                    it.isNotBlank() &&
                        !it.equals("Watch Movie", true) &&
                        !it.equals("Trailer", true) &&
                        !it.equals("Next", true) &&
                        !it.equals("Previous", true)
                }
                .maxByOrNull { it.length }
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        val title = rawTitle
            .removePrefix("Permalink to: ")
            .ifBlank { linkElement.text() }
            .trim()

        if (title.isBlank()) return null

        // Poster – support src, srcset, data-lazy-src, dll + ambil resolusi terbesar
        val posterElement = this.selectFirst("img.wp-post-image, img.attachment-large, img")
        val posterUrl = posterElement?.fixPoster()?.let { fixUrl(it) }

        val quality = this.selectFirst(".gmr-quality-item")?.let { el ->
    // 1. Check if text directly available: <div class="gmr-quality-item">HD</div>
        val directText = el.text().trim()
        if (directText.isNotEmpty()) {
        directText
        } else {
        // 2. Inside <a> : <a>HDTS2</a>
        val aText = el.selectFirst("a")?.text()?.trim()
        if (!aText.isNullOrBlank()) {
            aText
        } else {
            // 3. Fallback from class: hd, sd, hdrip, hdts2, etc.
            el.classNames().firstOrNull { cls ->
                cls.matches(
                    Regex(
                        "hd|sd|cam|ts|hdts|hdts2|hdrip|webrip|bluray|brrip|fhd|uhd|4k",
                        RegexOption.IGNORE_CASE
                    )
                )
            }?.uppercase()
        }
    }
}

        val typeText = listOfNotNull(
            selectFirst(".gmr-posttype-item")?.text()?.trim(),
            selectFirst(".gmr-numbeps, .mli-eps")?.text()?.trim(),
            text().takeIf { it.contains("TV Show", true) || it.contains("Eps", true) || it.contains("Episode", true) }
        ).joinToString(" ")
        val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val isSeries = typeText.equals("TV Show", ignoreCase = true) ||
            typeText.contains("Eps", true) ||
            typeText.contains("Episode", true) ||
            href.contains("/tv/", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (!quality.isNullOrBlank()) addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = request("$mainUrl/?s=$query").document
        return document.select("article.item, article.has-post-thumbnail, article.item-infinite")
            .mapNotNull { it.toSearchResult() }
    }

    /** Kadang rekomendasi punya struktur HTML beda */
    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterElement = this.selectFirst("img.wp-post-image, img.attachment-large, img")
        val posterUrl = posterElement?.fixPoster()?.let { fixUrl(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }


    /* =======================
       Load Detail Page
       ======================= */

    override suspend fun load(url: String): LoadResponse {
        val fetch = request(url, ref = mainUrl)
        val document = fetch.document

        // Title tanpa Season/Episode/Year
        val title = document
            .selectFirst("h1.entry-title, div.mvic-desc h3")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.substringBefore("(")
            ?.trim()
            .orEmpty()

        val poster = document
            .selectFirst("figure.pull-left > img, .mvic-thumb img, .poster img")
            .fixPoster()
            ?.let { fixUrl(it) }

        val description = document.selectFirst(
            "div[itemprop=description] > p, " +
                "div.desc p.f-desc, " +
                "div.entry-content > p"
        )
            ?.text()
            ?.trim()

        val tags = document.select("strong:contains(Genre) ~ a").eachText()

        val year = document
            .select("div.gmr-moviedata strong:contains(Year:) > a")
            .text()
            .toIntOrNull()

        val trailer = document
            .selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")
            ?.attr("href")

        val rating = document
            .selectFirst("span[itemprop=ratingValue]")
            ?.text()
            ?.toDoubleOrNull()

        val actors = document
            .select("div.gmr-moviedata span[itemprop=actors] a")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }

        val recommendations = document
    .select("article.item.col-md-20")
    .mapNotNull { it.toRecommendResult() }

        /* ===== Ambil Episodes (kalau TV Series) ===== */

        val seasonBlocks = document.select("div.gmr-season-block")
        val allEpisodes = mutableListOf<Episode>()

        seasonBlocks.forEach { block ->
            val seasonTitle = block.selectFirst("h3.season-title")?.text()?.trim()
            val seasonNumber = Regex("(\\d+)")
                .find(seasonTitle ?: "")
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            val eps = block.select("div.gmr-season-episodes a")
                .filter { a ->
                    val t = a.text().lowercase()
                    !t.contains("view all") && !t.contains("batch")
                }
                .mapIndexedNotNull { index, epLink ->
                    val hrefEp = epLink.attr("href")
                        .takeIf { it.isNotBlank() }
                        ?.let { fixUrl(it) }
                        ?: return@mapIndexedNotNull null

                    val name = epLink.text().trim()

                    val episodeNum = Regex("E(p|ps)?(\\d+)", RegexOption.IGNORE_CASE)
                        .find(name)
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull()
                        ?: (index + 1)

                    newEpisode(hrefEp) {
                        this.name = name
                        this.season = seasonNumber
                        this.episode = episodeNum
                    }
                }

            allEpisodes.addAll(eps)
        }

        val episodes = allEpisodes
            .sortedWith(compareBy({ it.season }, { it.episode }))

        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                addActors(actors)
                addTrailer(trailer)
                if (rating != null) addScore(rating.toString(), 10)
                this.recommendations = recommendations
            }
        }
    }

    /* =======================
       Links / Streams
       ======================= */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data, ref = mainUrl).document
        val postId = document
            .selectFirst("div#muvipro_player_content_id")
            ?.attr("data-id")

        if (postId.isNullOrBlank()) return false

        document.select("div.tab-content-ajax").forEach { tab ->
            val tabId = tab.attr("id")
            if (tabId.isNullOrBlank()) return@forEach

            val response = requestPost(
                "$mainUrl/wp-admin/admin-ajax.php",
                ref = data,
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to tabId,
                    "post_id" to postId
                )
            ).document

            val iframe = response.selectFirst("iframe")?.getIframeAttr() ?: return@forEach
            val link = httpsify(iframe)

            loadExtractor(link, mainUrl, subtitleCallback, callback)
        }

        return true
    }

    /* =======================
       Helper Functions
       ======================= */

    /** Ambil URL poster terbaik (srcset terbesar, data-lazy-src, dst) */
    private fun Element?.fixPoster(): String? {
    if (this == null) return null

    // Prioritas 1: srcset (ambil resolusi terbesar)
    if (this.hasAttr("srcset")) {
        val srcset = this.attr("srcset").trim()
        val best = srcset.split(",")
            .map { it.trim().split(" ")[0] }
            .lastOrNull()  // paling besar selalu di akhir
        if (!best.isNullOrBlank()) return fixUrl(best.fixImageQuality())
    }

    // Prioritas 2: data-src atau data-lazy
    val dataSrc = when {
        this.hasAttr("data-lazy-src") -> this.attr("data-lazy-src")
        this.hasAttr("data-src") -> this.attr("data-src")
        else -> null
    }
    if (!dataSrc.isNullOrBlank()) return fixUrl(dataSrc.fixImageQuality())

    // Prioritas 3: src biasa
    val src = this.attr("src")
    if (!src.isNullOrBlank()) return fixUrl(src.fixImageQuality())

    return null
}

    /** Ambil src untuk iframe, support data-litespeed-src */
    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() }
            ?: this?.attr("src")
    }

    /** Hapus pattern -WIDTHxHEIGHT sebelum ekstensi */
    private fun String?.fixImageQuality(): String {
        if (this == null) return ""
        val regex = Regex("-\\d+x\\d+(?=\\.(webp|jpg|jpeg|png))", RegexOption.IGNORE_CASE)
        return this.replace(regex, "")
    }

    /** Base URL dari sebuah URL (scheme + host) */
    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

class KlikxxiTurnstileInterceptor(
    private val targetCookies: List<String> = listOf("cf_clearance", "__cf_bm")
) : Interceptor {
    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 60
        private const val PAGE_WAIT_SECONDS = 45L
    }

    private fun getCookieHeader(url: String, domainUrl: String): String {
        val manager = CookieManager.getInstance()
        return manager.getCookie(url) ?: manager.getCookie(domainUrl) ?: ""
    }

    private fun getCookieValue(url: String, domainUrl: String): String? {
        val raw = getCookieHeader(url, domainUrl)
        if (raw.isBlank()) return null
        return raw.split(";")
            .map { it.trim() }
            .firstNotNullOfOrNull { cookie ->
                targetCookies.firstOrNull { target -> cookie.startsWith("$target=") }
                    ?.let { cookie.substringAfter("=") }
                    ?.takeIf { it.isNotBlank() }
            }
    }

    private fun invalidateCookie(domainUrl: String) {
        CookieManager.getInstance().apply {
            targetCookies.forEach { cookie ->
                setCookie(domainUrl, "$cookie=; Max-Age=0")
            }
            flush()
        }
    }

    private fun hasChallenge(response: Response): Boolean {
        if (response.code == 403 || response.code == 429 || response.code == 503) return true

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return false

        val preview = runCatching { response.peekBody(128 * 1024).string() }.getOrDefault("")
        if (preview.isBlank()) return false

        val challengeHints = listOf(
            "cf-challenge",
            "cf-browser-verification",
            "cf_clearance",
            "challenge-platform",
            "Performing security verification",
            "Verifying you are human",
            "Just a moment",
            "Attention Required",
            "/cdn-cgi/challenge-platform/"
        )
        return challengeHints.any { preview.contains(it, ignoreCase = true) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()
        if (getCookieValue(url, domainUrl) != null) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", getCookieHeader(url, domainUrl))
                    .build()
            )
            if (!hasChallenge(response)) return response
            response.close()
            invalidateCookie(domainUrl)
        }

        val context = AcraApplication.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var resolvedUserAgent = originalRequest.header("User-Agent") ?: ""
        val challengeLatch = CountDownLatch(1)

        handler.post {
            try {
                val wv = WebView(context).also { webView = it }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadsImagesAutomatically = true
                    if (resolvedUserAgent.isNotBlank()) userAgentString = resolvedUserAgent
                    resolvedUserAgent = userAgentString
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        cookieManager.flush()
                        if (getCookieValue(finishedUrl, domainUrl) != null) {
                            challengeLatch.countDown()
                        }
                    }
                }
                wv.loadUrl(url)
            } catch (e: Exception) {
                challengeLatch.countDown()
                e.printStackTrace()
            }
        }

        challengeLatch.await(PAGE_WAIT_SECONDS, TimeUnit.SECONDS)

        var attempts = 0
        while (attempts < MAX_ATTEMPTS && getCookieValue(url, domainUrl) == null) {
            Thread.sleep(POLL_INTERVAL_MS)
            cookieManager.flush()
            attempts++
        }

        handler.post {
            try {
                webView?.apply {
                    stopLoading()
                    clearCache(false)
                    destroy()
                }
                webView = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val finalCookies = getCookieHeader(url, domainUrl)
        val finalResponse = chain.proceed(
            originalRequest.newBuilder()
                .header("Cookie", finalCookies)
                .apply { if (resolvedUserAgent.isNotBlank()) header("User-Agent", resolvedUserAgent) }
                .build()
        )

        if (!hasChallenge(finalResponse)) return finalResponse

        return finalResponse
    }
}
