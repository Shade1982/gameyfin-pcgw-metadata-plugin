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
    fun `generic queries only match the exact title`() {
        val generic = listOf(
            SearchResult(1, "Age of Wonders II"),
            SearchResult(2, "Arena: An Age of Barbarians Story"),
            SearchResult(3, "Age of Empires II: The Age of Kings")
        )

        assertTrue(rankSearchResults("Age of", generic, maxResults = 5).isEmpty())
        assertTrue(rankSearchResults("The", listOf(SearchResult(4, "The Witcher 3: Wild Hunt")), maxResults = 5).isEmpty())
        assertEquals(
            listOf("Age of"),
            rankSearchResults("Age of", generic + SearchResult(5, "Age of"), maxResults = 5).map { it.title }
        )
    }

    @Test
    fun `single word query does not match titles containing it`() {
        val results = rankSearchResults(
            "Portal",
            listOf(SearchResult(1, "Portal Knights"), SearchResult(2, "Mind Portal")),
            maxResults = 5
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `multi word query matches edition and subtitle suffixes`() {
        val results = rankSearchResults(
            "Portal 2",
            listOf(SearchResult(1, "Mind Portal"), SearchResult(2, "Portal 2: Community Edition")),
            maxResults = 5
        )

        assertEquals(listOf("Portal 2: Community Edition"), results.map { it.title })
    }

    @Test
    fun `matching ignores case and punctuation and respects the result limit`() {
        val candidates = listOf(SearchResult(1, "Half-Life 2"), SearchResult(2, "Half Life 2"))

        assertEquals(2, rankSearchResults("half life 2", candidates, maxResults = 5).size)
        assertEquals(1, rankSearchResults("HALF-LIFE 2", candidates, maxResults = 1).size)
        assertTrue(rankSearchResults("Half-Life 2", candidates, maxResults = 0).isEmpty())
    }

    @Test
    fun `non latin titles are compared rather than normalized to empty`() {
        val results = rankSearchResults("ドラゴンクエスト", listOf(SearchResult(1, "ゲーム")), maxResults = 5)

        assertTrue(results.isEmpty())
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