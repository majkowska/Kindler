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

            store.addBookHighlights("ASIN-1", "Book One", listOf(HighlightEntry("Highlight 1", "Note 1")))
            assertFalse(outputFile.exists())

            store.addBookHighlights("ASIN-2", "Book Two", listOf(HighlightEntry("Highlight 2", "Note 2")))
            assertTrue(outputFile.exists())

            val json = JSONObject(outputFile.readText())
            assertEquals(2, json.getInt("bookCount"))
            val books = json.getJSONArray("books")
            assertEquals(2, books.length())

            val firstBook = books.getJSONObject(0)
            assertEquals("ASIN-1", firstBook.getString("asin"))
            assertEquals("Book One", firstBook.getString("title"))
            val firstHighlights = firstBook.getJSONArray("highlights")
            assertEquals(1, firstHighlights.length())
            val highlight = firstHighlights.getJSONObject(0)
            assertEquals("Highlight 1", highlight.getString("highlight"))
            assertEquals("Note 1", highlight.getString("note"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `flush writes remaining books even when below threshold`() {
        val tempDir = Files.createTempDirectory("highlight-store-test").toFile()
        try {
            val outputFile = File(tempDir, "highlights.json")
            val store = HighlightsFileStore(outputFile, flushThreshold = 3)

            store.addBookHighlights("ASIN-3", "Book Three", emptyList())
            assertFalse(outputFile.exists())

            store.flush()
            assertTrue(outputFile.exists())

            val json = JSONObject(outputFile.readText())
            assertEquals(1, json.getInt("bookCount"))
            val books = json.getJSONArray("books")
            assertEquals(1, books.length())
            assertEquals(0, books.getJSONObject(0).getJSONArray("highlights").length())
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

            store.addBookHighlights("ASIN-4", "Book Four", listOf(HighlightEntry("Highlight", "Note")))
            assertTrue(outputFile.exists())

            store.reset()
            assertFalse(outputFile.exists())

            store.addBookHighlights("ASIN-5", "Book Five", listOf(HighlightEntry("Another", "Note")))
            store.flush()

            val json = JSONObject(outputFile.readText())
            assertEquals(1, json.getInt("bookCount"))
            val firstBook = json.getJSONArray("books").getJSONObject(0)
            assertEquals("ASIN-5", firstBook.getString("asin"))
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
