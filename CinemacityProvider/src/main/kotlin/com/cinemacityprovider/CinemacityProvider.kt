package com.cinemacityprovider

import android.util.Log
import com.google.gson.Gson
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class CinemacityProvider : MainAPI() {
    override var mainUrl = "https://cinemacity.cc"
    override var name = "CinemaCity"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        val headers = mapOf(
            "Cookie" to base64Decode("ZGxlX3VzZXJfaWQ9MzI3Mjk7IGRsZV9wYXNzd29yZD04OTQxNzFjNmE4ZGFiMThlZTU5NGQ1YzY1MjAwOWEzNTs=")
        )
        val requestHeaders = headers + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        private const val TMDBIMAGEBASEURL = "https://image.tmdb.org/t/p/original"
        private const val cinemetaUrl = "https://v3-cinemeta.strem.io/meta"
    }

    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "movies" to "Movies",
        "tv-series" to "TV Series",
        "xfsearch/genre/anime" to "Anime",
        "xfsearch/genre/asian" to "Asian",
        "xfsearch/genre/animation" to "Animation",
        "xfsearch/genre/documentary" to "Documentary",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = if (page == 1) requestDocument("$mainUrl/${request.data}")
        else requestDocument("$mainUrl/${request.data}/page/$page")

        val home = doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, true)
    }

    private suspend fun requestDocument(url: String) = requestPage(url).document

    private suspend fun requestPage(url: String) =
        app.get(
            url,
            headers = requestHeaders,
            referer = mainUrl
        ).takeUnless { isCloudflareChallenge(it.text) }
            ?: app.get(
                url,
                interceptor = cloudflareInterceptor,
                headers = requestHeaders,
                referer = mainUrl
            )

    private fun isCloudflareChallenge(html: String): Boolean {
        val body = html.lowercase()
        val hasBlockingCopy = body.contains("just a moment") ||
            body.contains("enable javascript and cookies to continue") ||
            body.contains("cf-browser-verification") ||
            body.contains("__cf_chl_tk")
        val hasExpectedContent = body.contains("dar-short_item") ||
            body.contains("dar-full") ||
            body.contains("playerjs-") ||
            body.contains("cinemacity")
        return hasBlockingCopy && !hasExpectedContent
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = children().firstOrNull { it.tagName() == "a" && it.hasAttr("href") }
            ?: selectFirst("a.e-nowrap[href]")
            ?: return null
        val title = anchor.text().substringBeforeLast("(").trim().ifBlank { anchor.text().trim() }
        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(selectFirst("div.dar-short_bg a[href]")?.attr("href"))
        val score = selectFirst("span.rating-color1, span.rating-color")?.ownText()?.trim()
        val quality = this
            .selectFirst("div.dar-short_bg > div span a, div.dar-short_bg > div span")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                when {
                    it.contains("CAM", true) -> "CAM"
                    it.contains("TS", true) -> "TS"
                    else -> "HD"
                }
            }
            ?: run {
                if (this.selectFirst("div.dar-short_bg > div > span")?.text()?.contains("TS", true) == true) "TS" else "HD"
            }

        val type = if (href.contains("/tv-series/", true)) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val doc = requestPage(
            "$mainUrl/?do=search&subaction=search&search_start=0&full_search=0&story=$encodedQuery"
        ).document
        val res = doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return res.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val page = requestPage(url)
        val doc = page.document

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val title = ogTitle.substringBefore("(").trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val bgposter = doc.selectFirst("div.dar-full_bg a")?.attr("href")
        val trailer = doc.select("div.dar-full_bg.e-cover > div").attr("data-vbg")

        val audioLanguages = doc
            .select("li")
            .firstOrNull { it.selectFirst("span")?.text()?.equals("Audio language", ignoreCase = true) == true }
            ?.select("span:eq(1) a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(", ")

        val descriptions = doc.selectFirst("#about div.ta-full_text1")?.text()

        val recommendation = doc.select("div.ta-rel > div.ta-rel_item").map {
            val recTitle = it.select("a").text().substringBefore("(").trim()
            val href = fixUrl(it.selectFirst("> div > a")?.attr("href") ?: "")
            val score = it.select("span.rating-color1").text()
            val posterUrl = it.selectFirst("div > a")?.attr("href")

            newMovieSearchResponse(recTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }

        val year = ogTitle.substringAfter("(", "").substringBefore(")").toIntOrNull()
        val tvtype = if (url.contains("/movies/", true)) TvType.Movie else TvType.TvSeries
        val tmdbMetaType = if (tvtype == TvType.TvSeries) "tv" else "movie"

        var genre: List<String>? = null
        var background: String? = null
        var description: String? = null

        val imdbId = doc
            .select("div.ta-full_rating1 > div")
            .mapNotNull { it.attr("onclick") }
            .firstNotNullOfOrNull { Regex("tt\\d+").find(it)?.value }

        val tmdbId = imdbId?.let { id ->
            runCatching {
                val obj = JSONObject(
                    app.get(
                        "https://api.themoviedb.org/3/find/$id" +
                            "?api_key=1865f43a0549ca50d341dd9ab8b29f49" +
                            "&external_source=imdb_id"
                    ).text
                )
                obj.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it != 0 }
                    ?: obj.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id")?.takeIf { it != 0 }
            }.getOrNull()?.toString()
        }

        val logoPath = imdbId?.let { "https://live.metahub.space/logo/medium/$it/img" }

        val creditsJson = tmdbId?.let {
            runCatching {
                app.get(
                    "https://api.themoviedb.org/3/$tmdbMetaType/$it/credits" +
                        "?api_key=1865f43a0549ca50d341dd9ab8b29f49&language=en-US"
                ).text
            }.getOrNull()
        }
        val castList = parseCredits(creditsJson)

        val typeSet = if (tvtype == TvType.TvSeries) "series" else "movie"
        val responseData = imdbId?.takeIf { it.isNotBlank() }?.let {
            val text = app.get("$cinemetaUrl/$typeSet/$it.json").text
            if (text.startsWith("{")) Gson().fromJson(text, ResponseData::class.java) else null
        }

        responseData?.meta?.let {
            description = it.description ?: descriptions
            background = it.background ?: poster
            genre = it.genres
        }

        val epMetaMap: Map<String, ResponseData.Meta.EpisodeDetails> =
            responseData?.meta?.videos
                ?.filter { it.season != null && it.episode != null }
                ?.associateBy { "${it.season}:${it.episode}" }
                ?: emptyMap()

        val fileArray = extractPlaylist(doc)

        val seasonRegex = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
        val episodeRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)

        val episodeList = mutableListOf<Episode>()

        val movieJson = fileArray.takeIf { it.length() > 0 }?.let { playlist ->
            JSONObject().apply {
                put("pageUrl", url)
                put("playlist", playlist)
            }.toString()
        }

        if (tvtype == TvType.TvSeries) {
            for (i in 0 until fileArray.length()) {
                val seasonJson = fileArray.getJSONObject(i)
                val seasonNumber = seasonRegex.find(seasonJson.optString("title"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val episodes = seasonJson.optJSONArray("folder") ?: continue
                for (j in 0 until episodes.length()) {
                    val epJson = episodes.getJSONObject(j)
                    val episodeNumber = episodeRegex.find(epJson.optString("title"))?.groupValues?.get(1)?.toIntOrNull() ?: continue

                    val epMeta = epMetaMap["$seasonNumber:$episodeNumber"]
                    val epData = JSONObject().apply {
                        put("title", epJson.optString("title"))
                        put("file", epJson.optString("file"))
                        put("subtitle", epJson.optString("subtitle"))
                        put("pageUrl", url)
                    }.toString()

                    episodeList += newEpisode(epData) {
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.name = epMeta?.name ?: "S${seasonNumber}E${episodeNumber}"
                        this.description = epMeta?.overview
                        this.posterUrl = epMeta?.thumbnail
                        addDate(epMeta?.released)
                    }
                }
            }

            return newTvSeriesLoadResponse(responseData?.meta?.name ?: title, url, TvType.TvSeries, episodeList) {
                this.backgroundPosterUrl = background ?: bgposter
                this.posterUrl = poster
                try { this.logoUrl = logoPath } catch (_: Throwable) {}
                this.year = year ?: responseData?.meta?.year?.toIntOrNull()
                this.plot = buildString {
                    append(description ?: descriptions)
                    if (!audioLanguages.isNullOrBlank()) {
                        append(" - Audio: ")
                        append(audioLanguages)
                    }
                }
                this.recommendations = recommendation
                this.tags = genre
                this.actors = castList
                this.score = Score.from10(responseData?.meta?.imdbRating)
                this.contentRating = responseData?.meta?.appExtras?.certification
                addImdbId(imdbId)
                addTMDbId(tmdbId)
                addTrailer(trailer)
            }
        }

        responseData?.meta?.appExtras?.certification?.let { Log.d("Phisher", it) }

        return newMovieLoadResponse(responseData?.meta?.name ?: title, url, TvType.Movie, movieJson) {
            this.backgroundPosterUrl = background ?: bgposter
            this.posterUrl = poster
            try { this.logoUrl = logoPath } catch (_: Throwable) {}
            this.year = year ?: responseData?.meta?.year?.toIntOrNull()
            this.plot = buildString {
                append(description ?: descriptions)
                if (!audioLanguages.isNullOrBlank()) {
                    append(" - Audio: ")
                    append(audioLanguages)
                }
            }
            this.recommendations = recommendation
            this.tags = genre
            this.actors = castList
            this.contentRating = responseData?.meta?.appExtras?.certification
            this.score = Score.from10(responseData?.meta?.imdbRating)
            addImdbId(imdbId)
            addTMDbId(tmdbId)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trimmed = data.trim()
        if (trimmed.isBlank()) return false

        val playlist = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> runCatching { JSONObject(trimmed).optJSONArray("playlist") }.getOrNull()
        }
        val obj = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
        val pageUrl = obj?.optString("pageUrl")?.takeIf { it.isNotBlank() }

        val rawFile = obj?.optString("file").orEmpty()
        if (rawFile.isNotBlank()) {
            val baseUrl = pageUrl ?: mainUrl
            val subtitleRaw = obj?.optString("subtitle")
            return emitFileSetLinks(baseUrl, rawFile, subtitleRaw, subtitleCallback, callback)
        }

        if (playlist != null) {
            val emitted = mutableSetOf<String>()
            for (i in 0 until playlist.length()) {
                val item = playlist.optJSONObject(i) ?: continue
                val itemBaseUrl = item.optString("pageUrl").takeIf { it.isNotBlank() } ?: pageUrl ?: mainUrl
                val ok = emitFileSetLinks(itemBaseUrl, item.optString("file"), item.optString("subtitle"), subtitleCallback) { link ->
                    if (emitted.add(link.url)) callback(link)
                }
                if (ok) return true
            }
        }

        obj?.optJSONArray("subtitleTracks")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                subtitleCallback(newSubtitleFile(s.getString("language"), s.getString("subtitleUrl")))
            }
        }

        val streamUrls = mutableListOf<String>()
        obj?.optJSONArray("streams")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { streamUrls += it }
            }
        }
        if (streamUrls.isEmpty()) {
            obj?.optString("streamUrl").takeIf { !it.isNullOrBlank() }?.let { streamUrls += it }
        }
        if (streamUrls.isEmpty()) return false

        streamUrls.forEach { url ->
            callback(
                newExtractorLink(name, name, url, INFER_TYPE) {
                    referer = mainUrl
                    quality = extractQuality(url)
                }
            )
        }
        return true
    }

    private fun extractPlaylist(doc: org.jsoup.nodes.Document): JSONArray {
        val playerScript = doc.selectFirst("div[id^=player] + script")
            ?: doc.select("script:containsData(atob)").getOrNull(1)
            ?: error("Player playlist script not found")

        val encoded = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
            .find(playerScript.data())
            ?.groupValues
            ?.getOrNull(1)
            ?: error("Player playlist payload not found")

        val decoded = base64Decode(encoded)
        val rawFile = listOf(
            Regex("""file:'(.*)',\s*poster"""),
            Regex("""file:"(.*)",\s*poster""")
        ).firstNotNullOfOrNull { it.find(decoded)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: error("Player playlist is empty")

        return when {
            rawFile.startsWith("[") && rawFile.endsWith("]") -> JSONArray(rawFile)
            rawFile.startsWith("{") && rawFile.endsWith("}") -> JSONArray().apply { put(JSONObject(rawFile)) }
            else -> JSONArray().apply { put(JSONObject().apply { put("file", rawFile) }) }
        }
    }

    private suspend fun emitFileSetLinks(
        fallbackBaseUrl: String,
        rawFile: String,
        rawSubtitle: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = rawFile.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return false

        val mediaPattern = Regex("""\.(mp4|m4a|m3u8)(\?|$)""", RegexOption.IGNORE_CASE)
        val first = parts.first()
        val base = if (mediaPattern.containsMatchIn(first)) fallbackBaseUrl else first
        val rels = if (base == first) parts.drop(1) else parts
        if (rels.isEmpty()) return false

        val subtitles = parseSubtitlePaths(rawSubtitle)
        subtitles.forEach { (_, url) -> subtitleCallback(newSubtitleFile(url.second, url.first)) }

        val hlsPaths = rels.filter {
            it.endsWith(".m3u8", true) || it.contains(".urlset/master.m3u8", true)
        }.distinct()

        var emitted = false
        for (hlsPath in hlsPaths) {
            val streamUrl = resolveMediaUrl(base, hlsPath)
            callback(
                newExtractorLink(name, "$name HLS", streamUrl, ExtractorLinkType.M3U8) {
                    referer = fallbackBaseUrl
                    headers = requestHeaders + mapOf("Referer" to fallbackBaseUrl)
                    quality = extractQuality(hlsPath)
                }
            )
            emitted = true
        }

        val audios = rels.filter {
            it.endsWith(".m4a", true) && !it.endsWith(".urlset/master.m3u8", true)
        }.mapNotNull { path ->
            val key = Regex("""_([^_/]+)\.m4a$""", RegexOption.IGNORE_CASE)
                .find(path)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            Triple(key, titleCase(key), path)
        }.distinctBy { it.third }

        val videos = rels.filter {
            it.endsWith(".mp4", true) && !it.endsWith(".urlset/master.m3u8", true)
        }.map { path ->
            val res = Regex("""_(\d{3,4}p)\.mp4$""", RegexOption.IGNORE_CASE)
                .find(path)?.groupValues?.getOrNull(1)?.lowercase() ?: "mp4"
            res to path
        }.distinctBy { it.second }

        if (emitted) return true

        for ((_, langLabel, audioPath) in audios) {
            for ((res, videoPath) in videos) {
                val directVideoUrl = resolveMediaUrl(base, videoPath)
                callback(
                    newExtractorLink(name, "$name $res $langLabel", directVideoUrl, ExtractorLinkType.VIDEO) {
                        referer = fallbackBaseUrl
                        headers = requestHeaders + mapOf("Referer" to fallbackBaseUrl)
                        quality = extractQuality(videoPath)
                    }
                )
                emitted = true
            }
        }

        if (emitted) return true

        for ((_, langLabel, audioPath) in audios) {
            for ((res, videoPath) in videos) {
                val href = buildDownloadHref(base, videoPath, audioPath, subtitles.map { it.first })
                callback(
                    newExtractorLink(name, "$name Download $res $langLabel", href, INFER_TYPE) {
                        referer = fallbackBaseUrl
                        headers = requestHeaders + mapOf("Referer" to fallbackBaseUrl)
                        quality = extractQuality(videoPath)
                    }
                )
                emitted = true
            }
        }
        return emitted
    }

    private fun resolveMediaUrl(base: String, path: String): String {
        val cleanPath = path.trim()
        if (cleanPath.startsWith("http://", true) || cleanPath.startsWith("https://", true)) {
            return cleanPath
        }
        if (cleanPath.startsWith("/")) {
            return fixUrl(cleanPath)
        }

        val cleanBase = base.trim().substringBefore("?")
        if (cleanBase.startsWith("http://", true) || cleanBase.startsWith("https://", true)) {
            val normalizedBase = when {
                cleanBase.endsWith("/") -> cleanBase
                cleanBase.substringAfterLast("/").contains(".") -> cleanBase.substringBeforeLast("/") + "/"
                else -> "$cleanBase/"
            }
            return normalizedBase + cleanPath.removePrefix("/")
        }

        return fixUrl("/$cleanPath")
    }

    private fun buildDownloadHref(
        base: String,
        videoPath: String,
        audioPath: String,
        subtitlePaths: List<String>
    ): String {
        val separator = if (base.contains("?")) "&" else "?"
        val subtitleParam = subtitlePaths.joinToString(",").takeIf { it.isNotBlank() }
        return buildString {
            append(fixUrl(base))
            append(separator)
            append("action=download&video=")
            append(URLEncoder.encode(videoPath, StandardCharsets.UTF_8.toString()))
            append("&audio=")
            append(URLEncoder.encode(audioPath, StandardCharsets.UTF_8.toString()))
            if (!subtitleParam.isNullOrBlank()) {
                append("&subtitle=")
                append(URLEncoder.encode(subtitleParam, StandardCharsets.UTF_8.toString()))
            }
            append("&name=")
            append(URLEncoder.encode("cinemacity", StandardCharsets.UTF_8.toString()))
        }
    }

    private fun parseSubtitlePaths(raw: String?): List<Pair<String, Pair<String, String>>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val match = Regex("""\[(.+?)](https?://.+)""").find(entry.trim()) ?: return@mapNotNull null
            val lang = match.groupValues[1]
            val fullUrl = match.groupValues[2]
            val relative = fullUrl.substringAfter("/public_files/", "")
            if (relative.isBlank()) null else relative to (fullUrl to lang)
        }
    }

    private fun titleCase(value: String): String =
        value.split("-", "_", " ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }

    private fun parseCredits(jsonText: String?): List<ActorData> {
        if (jsonText.isNullOrBlank()) return emptyList()
        val list = ArrayList<ActorData>()
        val root = JSONObject(jsonText)
        val castArr = root.optJSONArray("cast") ?: return list
        for (i in 0 until castArr.length()) {
            val c = castArr.optJSONObject(i) ?: continue
            val name = c.optString("name").takeIf { it.isNotBlank() } ?: c.optString("original_name").orEmpty()
            val profile = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$TMDBIMAGEBASEURL$it" }
            val character = c.optString("character").takeIf { it.isNotBlank() }
            val actor = Actor(name, profile)
            list += ActorData(actor, roleString = character)
        }
        return list
    }

    private fun extractQuality(url: String): Int {
        return when {
            url.contains("2160p") -> Qualities.P2160.value
            url.contains("1440p") -> Qualities.P1440.value
            url.contains("1080p") -> Qualities.P1080.value
            url.contains("720p") -> Qualities.P720.value
            url.contains("480p") -> Qualities.P480.value
            url.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun parseSubtitles(raw: String?): JSONArray {
        val tracks = JSONArray()
        if (raw.isNullOrBlank()) return tracks
        raw.split(",").forEach { entry ->
            val match = Regex("""\[(.+?)](https?://.+)""").find(entry.trim())
            if (match != null) {
                tracks.put(
                    JSONObject().apply {
                        put("language", match.groupValues[1])
                        put("subtitleUrl", match.groupValues[2])
                    }
                )
            }
        }
        return tracks
    }
}
