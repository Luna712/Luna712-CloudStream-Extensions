package com.luna712

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
            res?.metadata?.toLoadResponse(this)
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
            val type = when (mediatype) {
                "audio" -> TvType.Music
                else -> TvType.Movie
            }
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
        val metadata: MediaEntry
    )

    private data class MediaEntry(
        val identifier: String,
        val mediatype: String,
        val title: String?,
        val description: String?,
        val creator: String?
    ) {
        suspend fun toLoadResponse(provider: InternetArchiveProvider): LoadResponse {
            val type = when (mediatype) {
                "audio" -> TvType.Music
                else -> TvType.Movie
            }
            return provider.newMovieLoadResponse(
                title ?: identifier,
                "${provider.mainUrl}/details/$identifier",
                type,
                identifier
            ) {
                plot = description
                posterUrl = "${provider.mainUrl}/services/img/$identifier"
                actors = listOfNotNull(
                    creator?.let { ActorData(Actor(it, ""), roleString = "Creator") }
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(
            "https://archive.org/details/$data",
            subtitleCallback,
            callback
        )
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

            document.select("a[href*=\"/download/\"]").forEach {
                val mediaUrl = it.attr("href")
                if (mediaUrl.endsWith(".mp4", true) || mediaUrl.endsWith(".mkv", true) || mediaUrl.endsWith(".avi", true) || mediaUrl.endsWith(".mp3", true) || mediaUrl.endsWith(".flac", true)) {
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
                                mainUrl + mediaUrl,
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