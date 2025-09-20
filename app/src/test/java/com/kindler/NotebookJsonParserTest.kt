package com.kindler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookJsonParserTest {

    @Test
    fun parseBookEntries_returnsExpectedBooks() {
        val json = """
            [
                {
                    "asin": "ASIN1",
                    "title": "Book One",
                    "author": "Author A",
                    "lastAccessedDate": "2023-01-01"
                },
                {
                    "asin": "ASIN2",
                    "title": "Book Two",
                    "author": "Author B",
                    "lastAccessedDate": "2023-02-02"
                }
            ]
        """.trimIndent()

        val result = NotebookJsonParser.parseBookEntries(json)

        assertTrue(result.isSuccess)
        val books = result.getOrThrow()
        assertEquals(2, books.size)
        assertEquals(BookEntry("ASIN1", "Book One", "Author A", "2023-01-01"), books[0])
        assertEquals(BookEntry("ASIN2", "Book Two", "Author B", "2023-02-02"), books[1])
    }

    @Test
    fun parseBookEntries_invalidJson_returnsFailure() {
        val result = NotebookJsonParser.parseBookEntries("not valid json")

        assertTrue(result.isFailure)
    }

    @Test
    fun parseHighlights_returnsExpectedEntries() {
        val json = """
            [
                {
                    "highlight": "Highlight text",
                    "note": "Note text"
                },
                {
                    "highlight": "Another highlight",
                    "note": ""
                }
            ]
        """.trimIndent()

        val result = NotebookJsonParser.parseHighlights(json)

        assertTrue(result.isSuccess)
        val highlights = result.getOrThrow()
        assertEquals(2, highlights.size)
        assertEquals(HighlightEntry("Highlight text", "Note text"), highlights[0])
        assertEquals(HighlightEntry("Another highlight", ""), highlights[1])
    }
}
