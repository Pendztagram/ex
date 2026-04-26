package com.hexated

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class IdlixProvider : MainAPI() {
    override var mainUrl = base64Decode("aHR0cHM6Ly96MS5pZGxpeGt1LmNvbQ==")
    override var name = "Idlix🐍"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "TV Series Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Amazon Prime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, timeout = 10000L).parsedSafe<ApiResponse>() ?: return newHomePageResponse(request.name, emptyList())
        val home = res.data.map { item ->
            val title = item.title ?: "UnKnown"
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            if (item.contentType == "movie") {
                val movieurl = "$mainUrl/api/movies/${item.slug}"
                newMovieSearchResponse(title, movieurl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality = getSearchQuality(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                val seriesurl = "$mainUrl/api/series/${item.slug}"
                newTvSeriesSearchResponse(title, seriesurl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage)
                    this.quality = getSearchQuality(item.quality)
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query,1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/api/search?q=$query&page=$page&limit=8"
        val res = app.get(url).parsedSafe<SearchApiResponse>() ?: return null
        val items = res.results
        val results = items.mapNotNull { item ->
            val title = item.title
            val poster = item.posterPath.let { "https://image.tmdb.org/t/p/w342$it" }
            val year = (item.releaseDate ?: item.firstAirDate)?.substringBefore("-")?.toIntOrNull()

            val link = when (item.contentType) {
                "movie" -> "$mainUrl/api/movies/${item.slug}"
                "tv_series", "series" -> "$mainUrl/api/series/${item.slug}"
                else -> return@mapNotNull null
            }

            val rating = item.voteAverage

            if (item.contentType == "movie") {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = getQualityFromString(item.quality)
                    this.score = rating.let { Score.from10(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating.let { Score.from10(it) }
                }
            }
        }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, timeout = 10000L)

        val data = response.parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("Invalid JSON")

        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }

        val year = (data.releaseDate ?: data.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val logourl = "https://image.tmdb.org/t/p/w500"+data.logoPath
        val actors = data.cast?.map {
            Actor(it.name ?: "", it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" })
        } ?: emptyList()

        val trailer = data.trailerUrl
        val rating = data.voteAverage

        val relatedUrl = if (data.seasons != null) {
            "$mainUrl/api/series/${data.slug}/related"
        } else {
            "$mainUrl/api/movies/${data.slug}/related"
        }

        val recommendations = try {
            app.get(relatedUrl, referer = mainUrl)
                .parsedSafe<ApiResponse>()?.data?.mapNotNull { item ->

                    val title = item.title ?: return@mapNotNull null
                    val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

                    val link = if (item.contentType == "movie") {
                        "$mainUrl/api/movies/${item.slug}"
                    } else {
                        "$mainUrl/api/series/${item.slug}"
                    }

                    if (item.contentType == "movie") {
                        newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    } else {
                        newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    }

                } ?: emptyList()

        } catch (_: Exception) {
            emptyList()
        }

        return if (data.seasons != null) {
            val episodes = mutableListOf<Episode>()

            data.firstSeason?.episodes?.forEach { ep ->
                episodes.add(
                    newEpisode( LoadData(
                        id = ep.id ?: return@forEach,
                        type = "episode"
                    ).toJson()) {
                        this.name = ep.name
                        this.season = data.firstSeason.seasonNumber
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        addDate(ep.airDate)
                        this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                    }
                )
            }

            data.seasons.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == data.firstSeason?.seasonNumber) return@forEach
                val seasonUrl = "$mainUrl/api/series/${data.slug}/season/$seasonNum"

                val seasonData = try {
                    val res = app.get(seasonUrl, referer = mainUrl)
                    res.parsedSafe<SeasonWrapper>()?.season
                } catch (_: Exception) {
                    null
                }

                seasonData?.episodes?.forEach { ep ->
                    episodes.add(
                        newEpisode( LoadData(
                            id = ep.id ?: return@forEach,
                            type = "episode"
                        ).toJson()) {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.description = ep.overview
                            this.runTime = ep.runtime
                            this.score = Score.from10(ep.voteAverage?.toString())
                            addDate(ep.airDate)
                            this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie,  LoadData(
                id = data.id ?: "",
                type = "movie"
            ).toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parsed = try {
            AppUtils.parseJson<LoadData>(data)
        } catch (_: Exception) {
            null
        } ?: return false

        val contentId = parsed.id
        val contentType = parsed.type

        val ts = System.currentTimeMillis()
        val aclrRes = app.get(
            "$mainUrl/pagead/ad_frame.js?_=$ts",
            referer = mainUrl,
            interceptor = cloudflareInterceptor
        ).text
        val aclr = Regex("""__aclr\s*=\s*"([a-f0-9]+)"""")
            .find(aclrRes)
            ?.groupValues?.getOrNull(1)

        Log.d(name, "Idlix aclr: ${aclr ?: "null"}")

        val challengejson = """
{
    "contentType": "$contentType",
    "contentId": "$contentId"${if (aclr != null) ",\n    \"clearance\": \"$aclr\"" else ""}
}
""".trimIndent()

        val headers = mapOf(
            "accept" to "*/*",
            "content-type" to "application/json",
            "origin" to mainUrl,
            "referer" to mainUrl,
            "user-agent" to USER_AGENT,
        )

        val challengeText = app.post(
            "$mainUrl/api/watch/challenge",
            requestBody = challengejson.toRequestBody("application/json".toMediaType()),
            headers = headers,
            interceptor = cloudflareInterceptor
        ).text

        Log.d(name, "Idlix challengeText: ${challengeText.take(300)}")

        val challengeRes = AppUtils.tryParseJson<ChallengeResponse>(challengeText)
            ?: run {
                Log.d(name, "Idlix challenge parse failed: ${challengeText.take(300)}")
                return false
            }

        val nonce = solvePow(
            challengeRes.challenge,
            challengeRes.difficulty
        )

        val solvejson = """
        {
        "challenge": "${challengeRes.challenge}",
        "signature": "${challengeRes.signature}",
        "nonce": $nonce
        }
        """.trimIndent()

        val solveRes = app.post(
            "$mainUrl/api/watch/solve",
            requestBody = solvejson.toRequestBody("application/json".toMediaType()),
            headers = mapOf(
                "accept" to "*/*",
                "content-type" to "application/json",
                "origin" to mainUrl,
                "referer" to mainUrl,
                "user-agent" to USER_AGENT,
            ),
            interceptor = cloudflareInterceptor
        ).text

        Log.d(name, "Idlix solveRes: ${solveRes.take(300)}")

        val embedUrl = extractUrlFromSolveResponse(solveRes) ?: run {
            Log.d(name, "Idlix extractUrlFromSolveResponse failed")
            return false
        }

        Log.d(name, "Idlix embedUrl: $embedUrl")

        val embedPageUrl = when {
            embedUrl.startsWith("http", ignoreCase = true) -> embedUrl
            embedUrl.startsWith("/") -> "$mainUrl$embedUrl"
            else -> "$mainUrl/$embedUrl"
        }

        val resolver = WebViewResolver(
            interceptUrl = Regex(
                """https?://[^"'\s]+(?:\.m3u8|\.mp4)[^"'\s]*""",
                RegexOption.IGNORE_CASE
            ),
            additionalUrls = listOf(
                Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://(?:[a-z0-9-]+\.)*(?:majorplay\.net|jeniusplay\.com)[^"'\s]*""", RegexOption.IGNORE_CASE),
            ),
            useOkhttp = false,
            timeout = 20_000L
        )

        val resolvedUrl = app.get(embedPageUrl, referer = mainUrl, interceptor = resolver)
            .url
            .substringBefore('#')

        Log.d(name, "Idlix embedPageUrl: $embedPageUrl")
        Log.d(name, "Idlix resolvedUrl: $resolvedUrl")

        // Check if this is a Majorplay/Jeniusplay embed - extract subtitles from embed page
        val isMajorplayOrJeniusplay = resolvedUrl.contains("majorplay.net", ignoreCase = true) ||
                resolvedUrl.contains("jeniusplay.com", ignoreCase = true) ||
                embedPageUrl.contains("majorplay.net", ignoreCase = true) ||
                embedPageUrl.contains("jeniusplay.com", ignoreCase = true)

        if (isMajorplayOrJeniusplay) {
            // Fetch the embed page to extract subtitles
            val embedRes = runCatching {
                app.get(embedPageUrl, referer = mainUrl, interceptor = cloudflareInterceptor)
            }.getOrNull()

            if (embedRes != null) {
                val html = embedRes.text
                extractSubtitlesFromHtml(html, embedPageUrl, subtitleCallback)
            }
        }

        when {
            resolvedUrl.contains(".m3u8", ignoreCase = true) -> {
                generateM3u8(name, resolvedUrl, embedPageUrl).forEach(callback)
                return true
            }

            resolvedUrl.contains(".mp4", ignoreCase = true) -> {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Auto",
                        url = resolvedUrl,
                    ) {
                        referer = embedPageUrl
                    }
                )
                return true
            }
        }

        // Fallback kalau WebView tidak menemukan URL media / pattern redirect berubah.
        if (resolvedUrl == embedPageUrl || resolvedUrl.startsWith(mainUrl, ignoreCase = true)) {
            val embedRes = app.get(embedPageUrl, referer = mainUrl, interceptor = cloudflareInterceptor)
            val doc = embedRes.document
            val html = embedRes.text

            val iframeSrc = doc.selectFirst("iframe[src]")?.attr("abs:src")?.trim().orEmpty()
            val iframeDataSrc = doc.selectFirst("iframe[data-src]")?.attr("abs:data-src")?.trim().orEmpty()
            val sourceSrc = doc.selectFirst("video source[src], source[src]")?.attr("abs:src")?.trim().orEmpty()
            val videoSrc = doc.selectFirst("video[src]")?.attr("abs:src")?.trim().orEmpty()

            val scriptData = doc.select("script").joinToString("\n") { it.data() }
            val scriptUrl = Regex("""https?://(?:[a-z0-9-]+\.)*(?:majorplay\.net|jeniusplay\.com|[a-z0-9.-]+/(?:embed|player|video|play|e|v|watch))[^"'\s]*""", RegexOption.IGNORE_CASE)
                .find(scriptData)
                ?.value
                ?.trim()
                .orEmpty()

            val m3u8FromScript = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE)
                .find(scriptData)?.value.orEmpty()
            val mp4FromScript = Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE)
                .find(scriptData)?.value.orEmpty()

            val decryptedSrc = decryptEmbeddedUrl(html).orEmpty()

            Log.d(name, "Idlix fallback iframeSrc=$iframeSrc iframeDataSrc=$iframeDataSrc sourceSrc=$sourceSrc videoSrc=$videoSrc scriptUrl=$scriptUrl decrypted=${decryptedSrc.isNotBlank()} m3u8Script=${m3u8FromScript.isNotBlank()} mp4Script=${mp4FromScript.isNotBlank()}")

            val picked = listOf(decryptedSrc, iframeSrc, iframeDataSrc, sourceSrc, videoSrc, scriptUrl, m3u8FromScript, mp4FromScript)
                .firstOrNull { it.startsWith("http", ignoreCase = true) }
            if (picked != null) {
                if (emitDirectMediaLinks(picked, embedPageUrl, callback)) {
                    return true
                }
                loadExtractor(picked, embedPageUrl, subtitleCallback, callback)
                return true
            }
        }

        if (emitDirectMediaLinks(resolvedUrl, embedPageUrl, callback)) {
            return true
        }

        loadExtractor(resolvedUrl, embedPageUrl, subtitleCallback, callback)
        return true
    }

    fun solvePow(challenge: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty)

        var nonce = 0
        while (true) {
            val hash = sha256(challenge + nonce)

            if (hash.startsWith(target)) {
                return nonce
            }

            nonce++
        }
    }

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractUrlFromSolveResponse(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed

        // URL inside quotes
        Regex("""""'(https?://[^""']+)""'""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        // Relative path inside quotes
        Regex("""""'(/(?:embed|player|video|play|e|v|watch)[^""']*)""'""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

        val normalized = trimmed.replace("\\/", "/")
        val direct = Regex("""https?://[^""'\s]+""", RegexOption.IGNORE_CASE).find(normalized)?.value
        if (!direct.isNullOrBlank()) return direct

        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        // Recursive search for any URL-like string inside JSON
        fun findUrl(obj: Any?): String? {
            when (obj) {
                is String -> {
                    if (obj.startsWith("http", ignoreCase = true) || obj.startsWith("/")) return obj
                }
                is JSONObject -> {
                    val keys = obj.keys()
                    // First pass: known keys
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.equals("embedUrl", true) || key.equals("url", true) ||
                            key.equals("streamUrl", true) || key.equals("playbackUrl", true) ||
                            key.equals("src", true) || key.equals("file", true) || key.equals("link", true) ||
                            key.equals("embed", true) || key.equals("source", true) || key.equals("video", true)) {
                            findUrl(obj.get(key))?.let { return it }
                        }
                    }
                    // Second pass: any key
                    val keys2 = obj.keys()
                    while (keys2.hasNext()) {
                        findUrl(obj.get(keys2.next()))?.let { return it }
                    }
                }
            }
            return null
        }

        return findUrl(json)
    }

    private fun decryptEmbeddedUrl(html: String): String? {
        val dataA = Regex("""data-a=["']([a-fA-F0-9]{32})["']""").find(html)?.groupValues?.getOrNull(1)
        val dataP = Regex("""data-p=["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
        val dataV = Regex("""data-v=["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
        val cssSecret = Regex("""--_[a-z0-9]+:\s*["']([a-fA-F0-9]{32})["']""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)

        if (dataA.isNullOrBlank() || dataP.isNullOrBlank() || dataV.isNullOrBlank() || cssSecret.isNullOrBlank()) {
            return null
        }

        val keyHex = dataA + cssSecret
        val keyBytes = runCatching { hexToBytes(keyHex) }.getOrNull() ?: return null
        val cipherBytes = runCatching { Base64.decode(dataP, Base64.DEFAULT) }.getOrNull() ?: return null
        val ivBytes = runCatching { Base64.decode(dataV, Base64.DEFAULT) }.getOrNull() ?: return null

        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, ivBytes)
            )
            String(cipher.doFinal(cipherBytes))
        }.getOrNull() ?: return null

        return plain.trim().takeIf { it.startsWith("http", ignoreCase = true) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private suspend fun extractSubtitlesFromHtml(
        html: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        // Extract subtitles from JSON in script tags (Majorplay Next.js format)
        val subtitleRegex = Regex(""""subtitles"\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
        subtitleRegex.findAll(html).forEach { match ->
            val jsonArray = match.groupValues.getOrNull(1) ?: return@forEach
            try {
                val subtitles = AppUtils.tryParseJson<List<MajorplaySubtitle>>(jsonArray)
                subtitles?.forEach { sub ->
                    val subUrl = when {
                        sub.path.startsWith("http", ignoreCase = true) -> sub.path
                        sub.path.startsWith("/") -> baseUrl.substringBefore("/embed") + sub.path
                        else -> "$baseUrl/${sub.path}"
                    }
                    val lang = when {
                        sub.label.contains("indonesia", true) || sub.lang.contains("id", true) -> "Indonesian"
                        sub.label.contains("english", true) || sub.lang.contains("en", true) -> "English"
                        else -> sub.label.ifBlank { sub.lang.ifBlank { "Unknown" } }
                    }
                    subtitleCallback.invoke(
                        newSubtitleFile(lang, subUrl)
                    )
                }
            } catch (_: Exception) { }
        }

        // Extract from initialToken JSON format
        val initialTokenRegex = Regex(""""initialToken"\s*:\s*\{.*?"subtitles"\s*:\s*(\[.*?\]).*?\}""", RegexOption.DOT_MATCHES_ALL)
        initialTokenRegex.findAll(html).forEach { match ->
            val jsonArray = match.groupValues.getOrNull(1) ?: return@forEach
            try {
                val subtitles = AppUtils.tryParseJson<List<MajorplaySubtitle>>(jsonArray)
                subtitles?.forEach { sub ->
                    val subUrl = when {
                        sub.path.startsWith("http", ignoreCase = true) -> sub.path
                        sub.path.startsWith("/") -> baseUrl.substringBefore("/embed") + sub.path
                        else -> "$baseUrl/${sub.path}"
                    }
                    val lang = when {
                        sub.label.contains("indonesia", true) || sub.lang.contains("id", true) -> "Indonesian"
                        sub.label.contains("english", true) || sub.lang.contains("en", true) -> "English"
                        else -> sub.label.ifBlank { sub.lang.ifBlank { "Unknown" } }
                    }
                    subtitleCallback.invoke(
                        newSubtitleFile(lang, subUrl)
                    )
                }
            } catch (_: Exception) { }
        }

        // Extract from HTML track elements
        val trackRegex = Regex("""<track[^>]*kind=["']?(?:captions|subtitles)["']?[^>]*>""", RegexOption.IGNORE_CASE)
        trackRegex.findAll(html).forEach { match ->
            val trackTag = match.value
            val srcMatch = Regex("""src=["']([^"']+)["']""").find(trackTag)
            val labelMatch = Regex("""label=["']([^"']+)["']""").find(trackTag)
            val srclangMatch = Regex("""srclang=["']([^"']+)["']""").find(trackTag)

            val subUrl = srcMatch?.groupValues?.getOrNull(1) ?: return@forEach
            val label = labelMatch?.groupValues?.getOrNull(1) ?: srclangMatch?.groupValues?.getOrNull(1) ?: ""
            val lang = when {
                label.contains("indonesia", true) -> "Indonesian"
                label.contains("english", true) -> "English"
                else -> label.ifBlank { "Unknown" }
            }
            val fullUrl = when {
                subUrl.startsWith("http", ignoreCase = true) -> subUrl
                subUrl.startsWith("/") -> baseUrl.substringBefore("/embed") + subUrl
                else -> "$baseUrl/$subUrl"
            }
            subtitleCallback.invoke(newSubtitleFile(lang, fullUrl))
        }

        // Extract direct subtitle URLs (.vtt, .srt, .ass)
        Regex("""https?://[^"'\s]+\.(?:vtt|srt|ass)(?:\?[^"'\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            .distinct()
            .forEach { subUrl ->
                subtitleCallback.invoke(newSubtitleFile("Unknown", subUrl))
            }
    }

    data class MajorplaySubtitle(
        val lang: String = "",
        val path: String = "",
        val label: String = ""
    )

    private suspend fun emitDirectMediaLinks(
        targetUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleaned = targetUrl.substringBefore('#').trim()
        if (cleaned.isBlank()) return false

        if (cleaned.contains(".m3u8", ignoreCase = true)) {
            generateM3u8(name, cleaned, refererUrl).forEach(callback)
            return true
        }

        if (cleaned.contains(".mp4", ignoreCase = true)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Auto",
                    url = cleaned,
                ) {
                    referer = refererUrl
                }
            )
            return true
        }

        val isKnownEmbed = Regex("""https?://(?:[a-z0-9-]+\.)*(?:majorplay\.net|jeniusplay\.com|stream|player|embed)/""", RegexOption.IGNORE_CASE)
            .containsMatchIn(cleaned)
        if (!isKnownEmbed) return false

        val doc = runCatching {
            app.get(cleaned, referer = refererUrl, interceptor = cloudflareInterceptor).document
        }.getOrNull() ?: return false

        val sourceSrc = doc.selectFirst("video source[src], source[src]")?.attr("abs:src")?.trim().orEmpty()
        val videoSrc = doc.selectFirst("video[src]")?.attr("abs:src")?.trim().orEmpty()
        val scriptData = doc.select("script").joinToString("\n") { it.data() }

        val patterns = listOf(
            Regex("""["']hlsUrl["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']src["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']url["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*["']file["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE),
        )

        val scriptUrl = patterns.firstNotNullOfOrNull { regex ->
            regex.find(scriptData)?.groupValues?.getOrNull(1)?.trim()
        } ?: ""

        val streamUrl = listOf(sourceSrc, videoSrc, scriptUrl).firstOrNull { it.startsWith("http", ignoreCase = true) }
            ?: return false

        return if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            generateM3u8(name, streamUrl, cleaned).forEach(callback)
            true
        } else if (streamUrl.contains(".mp4", ignoreCase = true)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Auto",
                    url = streamUrl,
                ) {
                    referer = cleaned
                }
            )
            true
        } else {
            false
        }
    }
}

fun getSearchQuality(check: String?): SearchQuality? {
    val s = check ?: return null
    val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,

        // CAM / THEATRE SOURCES FIRST
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,

        // WEB / RIP
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,

        // BLURAY
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,

        // RESOLUTIONS
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,

        // GENERIC HD LAST
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,

        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
    )


    for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
    return null
}

