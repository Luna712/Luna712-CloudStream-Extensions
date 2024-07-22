package com.luna712

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
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

class InternetArchiveProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

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
            val res = tryParseJson<MetadataResult>(responseText)
            res?.toLoadResponse(this)
        } catch (e: Exception) {
            logError(e)
            null
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

        fun extractEpisodeInfo(fileName: String): Pair<Int?, Int?> {
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

        private fun getThumbnailUrl(fileName: String): String? {
            val thumbnail = files.find {
                it.format == "Thumbnail" && it.original == fileName
            }
            return thumbnail?.let { "https://${server}${dir}/${it.name}" }
        }

        suspend fun toLoadResponse(provider: InternetArchiveProvider): LoadResponse {
            val videoFiles = files.asSequence()
                .filter {
                    it.source == "original" &&
                            (it.length != null && it.length >= 10.0) &&
                            (it.format.contains("MPEG", true) ||
                                    it.format.startsWith("H.264", true) ||
                                    it.format.startsWith("Matroska", true) ||
                                    it.format.startsWith("DivX", true) ||
                                    it.format.startsWith("Ogg Video", true) ||
                                    it.format.startsWith("DVD Info", true) ||
                                    it.format.startsWith("ISO Image", true))
                }.toList()

            val fileUrls = videoFiles.asSequence()
                .map { "${provider.mainUrl}$dir/${it.name}" }
                .toList()

            val type = if (metadata.mediatype == "audio") {
                TvType.Music
            } else TvType.Movie

            return if (fileUrls.size <= 1 || type == TvType.Music) {
                // TODO if audio-playlist, use tracks
                provider.newMovieLoadResponse(
                    metadata.title ?: metadata.identifier,
                    "${provider.mainUrl}/details/${metadata.identifier}",
                    type,
                    metadata.identifier
                ) {
                    plot = metadata.description
                    posterUrl = "${provider.mainUrl}/services/img/${metadata.identifier}"
                    actors = listOfNotNull(
                        metadata.creator?.let { ActorData(Actor(it, ""), roleString = "Creator") }
                    )
                }
            } else {
                // This may not be a TV series but we use it for video playlists as
                // it is better for resuming (or downloading) what specific track
                // you are on.
                fun createKey(fileName: String): String {
                    return fileName.substringAfterLast('/')
                        .substringBeforeLast('.')
                }

                val urlMap = mutableMapOf<String, MutableSet<String>>()

                videoFiles.forEach { file ->
                    val key = createKey(file.name)
                    val url = "$server$dir/${file.name}"
                    urlMap.getOrPut(key) { mutableSetOf() }.add(url)
                }

                provider.newTvSeriesLoadResponse(
                    metadata.title ?: metadata.identifier,
                    "${provider.mainUrl}/details/${metadata.identifier}",
                    TvType.TvSeries,
                    urlMap.map { (key, urls) ->
                        val file = videoFiles.first { createKey(it.name) == key }
                        val episodeInfo = extractEpisodeInfo(file.name)
                        Episode(
                            data = Load(
                                urls = urls,
                                name = key.replace('_', ' '),
                                type = "video-playlist"
                            ).toJson(),
                            season = episodeInfo.first,
                            episode = episodeInfo.second,
                            name = key.replace('_', ' '),
                            posterUrl = getThumbnailUrl(file.name)
                        )
                    }
                ) {
                    plot = metadata.description
                    posterUrl = "${provider.mainUrl}/services/img/${metadata.identifier}"
                    actors = listOfNotNull(
                        metadata.creator?.let { ActorData(Actor(it, ""), roleString = "Creator") }
                    )
                }
            }
        }
    }

    private data class MediaEntry(
        val identifier: String,
        val mediatype: String,
        val title: String?,
        val description: String?,
        val creator: String?
    )

    private data class MediaFile(
        val name: String,
        val source: String,
        val format: String,
        val original: String?,
        val length: Float?
    )

    data class Load(
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
        val load = tryParseJson<Load>(data)
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
                        "https://$url",
                        "",
                        Qualities.Unknown.value,
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

            val fileLinks = document.select("a[href*=\"/download/\"]")

            val select = if (fileLinks.isEmpty()) {
                document.head().select("meta[property*=\"og:video\"]")
            } else fileLinks

            select.forEach {
                val mediaUrl = when {
                    it.hasAttr("href") -> mainUrl + it.attr("href")
                    it.hasAttr("content") -> it.attr("content")
                    else -> return@forEach
                }
                if (
                    mediaUrl.endsWith(".mp4", true) ||
                        mediaUrl.endsWith(".mpg", true) ||
                        mediaUrl.endsWith(".mkv", true) ||
                        mediaUrl.endsWith(".avi", true) ||
                        mediaUrl.endsWith(".ogv", true) ||
                        mediaUrl.endsWith(".ogg", true) ||
                        mediaUrl.endsWith(".ifo", true) ||
                        mediaUrl.endsWith(".bup", true) ||
                        mediaUrl.endsWith(".vob", true) ||
                        mediaUrl.endsWith(".iso", true) ||
                        mediaUrl.endsWith(".mp3", true) ||
                        mediaUrl.endsWith(".wav", true) ||
                        mediaUrl.endsWith(".flac", true)
                ) {
                    val fileName = mediaUrl.substringAfterLast('/')
                    val fileNameCleaned = URLDecoder.decode(fileName, "UTF-8").substringBeforeLast('.')
                    val quality = when {
                        fileName.contains("1080", true) -> Qualities.P1080.value
                        fileName.contains("720", true) -> Qualities.P720.value
                        fileName.contains("480", true) -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }

                    if (mediaUrl.isNotEmpty()) {
                        callback(
                            ExtractorLink(
                                this.name,
                                fileNameCleaned,
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
}