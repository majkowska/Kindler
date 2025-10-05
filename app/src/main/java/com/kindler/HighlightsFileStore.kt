package com.kindler

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

class HighlightsFileStore(
    private val outputFile: File,
    private val flushThreshold: Int = DEFAULT_FLUSH_THRESHOLD
) {

    private val storedBooks = mutableListOf<BookEntry>()
    private var nextReadPosition: Long = 0L
    private var fileLastModifiedSnapshot: Long = -1L
    private var reachedEndOfFile: Boolean = false

    @Synchronized
    @Throws(IOException::class)
    fun loadBooks(limit: Int = BOOKS_PER_PAGE, fromStart: Boolean = false): LoadResult {
        if (limit <= 0) {
            return LoadResult(emptyList(), hasMore = false)
        }

        if (!outputFile.exists()) {
            resetReadingStateInternal()
            return LoadResult(emptyList(), hasMore = false)
        }

        val lastModified = outputFile.lastModified()
        if (fromStart || lastModified != fileLastModifiedSnapshot) {
            nextReadPosition = 0L
            reachedEndOfFile = false
        }

        val books = mutableListOf<BookEntry>()

        RandomAccessFile(outputFile, "r").use { file ->
            file.seek(nextReadPosition)

            while (books.size < limit) {
                val rawLine = readUtf8Line(file) ?: break
                nextReadPosition = file.filePointer

                if (rawLine.isBlank()) {
                    continue
                }

                try {
                    val bookJson = JSONObject(rawLine)
                    val highlightsArray = bookJson.getJSONArray("highlights")
                    val highlights = ArrayList<HighlightEntry>(highlightsArray.length())
                    for (i in 0 until highlightsArray.length()) {
                        val highlightJson = highlightsArray.getJSONObject(i)
                        highlights.add(
                            HighlightEntry(
                                highlight = highlightJson.getString("highlight"),
                                note = highlightJson.getString("note")
                            )
                        )
                    }

                    books.add(
                        BookEntry(
                            asin = bookJson.getString("asin"),
                            title = bookJson.getString("title"),
                            author = bookJson.getString("author"),
                            lastAccessedDate = bookJson.getString("lastAccessedDate"),
                            highlights = highlights
                        )
                    )
                } catch (e: Exception) {
                    resetReadingStateInternal()
                    throw IOException("Failed to parse book data from file", e)
                }
            }

            nextReadPosition = file.filePointer
            reachedEndOfFile = nextReadPosition >= file.length()
        }

        fileLastModifiedSnapshot = outputFile.lastModified()

        return LoadResult(books, hasMore = !reachedEndOfFile && books.isNotEmpty())
    }

    @Synchronized
    @Throws(IOException::class)
    fun reset() {
        storedBooks.clear()
        resetReadingStateInternal()
        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException("Failed to delete existing highlights file: ${outputFile.absolutePath}")
        }
    }

    @Synchronized
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

    @Synchronized
    @Throws(IOException::class)
    fun flush() {
        if (storedBooks.isEmpty()) {
            return
        }
        writeToFile(storedBooks)
        storedBooks.clear()
    }

    @Synchronized
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
        resetReadingStateInternal()
    }

    @Throws(IOException::class)
    private fun readUtf8Line(file: RandomAccessFile): String? {
        if (file.filePointer >= file.length()) {
            return null
        }

        val buffer = ByteArrayOutputStream()
        while (true) {
            val nextByte = file.read()
            if (nextByte == -1) {
                break
            }
            if (nextByte == '\n'.code) {
                break
            }
            buffer.write(nextByte)
        }
        return buffer.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun resetReadingStateInternal() {
        nextReadPosition = 0L
        reachedEndOfFile = false
        fileLastModifiedSnapshot = -1L
    }

    companion object {
        const val DEFAULT_FLUSH_THRESHOLD = 100
        const val BOOKS_PER_PAGE = 100
    }

    data class LoadResult(
        val books: List<BookEntry>,
        val hasMore: Boolean
    )
}
