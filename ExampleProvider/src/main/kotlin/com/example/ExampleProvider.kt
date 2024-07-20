package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ExampleProvider : MainAPI() {
    override var mainUrl = "https://archive.org"
    override var name = "Archive.org"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        try {
            val responseText = app.get("$mainUrl/advancedsearch.php?q=mediatype:(movies)&fl[]=identifier,fl[]=title&rows=20&page=$page&output=json").text
            println("Response Text: $responseText") // Debugging line
            val featured = tryParseJson<SearchResult>(responseText)
            val homePageList = featured?.response?.docs?.map { it.toSearchResponse(this) } ?: emptyList()
            return newHomePageResponse(
                listOf(
                    HomePageList("Featured", homePageList, true)
                ),
                false
            )
        } catch (e: Exception) {
            logError(e)
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val responseText = app.get("$mainUrl/advancedsearch.php?q=${query.encodeUri()}&fl[]=identifier,fl[]=title&rows=20&output=json").text
            println("Response Text: $responseText") // Debugging line
            val res = tryParseJson<SearchResult>(responseText)
            return res?.response?.docs?.map { it.toSearchResponse(this) } ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val identifier = url.substringAfterLast("/")
            val responseText = app.get("$mainUrl/metadata/$identifier").text
            println("Response Text: $responseText") // Debugging line
            val res = tryParseJson<MetadataResult>(responseText)
            return res?.metadata?.toLoadResponse(this)
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    private data class SearchResult(
        val response: DocsResponse
    )

    private data class DocsResponse(
        val docs: List<SearchEntry>
    )

    private data class SearchEntry(
        val identifier: String
    ) {
        suspend fun toSearchResponse(provider: ExampleProvider): SearchResponse {
            val title = fetchTitle(provider, identifier) // Fetch the title based on the identifier
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/details/$identifier",
                TvType.Movie
            ) {
                this.posterUrl = "${provider.mainUrl}/services/img/$identifier"
            }
        }

        private suspend fun fetchTitle(provider: ExampleProvider, identifier: String): String {
            return try {
                val responseText = app.get("${provider.mainUrl}/metadata/$identifier").text
                val metadataResult = tryParseJson<MetadataResult>(responseText)
                metadataResult?.metadata?.title ?: identifier
            } catch (e: Exception) {
                logError(e)
                identifier
            }
        }
    }

    private data class MetadataResult(
        val metadata: VideoEntry
    )

    private data class VideoEntry(
        val title: String,
        val description: String,
        val identifier: String,
        val creator: String?
    ) {
        suspend fun toLoadResponse(provider: ExampleProvider): LoadResponse {
            return provider.newMovieLoadResponse(
                title,
                "${provider.mainUrl}/details/$identifier",
                TvType.Movie,
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
        fun String.encodeUri() = URLEncoder.encode(this, "utf8")
    }

    class ExampleExtractor : ExtractorApi() {
        override val mainUrl = "https://archive.org"
        override val requiresReferer = false
        override val name = "Archive.org"

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
                val videoUrl = it.attr("href")
                val fileName = videoUrl.substringAfterLast('/')
                val quality = when {
                    fileName.contains("1080", true) -> Qualities.P1080
                    fileName.contains("720", true) -> Qualities.P720
                    fileName.contains("480", true) -> Qualities.P480
                    else -> Qualities.Unknown
                }

                if (videoUrl.isNotEmpty()) {
                    callback(
                        ExtractorLink(
                            this.name,
                            this.name,
                            mainUrl + videoUrl,
                            "",
                            quality
                        )
                    )
                }
            }
        }
    }
}
