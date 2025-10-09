package com.kindler

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightsCsvExporterTest {

    @Test
    fun exportToWriter_includesHeaderAndAllHighlights() {
        val bookWithSingleHighlight = BookEntry(
            asin = "ASIN-1",
            title = "Book One",
            author = "Author A",
            lastAccessedDate = "2024-01-01",
            highlights = listOf(
                HighlightEntry(
                    highlight = "First highlight",
                    note = ""
                )
            )
        )
        val bookWithMultipleHighlights = BookEntry(
            asin = "ASIN-2",
            title = "Book Two",
            author = "Author B",
            lastAccessedDate = "2024-01-02",
            highlights = listOf(
                HighlightEntry(highlight = "Highlight one", note = "Note one"),
                HighlightEntry(highlight = "Highlight two", note = "Note two")
            )
        )

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
            books = listOf(bookWithSingleHighlight, bookWithMultipleHighlights),
            hasMore = false
        )

        val exporter = HighlightsCsvExporter(highlightsStore)
        val writer = StringWriter()

        exporter.exportToWriter(writer)

        val expectedOutput = buildString {
            appendLine("\"Asin\",\"Title\",\"Author\",\"Last accessed\",\"Highlight\",\"Note\"")
            appendLine("\"ASIN-1\",\"Book One\",\"Author A\",\"2024-01-01\",\"First highlight\",\"\"")
            appendLine("\"ASIN-2\",\"Book Two\",\"Author B\",\"2024-01-02\",\"Highlight one\",\"Note one\"")
            appendLine("\"ASIN-2\",\"Book Two\",\"Author B\",\"2024-01-02\",\"Highlight two\",\"Note two\"")
        }

        assertEquals(expectedOutput, writer.toString())

        verifySequence {
            highlightsStore.loadBooks(HighlightsFileStore.BOOKS_PER_PAGE, true)
        }
        confirmVerified(highlightsStore)
    }

    @Test
    fun exportToWriter_skipsBooksWithoutHighlights() {
        val emptyBook = BookEntry(
            asin = "ASIN-EMPTY",
            title = "Empty",
            author = "Nobody",
            lastAccessedDate = "2024-01-03",
            highlights = emptyList()
        )
        val bookWithHighlight = BookEntry(
            asin = "ASIN-REAL",
            title = "Real",
            author = "Somebody",
            lastAccessedDate = "2024-01-04",
            highlights = listOf(HighlightEntry("Interesting", ""))
        )

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
            books = listOf(emptyBook, bookWithHighlight),
            hasMore = false
        )

        val exporter = HighlightsCsvExporter(highlightsStore)
        val writer = StringWriter()

        exporter.exportToWriter(writer)

        val expectedOutput = buildString {
            appendLine("\"Asin\",\"Title\",\"Author\",\"Last accessed\",\"Highlight\",\"Note\"")
            appendLine("\"ASIN-REAL\",\"Real\",\"Somebody\",\"2024-01-04\",\"Interesting\",\"\"")
        }

        assertEquals(expectedOutput, writer.toString())

        verifySequence {
            highlightsStore.loadBooks(HighlightsFileStore.BOOKS_PER_PAGE, true)
        }
        confirmVerified(highlightsStore)
    }

    @Test
    fun hasAnyHighlights_returnsTrueWhenHighlightAppearsInLaterPage() {
        val firstPage = HighlightsFileStore.LoadResult(
            books = listOf(
                BookEntry("ASIN-1", "Title 1", "Author", "2024-01-01", emptyList())
            ),
            hasMore = true
        )
        val secondPage = HighlightsFileStore.LoadResult(
            books = listOf(
                BookEntry(
                    "ASIN-2",
                    "Title 2",
                    "Author",
                    "2024-01-02",
                    listOf(HighlightEntry("Highlight", ""))
                )
            ),
            hasMore = false
        )

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returnsMany listOf(firstPage, secondPage)

        val exporter = HighlightsCsvExporter(highlightsStore)

        assertTrue(exporter.hasAnyHighlights())

        verifySequence {
            highlightsStore.loadBooks(HighlightsFileStore.BOOKS_PER_PAGE, true)
            highlightsStore.loadBooks(HighlightsFileStore.BOOKS_PER_PAGE, false)
        }
        confirmVerified(highlightsStore)
    }

    @Test
    fun hasAnyHighlights_returnsFalseWhenNoHighlightsFound() {
        val firstPage = HighlightsFileStore.LoadResult(
            books = listOf(
                BookEntry("ASIN-1", "Title", "Author", "2024-01-01", emptyList())
            ),
            hasMore = true
        )
        val secondPage = HighlightsFileStore.LoadResult(
            books = emptyList(),
            hasMore = false
        )

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returnsMany listOf(firstPage, secondPage)

        val exporter = HighlightsCsvExporter(highlightsStore)

        assertFalse(exporter.hasAnyHighlights())

        verifySequence {
            highlightsStore.loadBooks(HighlightsFileStore.BOOKS_PER_PAGE, true)
            highlightsStore.loadBooks(HighlightsFileStore.BOOKS_PER_PAGE, false)
        }
        confirmVerified(highlightsStore)
    }

    @Test
    fun buildCsvRow_escapesQuotesWithinFields() {
        val row = buildCsvRow(
            asin = "ASIN-3",
            title = "A \"Quoted\" Title",
            author = "Author, The Great",
            lastAccessed = "2024-01-05",
            highlight = "He said \"Hello\"",
            note = "Line1\nLine2"
        )

        assertEquals(
            "\"ASIN-3\",\"A \"\"Quoted\"\" Title\",\"Author, The Great\",\"2024-01-05\",\"He said \"\"Hello\"\"\",\"Line1\nLine2\"",
            row
        )
    }
}
