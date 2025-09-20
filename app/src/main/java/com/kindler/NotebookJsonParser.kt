package com.kindler

import org.json.JSONArray

object NotebookJsonParser {

    fun parseBookEntries(bookDataJson: String): Result<List<BookEntry>> = runCatching {
        val rawBooksArray = JSONArray(bookDataJson)
        (0 until rawBooksArray.length()).map { index ->
            val bookObject = rawBooksArray.getJSONObject(index)
            BookEntry(
                asin = bookObject.getString("asin"),
                title = bookObject.getString("title"),
                author = bookObject.getString("author"),
                lastAccessedDate = bookObject.getString("lastAccessedDate")
            )
        }
    }

    fun parseHighlights(highlightsJson: String): Result<List<HighlightEntry>> = runCatching {
        val highlightsArray = JSONArray(highlightsJson)
        (0 until highlightsArray.length()).map { index ->
            val highlightObject = highlightsArray.getJSONObject(index)
            HighlightEntry(
                highlight = highlightObject.getString("highlight"),
                note = highlightObject.getString("note")
            )
        }
    }
}
