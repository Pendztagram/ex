package com.dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Dramabox : MainAPI() {
    override var mainUrl = "https://drama.sansekai.my.id"
    private val apiUrl = "https://api.sansekai.my.id/api"

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
        val url = appendPage(request.data, page)
        val items = requestList<DramaItem>(url)
            .mapNotNull { it.toSearch() }
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
                val body = app.get(url, headers = interceptHeaders).text
                return parseJson<T>(body)
            }
            if (attempt == 0) kotlinx.coroutines.delay(400)
        }
        return null
    }

    private suspend inline fun <reified T> requestList(url: String): List<T> {
        return requestJson<List<T>>(url).orEmpty()
    }

    private fun appendPage(base: String, page: Int): String {
        val join = if (base.contains("?")) "&" else "?"
        return "$base${join}page=${if (page <= 0) 1 else page}"
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
