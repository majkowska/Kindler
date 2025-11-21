package com.kindler

import java.io.IOException
import java.io.Writer
import java.text.SimpleDateFormat
import java.text.ParseException
import java.util.Date
import java.util.Locale

class HighlightsCsvExporter(
    private val dataSource: HighlightsFileStore,
    private val pageSize: Int = HighlightsFileStore.BOOKS_PER_PAGE
) {

    fun buildDefaultFileName(currentTimeMillis: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = formatter.format(Date(currentTimeMillis))
        return "Kindler Export $timestamp.csv"
    }

    @Throws(IOException::class)
    fun hasAnyHighlights(): Boolean {
        var fromStart = true

        while (true) {
            val result = dataSource.loadBooks(pageSize, fromStart)
            fromStart = false

            if (result.books.any { it.highlights.isNotEmpty() }) {
                return true
            }

            if (!result.hasMore) {
                return false
            }
        }
    }

    @Throws(IOException::class)
    fun exportToWriter(writer: Writer) {
        writer.appendLine(
            buildCsvRow(
                asin = "Asin",
                title = "Title",
                author = "Author",
                lastAccessed = "Last accessed",
                highlight = "Highlight",
                note = "Note"
            )
        )

        var fromStart = true

        while (true) {
            val result = dataSource.loadBooks(pageSize, fromStart)
            fromStart = false

            if (result.books.isEmpty() && !result.hasMore) {
                break
            }

            result.books.forEach { book ->
                book.highlights.forEach { highlightEntry ->
                    writer.appendLine(
                        buildCsvRow(
                            asin = book.asin,
                            title = book.title,
                            author = book.author,
                            lastAccessed = formatLastAccessedDate(book.lastAccessedDate),
                            highlight = highlightEntry.highlight,
                            note = highlightEntry.note
                        )
                    )
                }
            }

            if (!result.hasMore) {
                break
            }
        }
    }
}

internal fun formatLastAccessedDate(originalDate: String): String {
    val inputFormat = SimpleDateFormat("EEEE MMMM d, yyyy", Locale.US).apply {
        isLenient = false
    }
    val outputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    return try {
        val parsedDate = inputFormat.parse(originalDate)
        if (parsedDate != null) {
            outputFormat.format(parsedDate)
        } else {
            originalDate
        }
    } catch (_: ParseException) {
        originalDate
    }
}

internal fun buildCsvRow(
    asin: String,
    title: String,
    author: String,
    lastAccessed: String,
    highlight: String,
    note: String
): String {
    return listOf(asin, title, author, lastAccessed, highlight, note)
        .joinToString(separator = ",") { escapeCsvField(it) }
}

internal fun escapeCsvField(value: String): String {
    val escapedValue = value.replace("\"", "\"\"")
    return "\"$escapedValue\""
}
