package com.cinemacityprovider

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.Locale

class CinemacityProvider : MainAPI() {
    override var mainUrl = "https://cinemacity.cc"
    override var name = "Cinemacity"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/%d/" to "Movie",
        "$mainUrl/tv-series/page/%d/" to "TV Series",
        "filter://anime/%d" to "Anime",
        "filter://asian/%d" to "Asian",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val home = when {
            data.startsWith("filter://anime/") -> getFilteredByGenre(page, "anime")
            data.startsWith("filter://asian/") -> getFilteredByGenre(page, "asian")
            else -> {
                val url = if (data.contains("%d")) data.format(page) else data
                val document = app.get(url).document
                document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
            }
        }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getFilteredByGenre(page: Int, genre: String): List<SearchResponse> {
        val movieDoc = app.get("$mainUrl/movies/page/$page/").document
        val seriesDoc = app.get("$mainUrl/tv-series/page/$page/").document
        val cards = movieDoc.select("div.dar-short_item") + seriesDoc.select("div.dar-short_item")
        return cards.mapNotNull { it.toSearchResultByGenre(genre) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val document = app.post(
            "$mainUrl/index.php",
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query,
            ),
        ).document

        val results = document.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return results.toNewSearchResponseList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a[href*=\"/movies/\"], a[href*=\"/tv-series/\"]")?.attr("href") ?: return null
        val title = selectFirst("a.e-nowrap")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img.poster")?.attr("src"))
        val year = Regex("\\((\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return if (href.contains("/tv-series/")) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun Element.toSearchResultByGenre(genre: String): SearchResponse? {
        val genresText = selectFirst("div.dar-short_meta")?.text()?.lowercase().orEmpty()
        if (!genresText.contains(genre.lowercase())) return null
        return toSearchResult()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(document.selectFirst("div.dar-full_poster img")?.attr("src"))
        val background = fixUrlNull(document.selectFirst("div.dar-full_bg img")?.attr("src"))
        val plot = document.selectFirst("div.ta-full_text1")?.text()?.trim()
        val tags = document.select("div.ta-full_meta a[href*=\"/xfsearch/genre/\"]").map { it.text().trim() }.distinct()
        val year = Regex("\\((\\d{4})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val trailer = document.selectFirst("a[href*=\"youtube.com/watch\"]")?.attr("href")
        val recs = document.select("div.ta-rel_item").mapNotNull { it.toRecommendation() }

        val isSeries = url.contains("/tv-series/")
        return if (isSeries) {
            val episodes = extractEpisodes(document, url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                addTrailer(trailer)
                this.recommendations = recs
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, LinkData(url).toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.tags = tags
                this.year = year
                addTrailer(trailer)
                this.recommendations = recs
            }
        }
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, url: String): List<Episode> {
        val playlist = extractPlaylist(document)
        if (playlist.isNotEmpty() && playlist.any { !it.folder.isNullOrEmpty() }) {
            val episodes = mutableListOf<Episode>()
            playlist.forEachIndexed { sIdx, season ->
                season.folder.orEmpty().forEachIndexed { eIdx, ep ->
                    episodes.add(
                        newEpisode(
                            LinkData(
                                url = url,
                                seasonIndex = sIdx,
                                episodeIndex = eIdx,
                                qualityIndex = null,
                            ).toJson()
                        ) {
                            this.name = ep.title?.ifBlank { null } ?: "Episode ${eIdx + 1}"
                            this.season = sIdx + 1
                            this.episode = eIdx + 1
                        }
                    )
                }
            }
            if (episodes.isNotEmpty()) return episodes
        }

        val out = linkedMapOf<String, Episode>()

        fun addEpisode(season: Int?, episode: Int?, name: String? = null) {
            if (episode == null) return
            val key = "${season ?: 1}_$episode"
            if (out.containsKey(key)) return
            out[key] = newEpisode(LinkData(url).toJson()) {
                this.name = name ?: "Episode $episode"
                this.season = season ?: 1
                this.episode = episode
            }
        }

        // 1) Direct selectors in section download (closest to site structure)
        val seasons = document.select("select[name=dar-dl_season] option")
            .mapNotNull { Regex("(\\d+)").find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .distinct()
        val eps = document.select("select[name=dar-dl_episode] option")
            .mapNotNull { Regex("(\\d+)").find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .distinct()
        if (seasons.isNotEmpty() && eps.isNotEmpty()) {
            seasons.forEach { s -> eps.forEach { e -> addEpisode(s, e) } }
        }

        // 2) Parse download filenames: FROM.S1E1.1080p...
        val fileRegex = Regex("S(\\d+)E(\\d+)", RegexOption.IGNORE_CASE)
        document.select("div#download div.dar-tr_title > div").forEach { row ->
            val text = row.text().trim()
            val match = fileRegex.find(text) ?: return@forEach
            val season = match.groupValues.getOrNull(1)?.toIntOrNull()
            val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
            addEpisode(season, episode, "Episode ${episode ?: "?"}")
        }

        // 3) Parse schedule block: "Episode 3 - May 3, 2026"
        val schedRegex = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
        document.select("div.ta-full_text1").forEach { block ->
            block.text().lineSequence().forEach { line ->
                val ep = schedRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEach
                addEpisode(1, ep)
            }
        }

        val episodes = out.values.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 })).toMutableList()
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(LinkData(url).toJson()) {
                    this.name = "Episode 1"
                    this.episode = 1
                    this.season = 1
                }
            )
        }
        return episodes
    }

    private fun Element.toRecommendation(): SearchResponse? {
        val href = selectFirst("a.e-nowrap")?.attr("href") ?: return null
        val title = selectFirst("a.e-nowrap")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img.poster")?.attr("src"))
        return if (href.contains("/tv-series/")) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = AppUtils.parseJson<LinkData>(data)
        val document = app.get(parsed.url).document
        val playlist = extractPlaylist(document)

        if (playlist.isNotEmpty()) {
            val selected = if (parsed.seasonIndex != null && parsed.episodeIndex != null) {
                val season = playlist.getOrNull(parsed.seasonIndex)
                season?.folder?.getOrNull(parsed.episodeIndex)
            } else {
                playlist.getOrNull(parsed.qualityIndex ?: 0)
            }

            val fileSet = selected?.file
            if (!fileSet.isNullOrBlank()) {
                val stream = parseFileSet(fileSet, selected.subtitle)
                var hasLink = false

                // 1) direct m3u8/mp4 if present
                stream.directLinks.forEach { raw ->
                    val link = fixUrl(raw)
                    if (link.contains(".m3u8", true)) {
                        generateM3u8(name, link, parsed.url).forEach(callback)
                        hasLink = true
                    } else if (link.contains(".mp4", true)) {
                        callback(newExtractorLink(name, "$name MP4", link, INFER_TYPE) {
                            this.referer = parsed.url
                            this.quality = getQualityFromName(link)
                        })
                        hasLink = true
                    } else {
                        try {
                            loadExtractor(link, parsed.url, subtitleCallback, callback)
                            hasLink = true
                        } catch (_: Throwable) {
                        }
                    }
                }

                // 2) Cinemacity internal downloader endpoint (same as website)
                val newsId = Regex("/(\\d+)-").find(parsed.url)?.groupValues?.getOrNull(1)
                val userHash = Regex("var\\s+dle_login_hash\\s*=\\s*'([^']+)'")
                    .find(document.html())
                    ?.groupValues?.getOrNull(1)

                if (!newsId.isNullOrBlank() && !userHash.isNullOrBlank()) {
                    val subtitles = stream.subtitles.joinToString(",")
                    val audioList = if (stream.audio.isNotEmpty()) stream.audio else listOf("")
                    val videoList = if (stream.video.isNotEmpty()) stream.video else listOf("")

                    videoList.forEach { video ->
                        audioList.forEach { audio ->
                            if (video.isBlank() || audio.isBlank()) return@forEach
                            val dlUrl = buildString {
                                append(stream.base)
                                append("?action=download")
                                append("&video=${encode(video)}")
                                append("&audio=${encode(audio)}")
                                if (subtitles.isNotBlank()) append("&subtitle=${encode(subtitles)}")
                                append("&name=${encode(buildDlName(parsed, video, audio))}")
                                append("&news_id=${encode(newsId)}")
                                append("&user_hash=${encode(userHash)}")
                            }
                            callback(
                                newExtractorLink(name, "$name ${qualityFromPath(video)}", dlUrl, INFER_TYPE) {
                                    this.referer = parsed.url
                                    this.quality = getQualityFromName(video)
                                }
                            )
                            hasLink = true
                        }
                    }
                }

                if (hasLink) return true
            }
        }

        val html = document.html()

        val rawCandidates = mutableListOf<String>()
        rawCandidates += Regex("""file\s*:\s*["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()
        rawCandidates += Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(html).map { it.value }.toList()
        rawCandidates += Regex("""https?://[^\s"'<>]+""").findAll(html).map { it.value }.filter {
            it.contains("stream", true) || it.contains("embed", true) || it.contains("m3u8", true)
        }.toList()
        val candidates = rawCandidates.map { fixUrl(it) }.distinct()

        var found = false
        candidates.forEach { link ->
            try {
                if (link.contains(".m3u8")) {
                    generateM3u8(name, link, parsed.url).forEach(callback)
                    found = true
                } else {
                    loadExtractor(link, parsed.url, subtitleCallback, callback)
                    found = true
                }
            } catch (_: Throwable) {
            }
        }
        return found
    }

    private fun extractPlaylist(document: org.jsoup.nodes.Document): List<PlaylistItem> {
        val script = document.selectFirst("div[id^=player] + script") ?: return emptyList()
        val scriptBody = script.html()
        val b64 = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
            .find(scriptBody)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val decoded = runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        }.getOrNull() ?: return emptyList()

        val fileRaw = Regex("""file\s*:\s*(['"])(.*?)\1\s*,\s*poster""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(decoded)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { unescapeJsString(it) }
            ?: return emptyList()

        if (!fileRaw.trim().startsWith("[")) return emptyList()
        return runCatching { AppUtils.parseJson<List<PlaylistItem>>(fileRaw) }.getOrElse { emptyList() }
    }

    private fun unescapeJsString(input: String): String {
        return input
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun parseFileSet(file: String, subtitle: String?): ParsedFileSet {
        val parts = file.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val audio = parts.filter { it.contains(".m4a", true) && !it.contains(".urlset/master.m3u8", true) }
        val video = parts.filter { it.contains(".mp4", true) && !it.contains(".urlset/master.m3u8", true) }
        val m3u8 = parts.filter { it.contains(".m3u8", true) && !it.contains(".urlset/master.m3u8", true) }
        val direct = (m3u8 + video).distinct()
        val subtitles = subtitle.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
        return ParsedFileSet(base = parts.firstOrNull().orEmpty(), audio = audio, video = video, subtitles = subtitles, directLinks = direct)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun qualityFromPath(path: String): String {
        return Regex("_(\\d{3,4}p)\\.mp4", RegexOption.IGNORE_CASE)
            .find(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.uppercase(Locale.ROOT)
            ?: "MP4"
    }

    private fun buildDlName(parsed: LinkData, video: String, audio: String): String {
        val res = qualityFromPath(video).lowercase(Locale.ROOT)
        val lang = Regex("_([^_/]+)\\.m4a", RegexOption.IGNORE_CASE).find(audio)?.groupValues?.getOrNull(1) ?: "audio"
        return if (parsed.seasonIndex != null && parsed.episodeIndex != null) {
            "S${parsed.seasonIndex + 1}E${parsed.episodeIndex + 1}.$res.$lang"
        } else {
            "movie.$res.$lang"
        }
    }
}

data class LinkData(
    val url: String,
    val seasonIndex: Int? = null,
    val episodeIndex: Int? = null,
    val qualityIndex: Int? = null,
)

data class PlaylistItem(
    val title: String? = null,
    val file: String? = null,
    val subtitle: String? = null,
    val folder: List<PlaylistItem>? = null,
)

data class ParsedFileSet(
    val base: String,
    val audio: List<String>,
    val video: List<String>,
    val subtitles: List<String>,
    val directLinks: List<String>,
)
