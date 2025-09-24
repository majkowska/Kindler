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

    private val storedBooks = mutableListOf<BookHighlights>()

    @Throws(IOException::class)
    fun reset() {
        storedBooks.clear()
        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException("Failed to delete existing highlights file: ${outputFile.absolutePath}")
        }
    }

    @Throws(IOException::class)
    fun addBookHighlights(
        asin: String,
        title: String,
        highlights: List<HighlightEntry>
    ) {
        storedBooks.add(BookHighlights(asin, title, highlights))
        if (flushThreshold > 0 && storedBooks.size % flushThreshold == 0) {
            writeToFile(storedBooks)
        }
    }

    @Throws(IOException::class)
    fun flush() {
        if (storedBooks.isEmpty()) {
            return
        }
        writeToFile(storedBooks)
    }

    @Throws(IOException::class)
    private fun writeToFile(books: List<BookHighlights>) {
        val snapshot = books.toList()
        val payload = JSONObject().apply {
            put("bookCount", snapshot.size)
            put("books", JSONArray().apply {
                snapshot.forEach { book ->
                    put(JSONObject().apply {
                        put("asin", book.asin)
                        put("title", book.title)
                        put("highlights", JSONArray().apply {
                            book.highlights.forEach { highlight ->
                                put(JSONObject().apply {
                                    put("highlight", highlight.highlight)
                                    put("note", highlight.note)
                                })
                            }
                        })
                    })
                }
            })
        }
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create directory for highlights file: ${parent.absolutePath}")
            }
        }
        outputFile.outputStream().buffered().use { stream ->
            stream.write(payload.toString(2).toByteArray(StandardCharsets.UTF_8))
        }
    }

    private data class BookHighlights(
        val asin: String,
        val title: String,
        val highlights: List<HighlightEntry>
    )

    companion object {
        const val DEFAULT_FLUSH_THRESHOLD = 100
    }
}
