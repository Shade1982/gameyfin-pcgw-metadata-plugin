package io.github.shade1982.gameyfin.plugins.pcgamingwiki

import kotlinx.coroutines.runBlocking
import org.gameyfin.pluginapi.gamemetadata.Genre
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcGamingWikiSupportTest {
    @Test
    fun `exact title is ranked ahead of fuzzy matches`() {
        val results = rankSearchResults(
            title = "Portal",
            results = listOf(
                SearchResult(1, "Portal Stories: VR"),
                SearchResult(2, "Portal"),
                SearchResult(3, "Portal 2")
            ),
            maxResults = 3
        )

        assertEquals("Portal", results.first().title)
    }

    @Test
    fun `ranking respects minimum score and result limit`() {
        val results = rankSearchResults(
            title = "Portal",
            results = listOf(
                SearchResult(1, "Portal 2"),
                SearchResult(2, "A completely unrelated game")
            ),
            maxResults = 1
        )

        assertEquals(1, results.size)
        assertEquals("Portal 2", results.single().title)
        assertTrue(rankSearchResults("Portal", results, maxResults = 0).isEmpty())
    }

    @Test
    fun `pagination advances offsets and stops once enough ranked results exist`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val results = SearchPagination.collect(
            maxPages = 5,
            fetchPage = { offset ->
                offsets += offset
                SearchBatch(
                    results = (offset until offset + 2).map { SearchResult(it, "Portal $it") },
                    nextOffset = offset + 2
                )
            },
            shouldStop = { it.size >= 4 }
        )

        assertEquals(listOf(0, 2), offsets)
        assertEquals(4, results.size)
    }

    @Test
    fun `pagination stops when API has no continuation`() = runBlocking {
        var requests = 0
        val results = SearchPagination.collect(
            maxPages = 5,
            fetchPage = {
                requests++
                SearchBatch(listOf(SearchResult(228, "Portal")), nextOffset = null)
            },
            shouldStop = { false }
        )

        assertEquals(1, requests)
        assertEquals("Portal", results.single().title)
    }

    @Test
    fun `wiki parser extracts infobox fields and handles unknown platforms`() {
        val wikitext = """
            {{Infobox game
            |cover = Portal cover.jpg
            |developers =
            {{Infobox game/row/developer|Valve Corporation}}
            |publishers =
            {{Infobox game/row/publisher|Valve Corporation}}
            |release dates =
            {{Infobox game/row/date|Windows|October 10, 2007}}
            {{Infobox game/row/date|Unlisted platform|2010}}
            |taxonomy =
            {{Infobox game/row/taxonomy/genres|Puzzle}}
            }}
        """.trimIndent()

        assertEquals("Portal cover.jpg", WikiTextParser.coverFilename(wikitext))
        assertEquals(listOf("Valve Corporation"), WikiTextParser.developers(wikitext))
        assertEquals(listOf("Valve Corporation"), WikiTextParser.publishers(wikitext))
        assertEquals(setOf(Platform.PC_MICROSOFT_WINDOWS), WikiTextParser.platforms(wikitext))
        assertEquals(Instant.parse("2007-10-10T00:00:00Z"), WikiTextParser.releaseDate(wikitext))
        assertTrue(Genre.PUZZLE in WikiTextParser.genres(wikitext))
    }

    @Test
    fun `wiki parser removes markup and references from introduction`() {
        val wikitext = """
            {{Introduction
            |introduction = ''Portal'' is a [[puzzle|puzzle]] game.<ref>citation</ref>
            |release history = ignored
            |current state = ignored
            }}
        """.trimIndent()

        assertEquals("Portal is a puzzle game.", WikiTextParser.description(wikitext))
        assertNull(WikiTextParser.description("No introduction template"))
    }

    @Test
    fun `bounded cache evicts least recently used entry`() {
        val cache = BoundedCache<String, Int>(2)
        cache["first"] = 1
        cache["second"] = 2
        assertEquals(1, cache["first"])
        cache["third"] = 3

        assertNull(cache["second"])
        assertEquals(1, cache["first"])
        assertFalse(cache["third"] == null)
    }
}