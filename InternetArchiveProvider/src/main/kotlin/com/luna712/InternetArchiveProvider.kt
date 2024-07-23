package com.luna712

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern

class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    private val mapper by lazy {
        // Some metadata uses different formats. We have to handle that here.
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, true)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val responseText = app.get("$mainUrl/advancedsearch.php?q=mediatype:(movies)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=26&page=$page&output=json").text
            val featured = tryParseJson<SearchResult>(responseText)
            val homePageList = featured?.response?.docs?.map { it.toSearchResponse(this) } ?: emptyList()
            newHomePageResponse(
                listOf(
                    HomePageList("Featured", homePageList, true)
                ),
                false
            )
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val responseText = app.get("$mainUrl/advancedsearch.php?q=${query.encodeUri()}+mediatype:(movies OR audio)&fl[]=identifier&fl[]=title&fl[]=mediatype&rows=26&output=json").text
            val res = tryParseJson<SearchResult>(responseText)
            res?.response?.docs?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val identifier = url.substringAfterLast("/")
            val responseText = app.get("$mainUrl/metadata/$identifier").text
            val res = mapper.readValue<MetadataResult>(responseText)
            res.toLoadResponse(this)
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Error loading: invalid json response")
        }
    }

    private data class SearchResult(
        val response: DocsResponse
    )

    private data class DocsResponse(
        val docs: List<SearchEntry>
    )

    private data class SearchEntry(
        val identifier: String,
        val mediatype: String,
        val title: String?
    ) {
        fun toSearchResponse(provider: InternetArchiveProvider): SearchResponse {
            val type = if (mediatype == "audio") {
                TvType.Music
            } else TvType.Movie
            return provider.newMovieSearchResponse(
                title ?: identifier,
                "${provider.mainUrl}/details/$identifier",
                type
            ) {
                this.posterUrl = "${provider.mainUrl}/services/img/$identifier"
            }
        }
    }

    private data class MetadataResult(
        val metadata: MediaEntry,
        val files: List<MediaFile>,
        val dir: String,
        val server: String
    ) {
        companion object {
            private val seasonEpisodePatterns = listOf(
                Regex("S(\\d+)E(\\d+)", RegexOption.IGNORE_CASE), // S01E01
                Regex("S(\\d+)\\s*E(\\d+)", RegexOption.IGNORE_CASE), // S01 E01
                Regex("Season\\s*(\\d+)\\D*Episode\\s*(\\d+)", RegexOption.IGNORE_CASE), // Season 1 Episode 1
                Regex("Episode\\s*(\\d+)\\D*Season\\s*(\\d+)", RegexOption.IGNORE_CASE), // Episode 1 Season 1
                Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE) // Episode 1
            )
        }

        private fun extractEpisodeInfo(fileName: String): Pair<Int?, Int?> {
            for (pattern in seasonEpisodePatterns) {
                val matchResult = pattern.find(fileName)
                if (matchResult != null) {
                    val groups = matchResult.groupValues
                    return when (groups.size) {
                        3 -> Pair(groups[1].toIntOrNull(), groups[2].toIntOrNull()) // S01E01, S01 E01
                        2 -> Pair(null, groups[1].toIntOrNull()) // Episode 1
                        5 -> Pair(groups[1].toIntOrNull(), groups[3].toIntOrNull()) // Season 1 Episode 1
                        else -> Pair(null, null)
                    }
                }
            }
            return Pair(null, null)
        }

        private fun extractYear(dateString: String?): Int? {
            // If it is impossible to find a date in the given string,
            // we can exit early
            if (dateString == null || dateString.length < 4) return null

            if (dateString.length == 4) {
                // If the date is already a year (or it can not be one),
                // we can exit early
                return dateString.toIntOrNull()
            }

            val yearPattern = "\\b(\\d{4})\\b"
            val yearRangePattern = "\\b(\\d{4})-(\\d{4})\\b"

            // Check for year ranges like YYYY-YYYY and get the start year if a match is found
            val yearRangeMatcher = Pattern.compile(yearRangePattern).matcher(dateString)
            if (yearRangeMatcher.find()) {
                return yearRangeMatcher.group(1)?.toInt()
            }

            // Check for single years within the date string in various formats
            val yearMatcher = Pattern.compile(yearPattern).matcher(dateString)
            if (yearMatcher.find()) {
                return yearMatcher.group(1)?.toInt()
            }

            return null
        }

        private fun getThumbnailUrl(fileName: String): String? {
            val thumbnail = files.find {
                it.format == "Thumbnail" && it.original == fileName
            }
            return thumbnail?.let { "https://${server}${dir}/${it.name}" }
        }

        private fun getCleanedName(fileName: String): String {
            return fileName
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace('_', ' ')
        }

        private fun getUniqueName(fileName: String): String {
            return getCleanedName(fileName)
                // Some files have two versions one with ".ia"
                // and one without. In this case, we do not want
                // treat the files as two separate files when checking
                // for uniqueness, otherwise it will think it is a playlist
                // when that is not the case.
                .replace(".ia", "")
        }

        private fun timeToSeconds(time: String): Float {
            val parts = time.split(":")
            return when (parts.size) {
                2 -> {
                    val minutes = parts[0].toFloatOrNull() ?: 0f
                    val seconds = parts[1].toFloatOrNull() ?: 0f
                    (minutes * 60) + seconds
                }
                3 -> {
                    val hours = parts[0].toFloatOrNull() ?: 0f
                    val minutes = parts[1].toFloatOrNull() ?: 0f
                    val seconds = parts[2].toFloatOrNull() ?: 0f
                    (hours * 3600) + (minutes * 60) + seconds
                }
                else -> 0f
            }
        }

        suspend fun toLoadResponse(provider: InternetArchiveProvider): LoadResponse {
            val videoFiles = files.asSequence()
                .filter {
                    val lengthInSeconds = it.length?.toFloatOrNull() ?: run {
                        // Check if length is in a different format and convert to seconds
                        if (it.length?.contains(":") == true) {
                            timeToSeconds(it.length)
                        } else 0f
                    }
                    lengthInSeconds >= 10.0 &&
                            (it.format.contains("MPEG", true) ||
                                    it.format.startsWith("H.264", true) ||
                                    it.format.startsWith("Matroska", true) ||
                                    it.format.startsWith("DivX", true) ||
                                    it.format.startsWith("Ogg Video", true))
                }

            val type = if (metadata.mediatype == "audio") {
                TvType.Music
            } else TvType.Movie

            return if (videoFiles.distinctBy { getUniqueName(it.name) }.count() <= 1 || type == TvType.Music) {
                // TODO if audio-playlist, use tracks
                provider.newMovieLoadResponse(
                    metadata.title ?: metadata.identifier,
                    "${provider.mainUrl}/details/${metadata.identifier}",
                    type,
                    metadata.identifier
                ) {
                    plot = metadata.description
                    year = extractYear(metadata.date)
                    tags = if (metadata.subject?.count() == 1) {
                        metadata.subject[0].split(";")
                    } else metadata.subject
                    posterUrl = "${provider.mainUrl}/services/img/${metadata.identifier}"
                    actors = metadata.creator?.map {
                        ActorData(Actor(it, ""), roleString = "Creator")
                    }
                }
            } else {
                /**
                 * This may not be a TV series but we use it for video playlists as
                 * it is better for resuming (or downloading) what specific track
                 * you are on.
                 */
                val urlMap = mutableMapOf<String, MutableSet<String>>()

                videoFiles.forEach { file ->
                    val cleanedName = getCleanedName(file.name)
                    val videoFileUrl = "https://$server$dir/${file.name}"
                    if (urlMap.containsKey(cleanedName)) {
                        urlMap[cleanedName]?.add(videoFileUrl)
                    } else urlMap[cleanedName] = mutableSetOf(videoFileUrl)
                }

                val episodes = urlMap.map { (fileName, urls) ->
                    val file = videoFiles.first { getCleanedName(it.name) == fileName }
                    val episodeInfo = extractEpisodeInfo(file.name)
                    val season = episodeInfo.first
                    val episode = episodeInfo.second

                    Episode(
                        data = LoadData(
                            urls = urls,
                            name = fileName,
                            type = "video-playlist"
                        ).toJson(),
                        name = fileName,
                        season = season,
                        episode = episode,
                        posterUrl = getThumbnailUrl(file.name)
                    )
                }.sortedWith(compareBy({ it.season }, { it.episode }))

                provider.newTvSeriesLoadResponse(
                    metadata.title ?: metadata.identifier,
                    "${provider.mainUrl}/details/${metadata.identifier}",
                    TvType.TvSeries,
                    episodes
                ) {
                    plot = metadata.description
                    year = extractYear(metadata.date)
                    tags = if (metadata.subject?.count() == 1) {
                        metadata.subject[0].split(";")
                    } else metadata.subject
                    posterUrl = "${provider.mainUrl}/services/img/${metadata.identifier}"
                    actors = metadata.creator?.map {
                        ActorData(Actor(it, ""), roleString = "Creator")
                    }
                }
            }
        }
    }

    private data class MediaEntry(
        val identifier: String,
        val mediatype: String,
        val title: String?,
        val description: String?,
        val subject: List<String>?,
        val creator: List<String>?,
        val date: String?
    )

    private data class MediaFile(
        val name: String,
        val source: String,
        val format: String,
        val original: String?,
        val length: String?
    )

    data class LoadData(
        val urls: Set<String>,
        val type: String,
        val name: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = tryParseJson<LoadData>(data)
        // TODO if audio-playlist, use tracks
        if (load?.type == "video-playlist") {
            fun getName(url: String): String {
                val directory = url
                    .substringBeforeLast("/")
                    .substringAfterLast("/")
                    .substringBeforeLast('.')
                    .replace('_', ' ')
                val extension = url.substringAfterLast(".")
                return if (load.name != directory && load.urls.count() > 1) "$directory ($extension)" else name
            }

            load.urls.sorted().forEach { url ->
                callback(
                    ExtractorLink(
                        this.name,
                        getName(url),
                        url,
                        "",
                        Qualities.Unknown.value
                    )
                )
            }
        } else {
            loadExtractor(
                "https://archive.org/details/$data",
                subtitleCallback,
                callback
            )
        }
        return true
    }

    companion object {
        fun String.encodeUri(): String = URLEncoder.encode(this, "utf8")

        fun String.decodeUri(): String = URLDecoder.decode(this, "utf8")
    }

    class InternetArchiveExtractor : ExtractorApi() {
        override val mainUrl = "https://archive.org"
        override val requiresReferer = false
        override val name = "Internet Archive"

        companion object {
            private var archivedItems: MutableMap<String, Document> = mutableMapOf()
        }

        override fun getExtractorUrl(id: String): String {
            return "$mainUrl/details/$id"
        }

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val document = archivedItems[url] ?: run {
                try {
                    val doc = Jsoup.connect(url).get()
                    archivedItems[url] = doc
                    doc
                } catch (e: Exception) {
                    logError(e)
                    return
                }
            }

            val fileLinks = document.select("a[href*=\"/download/\"]").filter { element ->
                val mediaUrl = element.attr("href")

                mediaUrl.endsWith(".mp4", true) ||
                        mediaUrl.endsWith(".mpg", true) ||
                        mediaUrl.endsWith(".mkv", true) ||
                        mediaUrl.endsWith(".avi", true) ||
                        mediaUrl.endsWith(".ogv", true) ||
                        mediaUrl.endsWith(".ogg", true) ||
                        mediaUrl.endsWith(".mp3", true) ||
                        mediaUrl.endsWith(".wav", true) ||
                        mediaUrl.endsWith(".flac", true)
            }

            val select = fileLinks.ifEmpty {
                document.head().select("meta[property=\"og:video\"]")
            }

            select.forEach {
                val mediaUrl = when {
                    it.hasAttr("href") -> mainUrl + it.attr("href")
                    it.hasAttr("content") -> it.attr("content")
                    else -> return@forEach
                }

                val fileName = mediaUrl.substringAfterLast('/')
                val quality = when {
                    fileName.contains("1080", true) -> Qualities.P1080.value
                    fileName.contains("720", true) -> Qualities.P720.value
                    fileName.contains("480", true) -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }

                if (mediaUrl.isNotEmpty()) {
                    val name = if (mediaUrl.count() > 1) {
                        val fileExtension = mediaUrl.substringAfterLast(".")
                        val fileNameCleaned = fileName.decodeUri().substringBeforeLast('.')
                        "$fileNameCleaned ($fileExtension)"
                    } else this.name
                    callback(
                        ExtractorLink(
                            this.name,
                            name,
                            mediaUrl,
                            "",
                            quality
                        )
                    )
                }
            }
        }
    }
}