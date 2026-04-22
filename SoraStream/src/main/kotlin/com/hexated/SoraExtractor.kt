package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONObject
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Interceptor
import org.jsoup.Jsoup

object SoraExtractor : SoraStream() {

    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.createSlug() ?: return
        val url = if (season == null) {
            "$idlixAPI/movie/$fixTitle-$year"
        } else {
            "$idlixAPI/episode/$fixTitle-season-$season-episode-$episode"
        }
        invokeWpmovies(
            "Idlix",
            url,
            subtitleCallback,
            callback,
            encrypt = true,
            hasCloudflare = true,
            interceptor = wpRedisInterceptor
        )
    }

    private suspend fun invokeWpmovies(
        name: String? = null,
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fixIframe: Boolean = false,
        encrypt: Boolean = false,
        hasCloudflare: Boolean = false,
        interceptor: Interceptor? = null,
    ) {

        val res = app.get(url ?: return, interceptor = if (hasCloudflare) interceptor else null)
        val referer = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$referer/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ),
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
                referer = url,
                interceptor = if (hasCloudflare) interceptor else null
            ).text
            val source = tryParseJson<ResponseHash>(json)?.let {
                when {
                    encrypt -> {
                        val meta = tryParseJson<Map<String, String>>(it.embed_url)?.get("m")
                            ?: return@amap
                        val key = generateWpKey(it.key ?: return@amap, meta)
                        AesHelper.cryptoAESHandler(
                            it.embed_url,
                            key.toByteArray(),
                            false
                        )?.fixUrlBloat()
                    }

                    fixIframe -> Jsoup.parse(it.embed_url).select("IFRAME").attr("SRC")
                    else -> it.embed_url
                }
            } ?: return@amap
            when {
                source.startsWith("https://jeniusplay.com") -> {
                    Jeniusplay2().getUrl(source, "$referer/", subtitleCallback, callback)
                }

                !source.contains("youtube") -> {
                    loadExtractor(source, "$referer/", subtitleCallback, callback)
                }

                else -> {
                    return@amap
                }
            }

        }
    }

    suspend fun invokeVidsrccc(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {

        val url = if (season == null) {
            "$vidsrcccAPI/v2/embed/movie/$tmdbId"
        } else {
            "$vidsrcccAPI/v2/embed/tv/$tmdbId/$season/$episode"
        }

        val script =
            app.get(url).document.selectFirst("script:containsData(userId)")?.data() ?: return

        val userId = script.substringAfter("userId = \"").substringBefore("\";")
        val v = script.substringAfter("v = \"").substringBefore("\";")

        val vrf = VidsrcHelper.encryptAesCbc("$tmdbId", "secret_$userId")

        val serverUrl = if (season == null) {
            "$vidsrcccAPI/api/$tmdbId/servers?id=$tmdbId&type=movie&v=$v&vrf=$vrf&imdbId=$imdbId"
        } else {
            "$vidsrcccAPI/api/$tmdbId/servers?id=$tmdbId&type=tv&v=$v&vrf=$vrf&imdbId=$imdbId&season=$season&episode=$episode"
        }

        app.get(serverUrl).parsedSafe<VidsrcccResponse>()?.data?.amap {
            val sources =
                app.get("$vidsrcccAPI/api/source/${it.hash}").parsedSafe<VidsrcccResult>()?.data
                    ?: return@amap

            when {
                it.name.equals("VidPlay") -> {

                    callback.invoke(
                        newExtractorLink(
                            "VidPlay",
                            "VidPlay",
                            sources.source ?: return@amap,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$vidsrcccAPI/"
                        }
                    )

                    sources.subtitles?.map {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                it.label ?: return@map,
                                it.file ?: return@map
                            )
                        )
                    }
                }

                it.name.equals("UpCloud") -> {
                    val scriptData = app.get(
                        sources.source ?: return@amap,
                        referer = "$vidsrcccAPI/"
                    ).document.selectFirst("script:containsData(source =)")?.data()
                    val iframe = Regex("source\\s*=\\s*\"([^\"]+)").find(
                        scriptData ?: return@amap
                    )?.groupValues?.get(1)?.fixUrlBloat()

                    val iframeRes =
                        app.get(iframe ?: return@amap, referer = "https://lucky.vidbox.site/").text

                    val id = iframe.substringAfterLast("/").substringBefore("?")
                    val key = Regex("\\w{48}").find(iframeRes)?.groupValues?.get(0) ?: return@amap

                    app.get(
                        "${iframe.substringBeforeLast("/")}/getSources?id=$id&_k=$key",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                        referer = iframe
                    ).parsedSafe<UpcloudResult>()?.sources?.amap file@{ source ->
                        callback.invoke(
                            newExtractorLink(
                                "UpCloud",
                                "UpCloud",
                                source.file ?: return@file,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$vidsrcccAPI/"
                            }
                        )
                    }

                }

                else -> {
                    return@amap
                }
            }
        }


    }

    suspend fun invokeVidsrc(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val api = "https://cloudnestra.com"
        val url = if (season == null) {
            "$vidSrcAPI/embed/movie?imdb=$imdbId"
        } else {
            "$vidSrcAPI/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
        }

        val document = app.get(url).document
        val playerIframe = document.selectFirst("iframe#player_iframe")?.attr("src")
            ?.let { iframe ->
                if (iframe.startsWith("//")) "https:$iframe" else iframe
            }

        val rcpPath = when {
            !playerIframe.isNullOrBlank() && playerIframe.contains("/rcp/") -> playerIframe
            else -> document.select(".serversList .server")
                .firstOrNull { it.text().equals("CloudStream Pro", ignoreCase = true) }
                ?.attr("data-hash")
                ?.takeIf { it.isNotBlank() }
                ?.let { "$api/rcp/$it" }
        } ?: return

        val hash = app.get(rcpPath, referer = url).text
            .substringAfter("/prorcp/")
            .substringBefore("'")
            .ifBlank { return }

        val res = app.get("$api/prorcp/$hash", referer = "$api/").text
        val m3u8Link = Regex("""https:.*?\.m3u8[^"'\\\s]*""").find(res)?.value ?: return

        callback.invoke(
            newExtractorLink(
                "Vidsrc",
                "Vidsrc",
                m3u8Link,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$api/"
            }
        )

    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "${watchSomuchAPI}/Watch/ajMovieTorrents.aspx", data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val subUrl = if (season == null) {
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl).parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.label?.substringBefore("&nbsp")?.trim() ?: "",
                    fixUrl(sub.url ?: return@map null, watchSomuchAPI)
                )
            )
        }


    }

    suspend fun invokeVidlink(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vidlinkAPI/$type/$tmdbId"
        } else {
            "$vidlinkAPI/$type/$tmdbId/$season/$episode"
        }

        val videoLink = app.get(
            url, interceptor = WebViewResolver(
                Regex("""$vidlinkAPI/api/b/$type/A{32}"""), timeout = 15_000L
            )
        ).parsedSafe<VidlinkSources>()?.stream?.playlist

        callback.invoke(
            newExtractorLink(
                "Vidlink",
                "Vidlink",
                videoLink ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$vidlinkAPI/"
            }
        )

    }

    suspend fun invokeVidfast(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val module = "hezushon/bunafmin/1000098709565419/lu/40468dfa/de97f995ef83714e8ce88dc789c1c1acc4760231/y"
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vidfastAPI/$type/$tmdbId"
        } else {
            "$vidfastAPI/$type/$tmdbId/$season/$episode"
        }

        val res = app.get(
            url, interceptor = WebViewResolver(
                Regex("""$vidfastAPI/$module/LAk"""),
                timeout = 15_000L
            )
        ).text

        tryParseJson<ArrayList<VidFastServers>>(res)?.filter { it.description?.contains("Original audio") == true }
            ?.amapIndexed { index, server ->
                val source =
                    app.get("$vidfastAPI/$module/N8b-ENGCMKNz/${server.data}", referer = "$vidfastAPI/")
                        .parsedSafe<VidFastSources>()

                callback.invoke(
                    newExtractorLink(
                        "Vidfast",
                        "Vidfast [${server.name}]",
                        source?.url ?: return@amapIndexed,
                        INFER_TYPE
                    )
                )

                if (index == 1) {
                    source.tracks?.map { subtitle ->
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                subtitle.label ?: return@map,
                                subtitle.file ?: return@map
                            )
                        )
                    }
                }

            }


    }

    suspend fun invokeWyzie(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "$wyzieAPI/search?id=$tmdbId"
        } else {
            "$wyzieAPI/search?id=$tmdbId&season=$season&episode=$episode"
        }

        val res = app.get(url).text

        tryParseJson<ArrayList<WyzieSubtitle>>(res)?.map { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.display ?: return@map,
                    subtitle.url ?: return@map,
                )
            )
        }

    }

    suspend fun invokeCineSrc(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$cinesrcAPI/embed/movie/$tmdbId"
        } else {
            "$cinesrcAPI/embed/tv/$tmdbId?s=$season&e=$episode"
        }

        val mediaRes = app.get(
            url,
            interceptor = WebViewResolver(
                Regex("""https?://[^"'\\s]+?\.(?:m3u8|mp4)(?:\?[^"'\\s]*)?"""),
                timeout = 20_000L
            )
        )

        val mediaUrl = mediaRes.url
        val mediaType = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mp4", ignoreCase = true) -> INFER_TYPE
            else -> return
        }

        callback.invoke(
            newExtractorLink(
                "CineSrc",
                "CineSrc",
                mediaUrl,
                mediaType
            ) {
                this.referer = "$cinesrcAPI/"
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "Referer" to "$cinesrcAPI/",
                    "Origin" to cinesrcAPI,
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }

    private suspend fun invokeWebviewEmbedSource(
        sourceName: String,
        pageUrl: String,
        referer: String,
        origin: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val mediaRes = app.get(
            pageUrl,
            interceptor = WebViewResolver(
                Regex("""https?://[^"'\\s]+?\.(?:m3u8|mp4)(?:\?[^"'\\s]*)?"""),
                timeout = 20_000L
            )
        )

        val mediaUrl = mediaRes.url
        val mediaType = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mp4", ignoreCase = true) -> INFER_TYPE
            else -> return
        }

        callback.invoke(
            newExtractorLink(
                sourceName,
                sourceName,
                mediaUrl,
                mediaType
            ) {
                this.referer = referer
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "Referer" to referer,
                    "Origin" to origin,
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }

    suspend fun invokeMafiaEmbed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$mafiaEmbedAPI/embed/movie/$tmdbId"
        } else {
            "$mafiaEmbedAPI/embed/tv/$tmdbId/$season/$episode"
        }

        invokeWebviewEmbedSource(
            "MafiaEmbed",
            url,
            "$mafiaEmbedAPI/",
            mafiaEmbedAPI,
            callback
        )
    }

    suspend fun invokeAutoEmbed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$autoEmbedAPI/movie/tmdb/$tmdbId"
        } else {
            "$autoEmbedAPI/tv/tmdb/$tmdbId-$season-$episode"
        }

        invokeWebviewEmbedSource(
            "AutoEmbed",
            url,
            "$autoEmbedAPI/",
            autoEmbedAPI,
            callback
        )
    }

    suspend fun invoke2Embed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$twoEmbedAPI/embed/movie/$tmdbId"
        } else {
            "$twoEmbedAPI/embed/tv/$tmdbId/$season/$episode"
        }

        invokeWebviewEmbedSource(
            "2Embed",
            url,
            "$twoEmbedAPI/",
            twoEmbedAPI,
            callback
        )
    }

    suspend fun invokeVidsrcMov(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = imdbId ?: tmdbId?.toString() ?: return
        val url = if (season == null) {
            "$vidsrcMovAPI/embed/movie/$id"
        } else {
            "$vidsrcMovAPI/embed/tv/$id/$season/$episode"
        }

        invokeWebviewEmbedSource(
            "VidSrcMov",
            url,
            "$vidsrcMovAPI/",
            vidsrcMovAPI,
            callback
        )
    }

    suspend fun invokeVembed(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = imdbId ?: tmdbId?.toString() ?: return
        val vembedId = if (season == null) id else "${id}_s$season"

        invokeWebviewEmbedSource(
            "Vembed",
            "$vembedAPI/play/$vembedId",
            "$vembedAPI/",
            vembedAPI,
            callback
        )
    }

    suspend fun invokeVixsrc(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$vixsrcAPI/movie/$tmdbId"
        } else {
            "$vixsrcAPI/tv/$tmdbId/$season/$episode"
        }
        invokeWebviewEmbedSource(
            "Vixsrc",
            url,
            "$vixsrcAPI/",
            vixsrcAPI,
            callback
        )

    }

    suspend fun invokeKisskh(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        try {
            val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$title&type=0").text
            val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return
            val matched = searchList.find { 
                it.title.equals(title, true) 
            } ?: searchList.firstOrNull { it.title?.contains(title, true) == true } ?: return
            val dramaId = matched.id ?: return
            val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false").parsedSafe<KisskhDetail>() ?: return
            val episodes = detailRes.episodes ?: return
            val targetEp = if (season == null) {
                episodes.lastOrNull()
            } else {
                episodes.find { it.number?.toInt() == episode }
            } ?: return
            val epsId = targetEp.id ?: return
            val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val videoUrl = "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkeyVideo"
            val sources = app.get(videoUrl).parsedSafe<KisskhSources>()

            val videoLink = sources?.video
            val thirdParty = sources?.thirdParty

            listOfNotNull(videoLink, thirdParty).forEach { link ->
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        "Kisskh",
                        link,
                        referer = "$mainUrl/",
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach(callback)
                } else if (link.contains(".mp4")) {
                    callback.invoke(
                        newExtractorLink(
                            "Kisskh",
                            "Kisskh",
                            link,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }

            val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val subJson = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub").text
            tryParseJson<List<KisskhSubtitle>>(subJson)?.forEach { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        sub.label ?: "Unknown",
                        sub.src ?: return@forEach
                    )
                )
            }

        } catch (e: Exception) {
            logError(e)
        }
    }

    private data class KisskhMedia(
        @param:JsonProperty("id") val id: Int?,
        @param:JsonProperty("title") val title: String?
    )
    private data class KisskhDetail(@param:JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(
        @param:JsonProperty("id") val id: Int?,
        @param:JsonProperty("number") val number: Double?
    )
    private data class KisskhKey(@param:JsonProperty("key") val key: String?)
    private data class KisskhSources(
        @param:JsonProperty("Video") val video: String?,
        @param:JsonProperty("ThirdParty") val thirdParty: String?
    )
    private data class KisskhSubtitle(
        @param:JsonProperty("src") val src: String?,
        @param:JsonProperty("label") val label: String?
    )


}
