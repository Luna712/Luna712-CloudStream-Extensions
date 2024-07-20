package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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
        val featured = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/advancedsearch.php?q=mediatype:(movies)&fl[]=identifier,fl[]=title&rows=20&page=$page&output=json").text
        )
        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Featured",
                    featured?.map { it.toSearchResponse(this) } ?: emptyList(),
                    true
                )
            ),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = tryParseJson<List<SearchEntry>>(
            app.get("$mainUrl/advancedsearch.php?q=${query.encodeUri()}&fl[]=identifier,fl[]=title&rows=20&output=json").text
        )
        return res?.map { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val identifier = url.substringAfterLast("/")
        val res = tryParseJson<VideoEntry>(
            app.get("$mainUrl/metadata/$identifier").text
        )
        return res?.toLoadResponse(this)
    }

    private data class SearchEntry(
        val title: String,
        val identifier: String
    ) {
        fun toSearchResponse(provider: ExampleProvider): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/details/$identifier",
                TvType.Movie
            ) {
                this.posterUrl = "${provider.mainUrl}/services/img/$identifier"
            }
        }
    }

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

        document.select("video source").forEach {
            val videoUrl = it.attr("src")
            val height = it.attr("height").toIntOrNull() ?: 0

            if (videoUrl.isNotEmpty() && height > 0) {
                callback(
                    ExtractorLink(
                        this.name,
                        this.name,
                        videoUrl,
                        "",
                        height
                    )
                )
            }
        }
    }
}
}
