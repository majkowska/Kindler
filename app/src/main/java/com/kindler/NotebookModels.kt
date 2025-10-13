package com.kindler

const val HIGHLIGHTS_FILE_NAME = "kindle_highlights.json"

data class BookEntry(
    val asin: String,
    val title: String,
    val author: String,
    val lastAccessedDate: String,
    val highlights: List<HighlightEntry> = emptyList()
)

data class HighlightEntry(
    val highlight: String,
    val note: String
)
