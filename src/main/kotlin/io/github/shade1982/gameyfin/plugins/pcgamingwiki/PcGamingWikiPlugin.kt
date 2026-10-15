package io.github.shade1982.gameyfin.plugins.pcgamingwiki

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gameyfin.pluginapi.core.wrapper.GameyfinPlugin
import org.gameyfin.pluginapi.gamemetadata.GameMetadata
import org.gameyfin.pluginapi.gamemetadata.GameMetadataProvider
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.pf4j.Extension
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory

class PcGamingWikiPlugin(wrapper: PluginWrapper) : GameyfinPlugin(wrapper) {
    @Suppress("Unused")
    @Extension(ordinal = 3)
    class PcGamingWikiMetadataProvider : GameMetadataProvider {
        private val log = LoggerFactory.getLogger(javaClass)
        private val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
        private val metadataCache = BoundedCache<String, GameMetadata>(METADATA_CACHE_SIZE)

        override val supportedPlatforms: Set<Platform> = setOf(
            Platform.PC_MICROSOFT_WINDOWS,
            Platform.LINUX,
            Platform.MAC
        )

        override fun fetchByTitle(
            gameTitle: String,
            platformFilter: Set<Platform>,
            maxResults: Int
        ): List<GameMetadata> {
            val title = gameTitle.removeSuffix("_base").removeSuffix("_game")
                .replace('_', ' ').trim()
            if (title.isBlank() || maxResults <= 0) return emptyList()

            return try {
                val results = runBlocking { search(title, maxResults) }
                val rankedResults = rankSearchResults(title, results, maxResults)
                if (rankedResults.isEmpty()) {
                    log.debug("PCGamingWiki search returned no matches for '{}' ({} API results)", title, results.size)
                    return emptyList()
                }
                val metadataResults = rankedResults.mapNotNull { result ->
                    val page = WikiPage(result.pageid, result.title)
                    val wikitext = runBlocking { fetchWikitext(page.pageid) }
                    val metadata = toMetadata(page, wikitext)
                    val metadataPlatforms = metadata.platforms.orEmpty()
                    val matchesPlatform = platformFilter.isEmpty() || metadataPlatforms.isEmpty() ||
                        metadataPlatforms.intersect(platformFilter).isNotEmpty()
                    if (matchesPlatform) {
                        metadataCache[metadata.originalId] = metadata
                        metadata
                    } else null
                }
                if (metadataResults.isEmpty() && platformFilter.isNotEmpty()) {
                    log.debug("PCGamingWiki returned matches for '{}' but none matched platforms {}", title, platformFilter)
                }
                metadataResults
            } catch (e: Exception) {
                log.warn("Failed to search PCGamingWiki for '$title': ${e.message}")
                emptyList()
            }
        }

        override fun fetchById(id: String): GameMetadata? {
            metadataCache[id]?.let { return it }
            val pageId = id.toIntOrNull() ?: return null
            return try {
                val page = runBlocking { fetchPage(pageId) } ?: return null
                val wikitext = runBlocking { fetchWikitext(page.pageid) }
                toMetadata(page, wikitext)
                    .also { metadataCache[id] = it }
            } catch (e: Exception) {
                log.warn("Failed to fetch PCGamingWiki page $id: ${e.message}")
                null
            }
        }

        private suspend fun search(title: String, maxResults: Int): List<SearchResult> {
            val exactMatch = runCatching { searchExactTitle(title) }
                .onFailure { log.debug("Exact PCGamingWiki lookup failed for '{}': {}", title, it.message) }
                .getOrNull()
            if (maxResults == 1 && exactMatch != null) return listOf(exactMatch)

            val searchResults = SearchPagination.collect(
                maxPages = MAX_SEARCH_PAGES,
                fetchPage = { offset -> searchPage(title, offset) },
                shouldStop = { results ->
                    val candidates = buildList {
                        exactMatch?.let(::add)
                        results.forEach { result -> if (none { it.pageid == result.pageid }) add(result) }
                    }
                    rankSearchResults(title, candidates, maxResults).size >= maxResults
                }
            )
            return buildList {
                exactMatch?.let(::add)
                searchResults.forEach { result ->
                    if (none { it.pageid == result.pageid }) add(result)
                }
            }
        }

        private suspend fun searchExactTitle(title: String): SearchResult? {
            val response = client.get(API_URL) {
                header("User-Agent", USER_AGENT)
                parameter("action", "query")
                parameter("titles", title)
                parameter("redirects", "1")
                parameter("format", "json")
                parameter("formatversion", "2")
            }
            if (response.status.value !in 200..299) {
                log.warn("PCGamingWiki exact-title lookup returned HTTP {} for '{}'", response.status.value, title)
                return null
            }
            val page = response.body<ExactTitleResponse>().query?.pages
                ?.firstOrNull { !it.missing && it.pageid != null }
                ?: return null
            return SearchResult(page.pageid!!, page.title)
        }

