package com.kindler

class NotebookImportStateMachine {

    var state: ImportState = ImportState.INITIAL
        private set

    private val books: MutableList<BookEntry> = mutableListOf()
    private var index: Int = 0

    val currentBookIndex: Int
        get() = index

    val totalBooks: Int
        get() = books.size

    fun startImport() {
        state = ImportState.LOADING_BOOK_LIST
        index = 0
        books.clear()
    }

    fun onBooksParsed(parsedBooks: List<BookEntry>): BooksUpdateResult {
        if (state != ImportState.LOADING_BOOK_LIST) {
            return BooksUpdateResult.InvalidState(state)
        }

        books.clear()
        books.addAll(parsedBooks)
        index = 0

        return if (books.isEmpty()) {
            state = ImportState.FINISHED
            BooksUpdateResult.NoBooks
        } else {
            state = ImportState.LOADING_HIGHLIGHTS
            BooksUpdateResult.Ready(books.first())
        }
    }

    fun currentBook(): BookEntry? = books.getOrNull(index)

    fun advanceToNextBook(): HighlightProcessingResult {
        if (state != ImportState.LOADING_HIGHLIGHTS) {
            return HighlightProcessingResult.InvalidState(state)
        }

        if (books.isEmpty()) {
            state = ImportState.ERROR
            return HighlightProcessingResult.InvalidState(state)
        }

        index++
        return if (index < books.size) {
            HighlightProcessingResult.Next(books[index])
        } else {
            state = ImportState.FINISHED
            HighlightProcessingResult.Completed
        }
    }

    fun markFinished() {
        state = ImportState.FINISHED
    }

    fun markError() {
        state = ImportState.ERROR
    }

    sealed class BooksUpdateResult {
        object NoBooks : BooksUpdateResult()
        data class Ready(val nextBook: BookEntry) : BooksUpdateResult()
        data class InvalidState(val state: ImportState) : BooksUpdateResult()
    }

    sealed class HighlightProcessingResult {
        data class Next(val nextBook: BookEntry) : HighlightProcessingResult()
        object Completed : HighlightProcessingResult()
        data class InvalidState(val state: ImportState) : HighlightProcessingResult()
    }
}
