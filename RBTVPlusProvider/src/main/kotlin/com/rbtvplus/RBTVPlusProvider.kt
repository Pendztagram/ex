package com.rbtvplus

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class RBTVPlusProvider : MainAPI() {
    override var mainUrl = "https://www.rbtvplus18.xyz"
    override var name = "RBTV+ Football"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "live" to "Live Football",
    )

    private val apiUrl = "https://apis-data10.tcrok62jdmd.cfd"
    private val mapper = jacksonObjectMapper()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val matches = fetchFootballMatches()
            .filter { it.isPlayable || it.status == 10000 || it.status == 101 }
            .distinctBy { it.id }
        return newHomePageResponse(request.name, matches.map { it.toSearchResponse() }, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        return fetchFootballMatches()
            .filter { match ->
                match.title.contains(clean, true) ||
                    match.league.contains(clean, true) ||
                    match.country.contains(clean, true)
            }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = parsePayload(url)
            ?: fetchFootballMatches().firstOrNull { url.contains(it.id.toString()) }
            ?: throw ErrorLoadingException("Match tidak ditemukan")

        return newMovieLoadResponse(payload.title, payload.url, TvType.Live, payload.toJson()) {
            posterUrl = payload.poster
            plot = buildString {
                if (payload.league.isNotBlank()) append(payload.league)
                if (payload.country.isNotBlank()) append(if (isEmpty()) payload.country else " - ${payload.country}")
                if (payload.timeText.isNotBlank()) append(if (isEmpty()) payload.timeText else "\n${payload.timeText}")
                if (payload.score.isNotBlank()) append(if (isEmpty()) payload.score else "\n${payload.score}")
            }.ifBlank { null }
            tags = listOfNotNull(payload.league.takeIf { it.isNotBlank() }, "Football", "Live")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parsePayload(data) ?: return false
        val emitted = linkedSetOf<String>()
        var found = false

        val candidates = buildList {
            addAll(fetchStreamCandidates(payload))
            add(payload.url)
        }.distinct()

        candidates.forEach { link ->
            if (!emitted.add(link)) return@forEach
            found = true
            if (link.contains(".m3u8", true) || link.contains(".mpd", true) || link.endsWith(".flv", true) || link.endsWith(".mp4", true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        referer = payload.url
                        quality = Qualities.Unknown.value
                        headers = mapOf(
                            "Referer" to payload.url,
                            "Origin" to mainUrl,
                            "User-Agent" to USER_AGENT,
                        )
                    }
                )
            } else {
                runCatching { loadExtractor(link, payload.url, subtitleCallback, callback) }
            }
        }

        return found
    }

    private suspend fun fetchFootballMatches(): List<FootballMatch> {
        val signature = fetchSignature(100, mapOf("sportType" to "1", "stream" to "true")) ?: return emptyList()
        val url = "$apiUrl/sfver915c76$signature/api/match/live?sportType=1&language=34&stream=true"
        val bytes = requestBytes(url, "$mainUrl/id/football.html")
        val body = ProtoReader(bytes).fields().firstOrNull { it.number == 10 }?.bytes ?: return emptyList()
        val playableIds = mutableSetOf<Long>()
        val matches = ProtoReader(body).fields().mapNotNull { field ->
            when (field.number) {
                1 -> field.bytes?.let(::parseMatch)
                2 -> parsePlayableId(field.bytes)?.let {
                    playableIds.add(it)
                    null
                }
                else -> null
            }
        }
        return matches.map { it.copy(isPlayable = it.id in playableIds) }
    }

    private fun parseMatch(bytes: ByteArray): FootballMatch? {
        val fields = ProtoReader(bytes).fields()
        val id = fields.firstVarint(1) ?: return null
        val timestamp = fields.firstVarint(3) ?: 0L
        val status = fields.firstVarint(4)?.toInt() ?: 0
        val league = fields.firstMessage(10)?.localizedName().orEmpty()
        val country = fields.firstMessage(10)?.firstMessage(80)?.localizedName().orEmpty()
        val title = fields.filter { it.number == 30 }
            .mapNotNull { it.bytes?.let(::ProtoReader)?.fields()?.firstString(2) }
            .firstOrNull { it.contains(" vs ", true) }
            ?: return null
        val teams = title.split(" vs ", limit = 2)
        val home = teams.getOrNull(0).orEmpty()
        val away = teams.getOrNull(1).orEmpty()
        val homeScore = fields.firstVarint(22)
        val awayScore = fields.firstVarint(23)
        val extra = fields.firstMessage(150)
        val teamLink = extra?.firstString(20).orEmpty()
        val leagueLink = extra?.firstString(21).orEmpty()
        val slug = if (leagueLink.isNotBlank() && teamLink.isNotBlank()) {
            "$mainUrl/id/football/$leagueLink-$id/$teamLink.html"
        } else {
            "$mainUrl/id/football.html#match-$id"
        }
        val score = if (homeScore != null && awayScore != null) "$homeScore - $awayScore" else ""
        return FootballMatch(
            id = id,
            title = title,
            home = home,
            away = away,
            league = league,
            country = country,
            status = status,
            startTime = timestamp,
            score = score,
            url = slug,
        )
    }

    private fun parsePlayableId(bytes: ByteArray?): Long? {
        if (bytes == null) return null
        return ProtoReader(bytes).fields().firstVarint(50)
    }

    private suspend fun fetchStreamCandidates(payload: FootballMatch): List<String> {
        val detailUrl = "$apiUrl/api/stream/detail?matchId=${payload.id}&sportType=1&language=34&stream=true"
        val detail = runCatching { requestBytes(detailUrl, payload.url) }.getOrNull() ?: ByteArray(0)
        val text = detail.toString(StandardCharsets.UTF_8)
        return Regex("""https?://[^\s"'<>\\]+""")
            .findAll(text)
            .map { it.value.replace("\\/", "/").replace("\\u0026", "&") }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".flv", true) || it.contains("stream", true) }
            .distinct()
            .toList()
    }

    private suspend fun fetchSignature(code: Int, params: Map<String, String>): String? {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val url = "$apiUrl/api/common/bs?code=$code&$query"
        val text = requestBytes(url, "$mainUrl/id/football.html").toString(StandardCharsets.ISO_8859_1)
        return Regex("""[a-f0-9]{32,40}""").find(text)?.value
    }

    private suspend fun requestBytes(url: String, referer: String): ByteArray {
        return app.get(
            url,
            referer = referer,
            headers = mapOf(
                "Accept" to "*/*",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
            )
        ).body.bytes()
    }

    private fun FootballMatch.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(title, toJson(), TvType.Live) {
            posterUrl = poster
        }
    }

    private fun FootballMatch.toJson(): String = mapper.writeValueAsString(this)

    private fun parsePayload(value: String): FootballMatch? {
        return runCatching { mapper.readValue(value, FootballMatch::class.java) }.getOrNull()
    }

    private fun List<ProtoField>.firstVarint(number: Int): Long? = firstOrNull { it.number == number }?.varint
    private fun List<ProtoField>.firstString(number: Int): String? = firstOrNull { it.number == number }?.string
    private fun List<ProtoField>.firstMessage(number: Int): List<ProtoField>? = firstOrNull { it.number == number }?.bytes?.let { ProtoReader(it).fields() }

    private fun List<ProtoField>.localizedName(): String? {
        return firstMessage(3)?.firstString(2)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FootballMatch(
        val id: Long = 0,
        val title: String = "",
        val home: String = "",
        val away: String = "",
        val league: String = "",
        val country: String = "",
        val status: Int = 0,
        val startTime: Long = 0,
        val score: String = "",
        val url: String = "",
        val poster: String? = null,
        val isPlayable: Boolean = false,
    ) {
        val timeText: String
            get() = if (startTime > 0) "Kickoff: $startTime" else ""
    }

    data class ProtoField(
        val number: Int,
        val wireType: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null,
    ) {
        val string: String?
            get() = bytes?.toString(StandardCharsets.UTF_8)?.takeIf { text ->
                text.isNotBlank() && text.any { it.isLetterOrDigit() }
            }
    }

    class ProtoReader(private val bytes: ByteArray) {
        private var index = 0

        fun fields(): List<ProtoField> {
            val out = mutableListOf<ProtoField>()
            index = 0
            while (index < bytes.size) {
                val key = readVarint().toInt()
                if (key == 0) break
                val number = key ushr 3
                val wireType = key and 7
                when (wireType) {
                    0 -> out.add(ProtoField(number, wireType, varint = readVarint()))
                    1 -> index = (index + 8).coerceAtMost(bytes.size)
                    2 -> {
                        val length = readVarint().toInt()
                        if (length < 0 || index + length > bytes.size) break
                        out.add(ProtoField(number, wireType, bytes = bytes.copyOfRange(index, index + length)))
                        index += length
                    }
                    5 -> index = (index + 4).coerceAtMost(bytes.size)
                    else -> break
                }
            }
            return out
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (index < bytes.size && shift < 64) {
                val value = bytes[index++].toInt() and 0xFF
                result = result or ((value and 0x7F).toLong() shl shift)
                if (value and 0x80 == 0) break
                shift += 7
            }
            return result
        }
    }
}