        private suspend fun searchPage(title: String, offset: Int): SearchBatch<SearchResult> {
            val response = client.get(API_URL) {
                header("User-Agent", USER_AGENT)
                parameter("action", "query")
                parameter("list", "search")
                parameter("srsearch", title)
                parameter("srnamespace", "0")
                parameter("srlimit", SEARCH_PAGE_SIZE)
                parameter("sroffset", offset)
                parameter("format", "json")
                parameter("formatversion", "2")
            }
            if (response.status.value !in 200..299) {
                log.warn("PCGamingWiki search returned HTTP {} for '{}'", response.status.value, title)
                return SearchBatch(emptyList(), null)
            }
            val searchResponse = response.body<SearchResponse>()
            return SearchBatch(
                results = searchResponse.query?.search.orEmpty(),
                nextOffset = searchResponse.continuation?.sroffset
            )
        }

        private suspend fun fetchPage(pageId: Int): WikiPage? {
            val response = client.get(API_URL) {
                header("User-Agent", USER_AGENT)
                parameter("action", "query")
                parameter("prop", "info")
                parameter("pageids", pageId)
                parameter("format", "json")
                parameter("formatversion", "2")
            }
            if (response.status.value !in 200..299) {
                log.warn("PCGamingWiki page lookup returned HTTP {} for page {}", response.status.value, pageId)
                return null
            }
            return response.body<PageResponse>().query?.pages?.firstOrNull()
        }

        private suspend fun fetchWikitext(pageId: Int): String? {
            val response = client.get(API_URL) {
                header("User-Agent", USER_AGENT)
                parameter("action", "parse")
                parameter("pageid", pageId)
                parameter("prop", "wikitext")
                parameter("format", "json")
            }
            if (response.status.value !in 200..299) {
                log.warn("PCGamingWiki wikitext lookup returned HTTP {} for page {}", response.status.value, pageId)
                return null
            }
            return response.body<ParseResponse>().parse.wikitext.content
        }

        private fun toMetadata(page: WikiPage, wikitext: String?): GameMetadata {
            val wiki = wikitext.orEmpty()
            val originalId = page.pageid.toString()

            return GameMetadata(
                originalId = originalId,
                title = page.title,
                description = WikiTextParser.description(wiki),
                // images.pcgamingwiki.com serves a Cloudflare challenge to server-side downloads.
                coverUrls = null,
                release = WikiTextParser.releaseDate(wiki),
                developedBy = WikiTextParser.developers(wiki).takeIf { it.isNotEmpty() }?.toSet(),
                publishedBy = WikiTextParser.publishers(wiki).takeIf { it.isNotEmpty() }?.toSet(),
                platforms = WikiTextParser.platforms(wiki).takeIf { it.isNotEmpty() },
                genres = WikiTextParser.genres(wiki),
                themes = WikiTextParser.themes(wiki),
                features = emptySet(),
                keywords = null,
                headerUrls = null,
                screenshotUrls = null,
                videoUrls = null,
                userRating = null
            )
        }

        companion object {
            private const val API_URL = "https://www.pcgamingwiki.com/w/api.php"
            private const val USER_AGENT = "Gameyfin-PCGamingWiki-Plugin/0.1 (metadata provider)"
            private const val SEARCH_PAGE_SIZE = 50
            private const val MAX_SEARCH_PAGES = 5
            private const val METADATA_CACHE_SIZE = 256
        }
    }
}

@Serializable
private data class SearchResponse(
    val query: SearchQuery? = null,
    @SerialName("continue") val continuation: SearchContinuation? = null
)

@Serializable
private data class SearchContinuation(val sroffset: Int? = null)

@Serializable
private data class SearchQuery(val search: List<SearchResult> = emptyList())

@Serializable
private data class ExactTitleResponse(val query: ExactTitleQuery? = null)

@Serializable
private data class ExactTitleQuery(val pages: List<ExactTitlePage> = emptyList())

@Serializable
private data class ExactTitlePage(val pageid: Int? = null, val title: String, val missing: Boolean = false)

@Serializable
private data class PageResponse(val query: PageQuery? = null)

@Serializable
private data class PageQuery(val pages: List<WikiPage> = emptyList())

@Serializable
private data class WikiPage(
    val pageid: Int,
    val title: String
)

@Serializable
private data class ParseResponse(val parse: ParseResult)

@Serializable
private data class ParseResult(val wikitext: Wikitext)

@Serializable
private data class Wikitext(@SerialName("*") val content: String)