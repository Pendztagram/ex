package com.dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Dramabox : MainAPI() {
    private fun b64(v: String): String = String(java.util.Base64.getDecoder().decode(v))
    override var mainUrl = b64("aHR0cHM6Ly9kcmFtYS5zYW5zZWthaS5teS5pZA==")
    private val apiUrl = b64("aHR0cHM6Ly9hcGkuc2Fuc2VrYWkubXkuaWQvYXBp")
    private val cloudflareInterceptor by lazy { CloudflareKiller() }
    private val rateMutex = Mutex()
    private var lastRequestAt = 0L
    private val minRequestGapMs = 1200L
    private val listCache = LinkedHashMap<String, Pair<Long, String>>()
    private val listCacheTtlMs = 45_000L
    private val listCacheSize = 24

    override var name = "DramaBox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$apiUrl/dramabox/foryou" to "Untukmu",
        "$apiUrl/dramabox/latest" to "Terbaru",
        "$apiUrl/dramabox/trending" to "Trending",
        "$apiUrl/dramabox/vip" to "VIP",
        "$apiUrl/dramabox/dubindo?classify=terpopuler" to "Dub Indo Populer",
        "$apiUrl/dramabox/dubindo?classify=terbaru" to "Dub Indo Terbaru",
    )

    private val interceptHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = appendPage(baseUrl, page)
        val items = requestList<DramaItem>(url).ifEmpty {
            if (url != baseUrl) requestList(baseUrl) else emptyList()
        }.mapNotNull { it.toSearch() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/dramabox/search?query=${encode(query.trim())}"
        return requestList<DramaItem>(url).mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/")
        val detail = requestJson<DramaItem>("$apiUrl/dramabox/detail?bookId=$bookId")
            ?: throw ErrorLoadingException("Detail drama tidak ditemukan")

        val episodes = requestList<DramaEpisode>("$apiUrl/dramabox/allepisode?bookId=$bookId")
            .sortedBy { it.chapterIndex ?: Int.MAX_VALUE }
            .mapIndexedNotNull { idx, ep ->
                val sources = ep.cdnList.orEmpty()
                    .flatMap { it.videoPathList.orEmpty() }
                    .mapNotNull { p ->
                        val link = p.videoPath?.trim().orEmpty()
                        if (link.isBlank()) null else VideoSource("${p.quality ?: 0}p", link, p.quality)
                    }
                    .distinctBy { it.url }
                    .sortedByDescending { it.quality ?: 0 }

                if (sources.isEmpty()) return@mapIndexedNotNull null
                val epNo = (ep.chapterIndex ?: idx) + 1
                newEpisode(
                    EpisodeData(bookId, ep.chapterId, epNo, ep.chapterName ?: "EP $epNo", sources).toJson()
                ) {
                    name = ep.chapterName ?: "EP $epNo"
                    episode = epNo
                    posterUrl = ep.chapterImg ?: detail.coverWap
                }
            }

        return newTvSeriesLoadResponse(
            detail.bookName ?: "DramaBox",
            "$mainUrl/drama/$bookId",
            TvType.AsianDrama,
            episodes
        ) {
            posterUrl = detail.coverWap
            plot = detail.introduction
            tags = detail.tags.orEmpty()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ep = parseJson<EpisodeData>(data)
        val seen = HashSet<String>()
        ep.sources.orEmpty().forEach { src ->
            val u = src.url?.trim().orEmpty()
            if (u.isBlank() || !seen.add(u)) return@forEach
            callback.invoke(
                newExtractorLink(name, "$name ${src.label ?: "Auto"}", u,
                    if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    quality = src.quality ?: Qualities.Unknown.value
                    headers = interceptHeaders
                    referer = "$mainUrl/"
                }
            )
        }
        return seen.isNotEmpty()
    }

    private suspend inline fun <reified T> requestJson(url: String): T? {
        repeat(2) { attempt ->
            runCatching {
                val bodyFromCache = takeCached(url)
                if (bodyFromCache != null) return parseJson<T>(bodyFromCache)

                throttleRequest()
                // API domain is usually reachable directly; avoid long Cloudflare wait on first attempt.
                val plain = app.get(
                    url,
                    headers = interceptHeaders,
                    referer = "$mainUrl/",
                    timeout = 20L
                )
                val plainBody = plain.text
                if (!looksLikeChallenge(plainBody)) {
                    putCached(url, plainBody)
                    return parseJson<T>(plainBody)
                }

                throttleRequest()
                val body = app.get(
                    url,
                    headers = interceptHeaders,
                    interceptor = cloudflareInterceptor,
                    referer = "$mainUrl/",
                    timeout = 25L
                ).text
                if (looksLikeChallenge(body)) return null
                putCached(url, body)
                return parseJson<T>(body)
            }
            if (attempt == 0) delay(400)
        }
        return null
    }

    private suspend inline fun <reified T> requestList(url: String): List<T> {
        return requestJson<List<T>>(url).orEmpty()
    }

    private fun appendPage(base: String, page: Int): String {
        val pagedPaths = listOf("/foryou", "/dubindo")
        if (pagedPaths.none { base.contains(it) }) return base
        val join = if (base.contains("?")) "&" else "?"
        return "$base${join}page=${if (page <= 0) 1 else page}"
    }

    private fun looksLikeChallenge(body: String): Boolean {
        val preview = body.take(32_768)
        if (preview.isBlank()) return false
        val hints = listOf(
            "cf-challenge",
            "challenge-platform",
            "Attention Required",
            "Just a moment",
            "Verifying you are human",
            "/cdn-cgi/challenge-platform/"
        )
        return hints.any { preview.contains(it, ignoreCase = true) }
    }

    private suspend fun throttleRequest() {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = minRequestGapMs - (now - lastRequestAt)
            if (waitMs > 0) delay(waitMs)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun takeCached(url: String): String? {
        val now = System.currentTimeMillis()
        val hit = listCache[url] ?: return null
        if ((now - hit.first) > listCacheTtlMs) {
            listCache.remove(url)
            return null
        }
        return hit.second
    }

    private fun putCached(url: String, body: String) {
        val isListLike = url.contains("/dramabox/foryou") ||
            url.contains("/dramabox/latest") ||
            url.contains("/dramabox/trending") ||
            url.contains("/dramabox/vip") ||
            url.contains("/dramabox/dubindo") ||
            url.contains("/dramabox/search")
        if (!isListLike) return
        if (body.isBlank() || looksLikeChallenge(body)) return
        listCache[url] = System.currentTimeMillis() to body
        while (listCache.size > listCacheSize) {
            val firstKey = listCache.keys.firstOrNull() ?: break
            listCache.remove(firstKey)
        }
    }

    private fun DramaItem.toSearch(): SearchResponse? {
        val id = bookId?.trim().orEmpty()
        val title = bookName?.trim().orEmpty()
        if (id.isBlank() || title.isBlank()) return null
        return newTvSeriesSearchResponse(title, "$mainUrl/drama/$id", TvType.AsianDrama) {
            posterUrl = coverWap
        }
    }

    private fun encode(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    data class EpisodeData(
        val bookId: String? = null,
        val chapterId: String? = null,
        val episodeNumber: Int? = null,
        val episodeName: String? = null,
        val sources: List<VideoSource>? = null,
    )

    data class VideoSource(
        val label: String? = null,
        val url: String? = null,
        val quality: Int? = null,
    )

    data class DramaItem(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("coverWap") val coverWap: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
    )

    data class DramaEpisode(
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("chapterImg") val chapterImg: String? = null,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null,
    )

    data class CdnItem(
        @JsonProperty("videoPathList") val videoPathList: List<VideoPath>? = null,
    )

    data class VideoPath(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("videoPath") val videoPath: String? = null,
    )
}
