package io.github.shade1982.gameyfin.plugins.pcgamingwiki

import org.gameyfin.pluginapi.gamemetadata.Genre
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.gameyfin.pluginapi.gamemetadata.Theme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@kotlinx.serialization.Serializable
internal data class SearchResult(val pageid: Int, val title: String)

internal data class SearchBatch<T>(val results: List<T>, val nextOffset: Int?)

internal object SearchPagination {
    suspend fun <T> collect(
        maxPages: Int,
        fetchPage: suspend (offset: Int) -> SearchBatch<T>,
        shouldStop: (List<T>) -> Boolean
    ): List<T> {
        require(maxPages > 0)
        val collected = mutableListOf<T>()
        var offset = 0

        repeat(maxPages) {
            val batch = fetchPage(offset)
            collected += batch.results
            if (shouldStop(collected)) return collected

            val nextOffset = batch.nextOffset ?: return collected
            if (nextOffset <= offset) return collected
            offset = nextOffset
        }

        return collected
    }
}

internal fun rankSearchResults(
    title: String,
    results: Iterable<SearchResult>,
    maxResults: Int,
    minimumScore: Int = 55
): List<SearchResult> {
    if (maxResults <= 0) return emptyList()
    return results
        .map { it to me.xdrop.fuzzywuzzy.FuzzySearch.weightedRatio(title.lowercase(), it.title.lowercase()) }
        .filter { it.second >= minimumScore }
        .sortedWith(compareByDescending<Pair<SearchResult, Int>> {
            it.first.title.equals(title, ignoreCase = true)
        }.thenByDescending { it.second })
        .take(maxResults)
        .map { it.first }
}

internal class BoundedCache<K, V>(private val maxSize: Int) {
    init {
        require(maxSize > 0)
    }

    private val entries = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        entries[key] = value
    }
}

internal object WikiTextParser {
    private val releaseRowRegex = Regex("(?is)\\{\\{Infobox game/row/date\\|([^|}\\n]+)\\|([^}\\n]+)")

    fun developers(wikitext: String): List<String> = rowValues(wikitext, "developer")

    fun publishers(wikitext: String): List<String> = rowValues(wikitext, "publisher")

    fun coverFilename(wikitext: String): String? = Regex("(?im)^\\|\\s*cover\\s*=\\s*([^\\n]+)")
        .find(wikitext)
        ?.groupValues?.get(1)
        ?.let(::cleanWikiText)
        ?.removePrefix("File:")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun platforms(wikitext: String): Set<Platform> = releaseRowRegex.findAll(wikitext)
        .mapNotNull { platformFrom(it.groupValues[1]) }
        .toSet()

    fun releaseDate(wikitext: String): Instant? = releaseRowRegex.findAll(wikitext)
        .mapNotNull { parseDate(cleanWikiText(it.groupValues[2])) }
        .firstOrNull()

    fun genres(wikitext: String): Set<Genre> = taxonomyValues(wikitext, "genres")
        .mapNotNull { enumValue<Genre>(it) }
        .toSet()

    fun themes(wikitext: String): Set<Theme> = taxonomyValues(wikitext, "themes")
        .mapNotNull { enumValue<Theme>(it) }
        .toSet()

    fun description(wikitext: String): String? {
        var description = Regex(
            "(?is)\\{\\{Introduction\\s*\\|\\s*introduction\\s*=(.*?)(?=\\n\\s*\\|\\s*(?:release history|current state)\\s*=)"
        ).find(wikitext)?.groupValues?.get(1) ?: return null
        val nestedTemplates = Regex("\\{\\{[^{}]*}}")
        while (true) {
            val withoutTemplates = nestedTemplates.replace(description, "")
            if (withoutTemplates == description) break
            description = withoutTemplates
        }
        return description
            .replace(Regex("(?is)<ref\\b[^>]*>.*?</ref>"), "")
            .replace(Regex("(?is)<ref\\b[^>]*/>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), " ")
            .replace(Regex("\\[\\[([^]|]+\\|)?([^]]+)]]"), "$2")
            .replace(Regex("\\[https?://[^\\s\\]]+\\s+([^]]+)]"), "$1")
            .replace(Regex("<[^>]+>"), "")
            .replace("'''", "")
            .replace("''", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun rowValues(wikitext: String, row: String): List<String> =
        Regex("(?is)\\{\\{Infobox game/row/$row\\|([^|}\\n]+)")
            .findAll(wikitext)
            .map { cleanWikiText(it.groupValues[1]) }
            .filter(String::isNotBlank)
            .distinct()
            .toList()

    private fun taxonomyValues(wikitext: String, taxonomy: String): List<String> =
        Regex("(?i)\\{\\{Infobox game/row/taxonomy/$taxonomy\\s*\\|([^}|\\n]+)")
            .findAll(wikitext)
            .flatMap { it.groupValues[1].split(',').asSequence() }
            .map(::cleanWikiText)
            .filter(String::isNotBlank)
            .toList()

    private fun platformFrom(value: String): Platform? = when (cleanWikiText(value).lowercase()) {
        "windows" -> Platform.PC_MICROSOFT_WINDOWS
        "linux" -> Platform.LINUX
        "os x", "macos", "mac os" -> Platform.MAC
        else -> null
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T? {
        val normalized = value.filter(Char::isLetterOrDigit)
        return enumValues<T>().firstOrNull {
            it.name.filter(Char::isLetterOrDigit).equals(normalized, ignoreCase = true)
        }
    }

    private fun parseDate(value: String): Instant? {
        val normalized = value.trim()
        if (normalized.matches(Regex("\\d{4}"))) {
            return runCatching {
                LocalDate.of(normalized.toInt(), 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
            }.getOrNull()
        }

        val formats = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)
        )
        return formats.firstNotNullOfOrNull { formatter ->
            runCatching {
                LocalDate.parse(normalized, formatter).atStartOfDay().toInstant(ZoneOffset.UTC)
            }.getOrNull()
        }
    }

    private fun cleanWikiText(value: String): String = value
        .replace(Regex("\\[\\[([^]|]+\\|)?([^]]+)]]"), "$2")
        .replace(Regex("<[^>]+>"), "")
        .replace("'''", "")
        .replace("''", "")
        .trim()
}