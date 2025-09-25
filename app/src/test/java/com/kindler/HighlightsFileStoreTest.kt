package com.kindler

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsFileStoreTest {

    @Test
    fun `does not write file before flush threshold is reached`() {
        val tempDir = Files.createTempDirectory("highlight-store-test").toFile()
        try {
            val outputFile = File(tempDir, "highlights.json")
            val store = HighlightsFileStore(outputFile, flushThreshold = 2)

            store.addBookHighlights(
                BookEntry("ASIN-1", "Book One", "Author One", "2023-01-01"),
                listOf(HighlightEntry("Highlight 1", "Note 1"))
            )
            assertFalse(outputFile.exists())

            store.addBookHighlights(
                BookEntry("ASIN-2", "Book Two", "Author Two", "2023-02-02"),
                listOf(HighlightEntry("Highlight 2", "Note 2"))
            )
            assertTrue(outputFile.exists())

            val lines = outputFile.readLines()
            assertEquals(2, lines.size)

            val firstBook = JSONObject(lines[0])
            assertEquals("ASIN-1", firstBook.getString("asin"))
            assertEquals("Book One", firstBook.getString("title"))
            assertEquals("Author One", firstBook.getString("author"))
            assertEquals("2023-01-01", firstBook.getString("lastAccessedDate"))
            val firstHighlights = firstBook.getJSONArray("highlights")
            assertEquals(1, firstHighlights.length())
            val highlight = firstHighlights.getJSONObject(0)
            assertEquals("Highlight 1", highlight.getString("highlight"))
            assertEquals("Note 1", highlight.getString("note"))

            val secondBook = JSONObject(lines[1])
            assertEquals("ASIN-2", secondBook.getString("asin"))
            assertEquals("Book Two", secondBook.getString("title"))
            assertEquals("Author Two", secondBook.getString("author"))
            assertEquals("2023-02-02", secondBook.getString("lastAccessedDate"))
            val secondHighlights = secondBook.getJSONArray("highlights")
            assertEquals(1, secondHighlights.length())
            val secondHighlight = secondHighlights.getJSONObject(0)
            assertEquals("Highlight 2", secondHighlight.getString("highlight"))
            assertEquals("Note 2", secondHighlight.getString("note"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `flush appends remaining books when below threshold`() {
        val tempDir = Files.createTempDirectory("highlight-store-test").toFile()
        try {
            val outputFile = File(tempDir, "highlights.json")
            val store = HighlightsFileStore(outputFile, flushThreshold = 3)

            store.addBookHighlights(
                BookEntry("ASIN-3", "Book Three", "Author Three", "2023-03-03"),
                emptyList()
            )
            assertFalse(outputFile.exists())

            store.flush()
            assertTrue(outputFile.exists())

            var lines = outputFile.readLines()
            assertEquals(1, lines.size)

            val firstFlushBook = JSONObject(lines[0])
            assertEquals("ASIN-3", firstFlushBook.getString("asin"))
            assertEquals("Author Three", firstFlushBook.getString("author"))
            assertEquals("2023-03-03", firstFlushBook.getString("lastAccessedDate"))
            assertEquals(0, firstFlushBook.getJSONArray("highlights").length())

            store.addBookHighlights(
                BookEntry("ASIN-6", "Book Six", "Author Six", "2023-06-06"),
                listOf(HighlightEntry("Highlight 6", "Note 6"))
            )
            store.flush()

            lines = outputFile.readLines()
            assertEquals(2, lines.size)
            assertEquals(firstFlushBook.toString(), JSONObject(lines[0]).toString())
            val appendedBook = JSONObject(lines[1])
            assertEquals("ASIN-6", appendedBook.getString("asin"))
            assertEquals("Author Six", appendedBook.getString("author"))
            assertEquals("2023-06-06", appendedBook.getString("lastAccessedDate"))
            val appendedHighlights = appendedBook.getJSONArray("highlights")
            assertEquals(1, appendedHighlights.length())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `reset clears stored books and deletes existing file`() {
        val tempDir = Files.createTempDirectory("highlight-store-test").toFile()
        try {
            val outputFile = File(tempDir, "highlights.json")
            val store = HighlightsFileStore(outputFile, flushThreshold = 1)

            store.addBookHighlights(
                BookEntry("ASIN-4", "Book Four", "Author Four", "2023-04-04"),
                listOf(HighlightEntry("Highlight", "Note"))
            )
            assertTrue(outputFile.exists())

            store.reset()
            assertFalse(outputFile.exists())

            store.addBookHighlights(
                BookEntry("ASIN-5", "Book Five", "Author Five", "2023-05-05"),
                listOf(HighlightEntry("Another", "Note"))
            )
            store.flush()

            val lines = outputFile.readLines()
            assertEquals(1, lines.size)
            val firstBook = JSONObject(lines[0])
            assertEquals("ASIN-5", firstBook.getString("asin"))
            assertEquals("Author Five", firstBook.getString("author"))
            assertEquals("2023-05-05", firstBook.getString("lastAccessedDate"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `flush does not create file when no highlights are present`() {
        val tempDir = Files.createTempDirectory("highlight-store-test").toFile()
        try {
            val outputFile = File(tempDir, "highlights.json")
            val store = HighlightsFileStore(outputFile)

            store.flush()

            assertFalse(outputFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
