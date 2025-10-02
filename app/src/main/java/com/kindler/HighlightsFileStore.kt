package com.kindler

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

class HighlightsFileStore(
    private val outputFile: File,
    private val flushThreshold: Int = DEFAULT_FLUSH_THRESHOLD
) {

    private val storedBooks = mutableListOf<BookEntry>()

    @Throws(IOException::class)
    fun loadBooks(offset: Int = 0, limit: Int = BOOKS_PER_PAGE): List<BookEntry> {
        if (!outputFile.exists()) {
            return emptyList()
        }

        val allBooks = mutableListOf<BookEntry>()
        outputFile.readLines(StandardCharsets.UTF_8).forEach { line ->
            if (line.isNotBlank()) {
                try {
                    val bookJson = JSONObject(line)
                    val highlightsArray = bookJson.getJSONArray("highlights")
                    val highlights = mutableListOf<HighlightEntry>()

                    for (i in 0 until highlightsArray.length()) {
                        val highlightJson = highlightsArray.getJSONObject(i)
                        highlights.add(
                            HighlightEntry(
                                highlight = highlightJson.getString("highlight"),
                                note = highlightJson.getString("note")
                            )
                        )
                    }

                    allBooks.add(
                        BookEntry(
                            asin = bookJson.getString("asin"),
                            title = bookJson.getString("title"),
                            author = bookJson.getString("author"),
                            lastAccessedDate = bookJson.getString("lastAccessedDate"),
                            highlights = highlights
                        )
                    )
                } catch (e: Exception) {
                    throw IOException("Failed to parse book data from file", e)
                }
            }
        }

        val startIndex = offset.coerceAtLeast(0)
        val endIndex = (offset + limit).coerceAtMost(allBooks.size)

        return if (startIndex < allBooks.size) {
            allBooks.subList(startIndex, endIndex)
        } else {
            emptyList()
        }
    }

    fun getTotalBookCount(): Int {
        if (!outputFile.exists()) {
            return 0
        }
        return outputFile.readLines(StandardCharsets.UTF_8).count { it.isNotBlank() }
    }

    @Throws(IOException::class)
    fun reset() {
        storedBooks.clear()
        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException("Failed to delete existing highlights file: ${outputFile.absolutePath}")
        }
    }

    @Throws(IOException::class)
    fun addBookHighlights(
        book: BookEntry,
        highlights: List<HighlightEntry>
    ) {
        val bookWithHighlights = book.copy(highlights = highlights)
        storedBooks.add(bookWithHighlights)
        if (flushThreshold > 0 && storedBooks.size % flushThreshold == 0) {
            writeToFile(storedBooks)
            storedBooks.clear()
        }
    }

    @Throws(IOException::class)
    fun flush() {
        if (storedBooks.isEmpty()) {
            return
        }
        writeToFile(storedBooks)
        storedBooks.clear()
    }

    @Throws(IOException::class)
    private fun writeToFile(books: List<BookEntry>) {
        val snapshot = books.toList()
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create directory for highlights file: ${parent.absolutePath}")
            }
        }
        snapshot.forEach { book ->
            val bookJson = JSONObject().apply {
                put("asin", book.asin)
                put("title", book.title)
                put("author", book.author)
                put("lastAccessedDate", book.lastAccessedDate)
                put("highlights", JSONArray().apply {
                    book.highlights.forEach { highlight ->
                        put(JSONObject().apply {
                            put("highlight", highlight.highlight)
                            put("note", highlight.note)
                        })
                    }
                })
            }
            outputFile.appendText(bookJson.toString() + "\n", StandardCharsets.UTF_8)
        }
    }

    companion object {
        const val DEFAULT_FLUSH_THRESHOLD = 100
        const val BOOKS_PER_PAGE = 100
    }
}
