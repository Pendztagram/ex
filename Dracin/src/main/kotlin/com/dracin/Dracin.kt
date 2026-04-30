package com.dracin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Dracin : MainAPI() {
    override var mainUrl = "https://drama.sansekai.my.id"
    private val apiUrl = "https://api.sansekai.my.id/api"

    override var name = "Dracin"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    private enum class SourceKey { DRAMABOX, REELSHORT, SHORTMAX, NETSHORT, GOODSHORT, FREEREELS }

    override val mainPage = mainPageOf(
        "DRAMABOX|$apiUrl/dramabox/foryou" to "DramaBox - Untukmu",
        "DRAMABOX|$apiUrl/dramabox/latest" to "DramaBox - Terbaru",
        "REELSHORT|$apiUrl/reelshort/foryou" to "ReelShort - Untukmu",
        "SHORTMAX|$apiUrl/shortmax/foryou" to "ShortMax - Untukmu",
        "NETSHORT|$apiUrl/netshort/foryou" to "NetShort - Untukmu",
        "GOODSHORT|$apiUrl/goodshort/foryou" to "GoodShort - Untukmu",
        "FREEREELS|$apiUrl/freereels/foryou" to "FreeReels - Untukmu",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (source, baseUrl) = parseMainPageData(request.data)
        val pageUrl = buildPageUrl(baseUrl, page)
        val body = app.get(pageUrl).text

        val items = when (source) {
            SourceKey.DRAMABOX -> app.get(pageUrl).parsedSafe<List<DramaBoxItem>>().orEmpty().mapNotNull {
                toSearchResponse(source, it.bookId, it.bookName, it.coverWap)
            }

            SourceKey.REELSHORT -> parseJson<ReelShortListResponse>(body).data?.lists.orEmpty().mapNotNull {
                toSearchResponse(source, it.bookId, it.bookTitle, it.bookPic)
            }

            SourceKey.SHORTMAX -> parseJson<ShortMaxListResponse>(body).results.orEmpty().mapNotNull {
                toSearchResponse(source, it.shortPlayId?.toString(), it.name, it.cover)
            }

            SourceKey.NETSHORT -> parseJson<NetShortListResponse>(body).contentInfos.orEmpty().mapNotNull {
                toSearchResponse(source, it.shortPlayId, it.shortPlayName, it.shortPlayCover)
            }

            SourceKey.GOODSHORT -> parseJson<GoodShortListResponse>(body).data?.records.orEmpty()
                .flatMap { it.items.orEmpty() }
                .mapNotNull { toSearchResponse(source, it.bookId, it.bookName, it.cover) }

            SourceKey.FREEREELS -> parseJson<FreeReelsListResponse>(body).data?.items.orEmpty().mapNotNull {
                toSearchResponse(source, it.key, it.title, it.cover)
            }
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = encode(query.trim())
        val merged = mutableListOf<SearchResponse>()

        runCatching {
            app.get("$apiUrl/dramabox/search?query=$q").parsedSafe<List<DramaBoxItem>>().orEmpty().mapNotNull {
                toSearchResponse(SourceKey.DRAMABOX, it.bookId, it.bookName, it.coverWap)
            }
        }.getOrDefault(emptyList()).also { merged += it }

        runCatching {
            parseJson<ReelShortListResponse>(app.get("$apiUrl/reelshort/search?query=$q&page=1").text)
                .data?.lists.orEmpty().mapNotNull {
                    toSearchResponse(SourceKey.REELSHORT, it.bookId, it.bookTitle, it.bookPic)
                }
        }.getOrDefault(emptyList()).also { merged += it }

        runCatching {
            parseJson<ShortMaxListResponse>(app.get("$apiUrl/shortmax/search?query=$q").text)
                .results.orEmpty().mapNotNull {
                    toSearchResponse(SourceKey.SHORTMAX, it.shortPlayId?.toString(), it.name, it.cover)
                }
        }.getOrDefault(emptyList()).also { merged += it }

        runCatching {
            parseJson<NetShortListResponse>(app.get("$apiUrl/netshort/search?query=$q").text)
                .contentInfos.orEmpty().mapNotNull {
                    toSearchResponse(SourceKey.NETSHORT, it.shortPlayId, it.shortPlayName, it.shortPlayCover)
                }
        }.getOrDefault(emptyList()).also { merged += it }

        runCatching {
            parseJson<GoodShortSearchResponse>(app.get("$apiUrl/goodshort/search?query=$q").text)
                .data?.records.orEmpty().mapNotNull {
                    toSearchResponse(SourceKey.GOODSHORT, it.bookId, it.bookName, it.cover)
                }
        }.getOrDefault(emptyList()).also { merged += it }

        runCatching {
            parseJson<FreeReelsListResponse>(app.get("$apiUrl/freereels/search?query=$q").text)
                .data?.items.orEmpty().mapNotNull {
                    toSearchResponse(SourceKey.FREEREELS, it.key, it.title, it.cover)
                }
        }.getOrDefault(emptyList()).also { merged += it }

        return merged.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val (source, id) = parseContentUrl(url)
        return when (source) {
            SourceKey.DRAMABOX -> loadDramaBox(id)
            SourceKey.REELSHORT -> loadReelShort(id)
            SourceKey.SHORTMAX -> loadShortMax(id)
            SourceKey.NETSHORT -> loadNetShort(id)
            SourceKey.GOODSHORT -> loadGoodShort(id)
            SourceKey.FREEREELS -> loadFreeReels(id)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val loadData = parseJson<EpisodeLoadData>(data)
        val source = loadData.source?.let { SourceKey.valueOf(it) } ?: return false

        val links = mutableListOf<VideoSource>()
        links += loadData.sources.orEmpty()
        loadData.subtitles.orEmpty().forEach { sub ->
            val subUrl = sub.url?.trim().orEmpty()
            if (subUrl.isNotBlank()) subtitleCallback(SubtitleFile(sub.lang ?: "Indonesia", subUrl))
        }

        if (links.isEmpty()) {
            when (source) {
                SourceKey.REELSHORT -> {
                    val body = app.get("$apiUrl/reelshort/episode?bookId=${loadData.contentId}&episodeNumber=${loadData.episodeNumber}").text
                    val parsed = parseJson<ReelShortEpisodeResponse>(body)
                    links += parsed.videoList.orEmpty().mapNotNull {
                        val media = it.url?.trim()
                        if (media.isNullOrBlank()) null else VideoSource("${it.encode ?: "Auto"} ${it.quality ?: 0}p", media, it.quality)
                    }
                }

                SourceKey.SHORTMAX -> {
                    val body = app.get("$apiUrl/shortmax/episode?shortPlayId=${loadData.contentId}&episodeNumber=${loadData.episodeNumber}").text
                    val parsed = parseJson<ShortMaxEpisodeResponse>(body)
                    val video = parsed.episode?.videoUrl
                    if (video != null) {
                        listOf(video.video1080 to 1080, video.video720 to 720, video.video480 to 480).forEach { (u, q) ->
                            if (!u.isNullOrBlank()) links += VideoSource("$q p", u, q)
                        }
                    }
                }

                else -> {
                    val raw = loadData.raw.orEmpty()
                    extractMediaLinks(raw).forEach { media -> links += VideoSource("Auto", media, Qualities.Unknown.value) }
                }
            }
        }

        val unique = LinkedHashSet<String>()
        links.forEach { sourceLink ->
            val mediaUrl = sourceLink.url?.trim().orEmpty()
            if (mediaUrl.isBlank() || !unique.add(mediaUrl)) return@forEach

            callback.invoke(
                newExtractorLink(name, "$name ${sourceLink.label ?: "Auto"}", mediaUrl,
                    if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    referer = "$mainUrl/"
                    quality = sourceLink.quality ?: Qualities.Unknown.value
                }
            )
        }
        return unique.isNotEmpty()
    }

    private suspend fun loadDramaBox(id: String): LoadResponse {
        val detail = app.get("$apiUrl/dramabox/detail?bookId=$id").parsedSafe<DramaBoxItem>()
            ?: throw ErrorLoadingException("Detail DramaBox tidak ditemukan")

        val episodes = app.get("$apiUrl/dramabox/allepisode?bookId=$id").parsedSafe<List<DramaBoxEpisode>>().orEmpty()
            .sortedBy { it.chapterIndex ?: Int.MAX_VALUE }
            .mapIndexedNotNull { index, ep ->
                val sources = ep.cdnList.orEmpty().flatMap { it.videoPathList.orEmpty() }.mapNotNull {
                    val media = it.videoPath?.trim()
                    if (media.isNullOrBlank()) null else VideoSource("${it.quality ?: 0}p", media, it.quality)
                }.distinctBy { it.url }
                if (sources.isEmpty()) return@mapIndexedNotNull null
                val no = (ep.chapterIndex ?: index) + 1
                newEpisode(EpisodeLoadData(SourceKey.DRAMABOX.name, id, no, ep.chapterName, sources).toJson()) {
                    name = ep.chapterName ?: "EP $no"
                    episode = no
                    posterUrl = ep.chapterImg ?: detail.coverWap
                }
            }

        return newTvSeriesLoadResponse(detail.bookName ?: "DramaBox", buildUrl(SourceKey.DRAMABOX, id), TvType.AsianDrama, episodes) {
            posterUrl = detail.coverWap
            plot = detail.introduction
            tags = detail.tags.orEmpty()
        }
    }

    private suspend fun loadReelShort(id: String): LoadResponse {
        val detail = parseJson<ReelShortDetailResponse>(app.get("$apiUrl/reelshort/detail?bookId=$id").text)
        val title = detail.title ?: "ReelShort"
        val episodes = detail.chapters.orEmpty().mapIndexed { index, ep ->
            val no = ep.serialNumber ?: ep.index ?: index + 1
            newEpisode(EpisodeLoadData(SourceKey.REELSHORT.name, id, no, ep.title).toJson()) {
                name = ep.title ?: "Episode $no"
                episode = no
                posterUrl = detail.cover
            }
        }
        return newTvSeriesLoadResponse(title, buildUrl(SourceKey.REELSHORT, id), TvType.AsianDrama, episodes) {
            posterUrl = detail.cover
            plot = detail.description
        }
    }

    private suspend fun loadShortMax(id: String): LoadResponse {
        val detail = parseJson<ShortMaxDetailResponse>(app.get("$apiUrl/shortmax/detail?shortPlayId=$id").text).data
            ?: throw ErrorLoadingException("Detail ShortMax tidak ditemukan")
        val total = detail.totalEpisodes ?: 0
        val episodes = (1..total.coerceAtMost(200)).map { no ->
            newEpisode(EpisodeLoadData(SourceKey.SHORTMAX.name, id, no, "Episode $no").toJson()) {
                name = "Episode $no"
                episode = no
                posterUrl = detail.picUrl
            }
        }
        return newTvSeriesLoadResponse(detail.shortPlayName ?: "ShortMax", buildUrl(SourceKey.SHORTMAX, id), TvType.AsianDrama, episodes) {
            posterUrl = detail.picUrl
            plot = detail.summary
            tags = detail.labelResponseList.orEmpty().mapNotNull { it.labelName }
        }
    }

    private suspend fun loadFreeReels(id: String): LoadResponse {
        val body = app.get("$apiUrl/freereels/detailAndAllEpisode?key=$id").text
        val links = extractMediaLinks(body).map { VideoSource("Auto", it, Qualities.Unknown.value) }
        val title = parseJson<FreeReelsDetailResponse>(body).data?.info?.name ?: "FreeReels"
        val cover = parseJson<FreeReelsDetailResponse>(body).data?.info?.cover
        val episodes = listOf(
            newEpisode(
                EpisodeLoadData(
                    source = SourceKey.FREEREELS.name,
                    contentId = id,
                    episodeNumber = 1,
                    episodeName = "Episode 1",
                    sources = links,
                    raw = body
                ).toJson()
            ) {
                name = "Episode 1"
                episode = 1
                posterUrl = cover
            }
        )
        return newTvSeriesLoadResponse(title, buildUrl(SourceKey.FREEREELS, id), TvType.AsianDrama, episodes) {
            posterUrl = cover
        }
    }

    private suspend fun loadGoodShort(id: String): LoadResponse {
        val detail = parseJson<GoodShortAllEpisodeResponse>(app.get("$apiUrl/goodshort/allepisode?bookId=$id").text).data
            ?: throw ErrorLoadingException("Detail GoodShort tidak ditemukan")
        val episodes = detail.downloadList.orEmpty().sortedBy { it.index ?: Int.MAX_VALUE }.mapIndexedNotNull { idx, ep ->
            val sources = ep.multiVideos.orEmpty().mapNotNull {
                val url = it.filePath?.trim()
                if (url.isNullOrBlank()) null else VideoSource(it.type ?: "Auto", url, parseQuality(it.type))
            }.sortedByDescending { it.quality ?: 0 }
            if (sources.isEmpty()) return@mapIndexedNotNull null
            val no = (ep.index ?: idx) + 1
            newEpisode(
                EpisodeLoadData(
                    source = SourceKey.GOODSHORT.name,
                    contentId = id,
                    episodeNumber = no,
                    episodeName = "EP ${ep.chapterName ?: no}",
                    sources = sources
                ).toJson()
            ) {
                name = "EP ${ep.chapterName ?: no}"
                episode = no
                posterUrl = ep.image ?: detail.bookCover
            }
        }
        return newTvSeriesLoadResponse(detail.bookName ?: "GoodShort", buildUrl(SourceKey.GOODSHORT, id), TvType.AsianDrama, episodes) {
            posterUrl = detail.bookCover
            plot = detail.introduction
        }
    }

    private suspend fun loadNetShort(id: String): LoadResponse {
        val detail = parseJson<NetShortAllEpisodeResponse>(app.get("$apiUrl/netshort/allepisode?shortPlayId=$id").text)
        val episodes = detail.shortPlayEpisodeInfos.orEmpty().sortedBy { it.episodeNo ?: Int.MAX_VALUE }.mapIndexedNotNull { idx, ep ->
            val url = ep.playVoucher?.trim().orEmpty()
            if (url.isBlank()) return@mapIndexedNotNull null
            val no = ep.episodeNo ?: idx + 1
            val subtitles = ep.subtitleList.orEmpty().mapNotNull {
                val u = it.url?.trim()
                if (u.isNullOrBlank()) null else EpisodeSubtitle(lang = "id", url = u)
            }
            newEpisode(
                EpisodeLoadData(
                    source = SourceKey.NETSHORT.name,
                    contentId = id,
                    episodeNumber = no,
                    episodeName = "Episode $no",
                    sources = listOf(VideoSource(ep.playClarity ?: "Auto", url, parseQuality(ep.playClarity))),
                    subtitles = subtitles
                ).toJson()
            ) {
                name = "Episode $no"
                episode = no
                posterUrl = ep.episodeCover ?: detail.shortPlayCover
            }
        }
        return newTvSeriesLoadResponse(detail.shortPlayName ?: "NetShort", buildUrl(SourceKey.NETSHORT, id), TvType.AsianDrama, episodes) {
            posterUrl = detail.shortPlayCover
            plot = detail.shotIntroduce
            tags = detail.shortPlayLabels.orEmpty()
        }
    }

    private suspend fun loadGenericAllEpisode(source: SourceKey, id: String, endpoint: String): LoadResponse {
        val body = app.get(endpoint).text
        val links = extractMediaLinks(body).map { VideoSource("Auto", it, Qualities.Unknown.value) }
        val episodes = listOf(
            newEpisode(
                EpisodeLoadData(
                    source = source.name,
                    contentId = id,
                    episodeNumber = 1,
                    episodeName = "Episode 1",
                    sources = links,
                    raw = body
                ).toJson()
            ) {
                name = "Episode 1"
                episode = 1
            }
        )
        return newTvSeriesLoadResponse(source.name.lowercase().replaceFirstChar { it.uppercase() }, buildUrl(source, id), TvType.AsianDrama, episodes)
    }

    private fun parseMainPageData(data: String): Pair<SourceKey, String> {
        val sourceName = data.substringBefore("|")
        val source = SourceKey.valueOf(sourceName)
        return source to data.substringAfter("|")
    }

    private fun parseContentUrl(url: String): Pair<SourceKey, String> {
        val path = url.substringAfter("$mainUrl/watch/")
        val source = SourceKey.valueOf(path.substringBefore("/"))
        return source to path.substringAfter("/")
    }

    private fun buildUrl(source: SourceKey, id: String): String = "$mainUrl/watch/${source.name}/$id"

    private fun toSearchResponse(source: SourceKey, id: String?, title: String?, poster: String?): SearchResponse? {
        val itemId = id?.trim().orEmpty()
        val itemTitle = title?.trim().orEmpty()
        if (itemId.isBlank() || itemTitle.isBlank()) return null
        return newTvSeriesSearchResponse("[${source.name}] $itemTitle", buildUrl(source, itemId), TvType.AsianDrama) {
            posterUrl = poster
        }
    }

    private fun buildPageUrl(base: String, page: Int): String {
        val p = if (page <= 0) 1 else page
        val joiner = if (base.contains("?")) "&" else "?"
        return "$base${joiner}page=$p"
    }

    private fun extractMediaLinks(text: String): List<String> {
        return Regex("https?://[^\"'\\s]+\\.(?:m3u8|mp4)[^\"'\\s]*", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value }
            .distinct()
            .toList()
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    private fun parseQuality(label: String?): Int {
        val value = label?.filter { it.isDigit() }?.toIntOrNull() ?: return Qualities.Unknown.value
        return value
    }

    data class EpisodeLoadData(
        val source: String? = null,
        val contentId: String? = null,
        val episodeNumber: Int? = null,
        val episodeName: String? = null,
        val sources: List<VideoSource>? = null,
        val subtitles: List<EpisodeSubtitle>? = null,
        val raw: String? = null,
    )

    data class EpisodeSubtitle(val lang: String? = null, val url: String? = null)
    data class VideoSource(val label: String? = null, val url: String? = null, val quality: Int? = null)

    data class DramaBoxItem(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("coverWap") val coverWap: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
    )
    data class DramaBoxEpisode(@JsonProperty("chapterIndex") val chapterIndex: Int? = null, @JsonProperty("chapterName") val chapterName: String? = null, @JsonProperty("chapterImg") val chapterImg: String? = null, @JsonProperty("cdnList") val cdnList: List<DramaBoxCdn>? = null)
    data class DramaBoxCdn(@JsonProperty("videoPathList") val videoPathList: List<DramaBoxVideoPath>? = null)
    data class DramaBoxVideoPath(@JsonProperty("quality") val quality: Int? = null, @JsonProperty("videoPath") val videoPath: String? = null)

    data class ReelShortListResponse(@JsonProperty("data") val data: ReelShortListData? = null)
    data class ReelShortListData(@JsonProperty("lists") val lists: List<ReelShortItem>? = null)
    data class ReelShortItem(@JsonProperty("book_id") val bookId: String? = null, @JsonProperty("book_title") val bookTitle: String? = null, @JsonProperty("book_pic") val bookPic: String? = null)
    data class ReelShortDetailResponse(@JsonProperty("title") val title: String? = null, @JsonProperty("cover") val cover: String? = null, @JsonProperty("description") val description: String? = null, @JsonProperty("chapters") val chapters: List<ReelShortChapter>? = null)
    data class ReelShortChapter(@JsonProperty("index") val index: Int? = null, @JsonProperty("serialNumber") val serialNumber: Int? = null, @JsonProperty("title") val title: String? = null)
    data class ReelShortEpisodeResponse(@JsonProperty("videoList") val videoList: List<ReelShortVideo>? = null)
    data class ReelShortVideo(@JsonProperty("url") val url: String? = null, @JsonProperty("encode") val encode: String? = null, @JsonProperty("quality") val quality: Int? = null)

    data class ShortMaxListResponse(@JsonProperty("results") val results: List<ShortMaxItem>? = null)
    data class ShortMaxItem(@JsonProperty("shortPlayId") val shortPlayId: Long? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("cover") val cover: String? = null)
    data class ShortMaxDetailResponse(@JsonProperty("data") val data: ShortMaxDetailData? = null)
    data class ShortMaxDetailData(@JsonProperty("shortPlayName") val shortPlayName: String? = null, @JsonProperty("picUrl") val picUrl: String? = null, @JsonProperty("summary") val summary: String? = null, @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null, @JsonProperty("labelResponseList") val labelResponseList: List<ShortMaxLabel>? = null)
    data class ShortMaxLabel(@JsonProperty("labelName") val labelName: String? = null)
    data class ShortMaxEpisodeResponse(@JsonProperty("episode") val episode: ShortMaxEpisode? = null)
    data class ShortMaxEpisode(@JsonProperty("videoUrl") val videoUrl: ShortMaxVideoUrl? = null)
    data class ShortMaxVideoUrl(@JsonProperty("video_1080") val video1080: String? = null, @JsonProperty("video_720") val video720: String? = null, @JsonProperty("video_480") val video480: String? = null)

    data class NetShortListResponse(@JsonProperty("contentInfos") val contentInfos: List<NetShortItem>? = null)
    data class NetShortItem(@JsonProperty("shortPlayId") val shortPlayId: String? = null, @JsonProperty("shortPlayName") val shortPlayName: String? = null, @JsonProperty("shortPlayCover") val shortPlayCover: String? = null)

    data class GoodShortListResponse(@JsonProperty("data") val data: GoodShortData? = null)
    data class GoodShortData(@JsonProperty("records") val records: List<GoodShortRecord>? = null)
    data class GoodShortRecord(@JsonProperty("items") val items: List<GoodShortItem>? = null)
    data class GoodShortItem(@JsonProperty("bookId") val bookId: String? = null, @JsonProperty("bookName") val bookName: String? = null, @JsonProperty("cover") val cover: String? = null)
    data class GoodShortSearchResponse(@JsonProperty("data") val data: GoodShortSearchData? = null)
    data class GoodShortSearchData(@JsonProperty("records") val records: List<GoodShortItem>? = null)
    data class GoodShortAllEpisodeResponse(@JsonProperty("data") val data: GoodShortAllEpisodeData? = null)
    data class GoodShortAllEpisodeData(
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("bookCover") val bookCover: String? = null,
        @JsonProperty("downloadList") val downloadList: List<GoodShortEpisode>? = null
    )
    data class GoodShortEpisode(
        @JsonProperty("index") val index: Int? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("multiVideos") val multiVideos: List<GoodShortVideo>? = null
    )
    data class GoodShortVideo(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("filePath") val filePath: String? = null
    )

    data class FreeReelsListResponse(@JsonProperty("data") val data: FreeReelsData? = null)
    data class FreeReelsData(@JsonProperty("items") val items: List<FreeReelsItem>? = null)
    data class FreeReelsItem(@JsonProperty("key") val key: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("cover") val cover: String? = null)
    data class FreeReelsDetailResponse(@JsonProperty("data") val data: FreeReelsDetailData? = null)
    data class FreeReelsDetailData(@JsonProperty("info") val info: FreeReelsInfo? = null)
    data class FreeReelsInfo(@JsonProperty("name") val name: String? = null, @JsonProperty("cover") val cover: String? = null)

    data class NetShortAllEpisodeResponse(
        @JsonProperty("shortPlayName") val shortPlayName: String? = null,
        @JsonProperty("shortPlayCover") val shortPlayCover: String? = null,
        @JsonProperty("shotIntroduce") val shotIntroduce: String? = null,
        @JsonProperty("shortPlayLabels") val shortPlayLabels: List<String>? = null,
        @JsonProperty("shortPlayEpisodeInfos") val shortPlayEpisodeInfos: List<NetShortEpisode>? = null
    )
    data class NetShortEpisode(
        @JsonProperty("episodeNo") val episodeNo: Int? = null,
        @JsonProperty("episodeCover") val episodeCover: String? = null,
        @JsonProperty("playVoucher") val playVoucher: String? = null,
        @JsonProperty("playClarity") val playClarity: String? = null,
        @JsonProperty("subtitleList") val subtitleList: List<NetShortSubtitle>? = null
    )
    data class NetShortSubtitle(@JsonProperty("url") val url: String? = null)
}



