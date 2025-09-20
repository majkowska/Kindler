package com.kindler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookImportStateMachineTest {

    private val sampleBooks = listOf(
        BookEntry("ASIN1", "Book One", "Author A", "2023-01-01"),
        BookEntry("ASIN2", "Book Two", "Author B", "2023-02-02")
    )

    @Test
    fun startImport_initializesState() {
        val stateMachine = NotebookImportStateMachine()

        stateMachine.startImport()

        assertEquals(ImportState.LOADING_BOOK_LIST, stateMachine.state)
        assertEquals(0, stateMachine.currentBookIndex)
        assertEquals(0, stateMachine.totalBooks)
    }

    @Test
    fun onBooksParsed_withNoBooks_finishesProcess() {
        val stateMachine = NotebookImportStateMachine()
        stateMachine.startImport()

        val result = stateMachine.onBooksParsed(emptyList())

        assertTrue(result is NotebookImportStateMachine.BooksUpdateResult.NoBooks)
        assertEquals(ImportState.FINISHED, stateMachine.state)
    }

    @Test
    fun onBooksParsed_withBooks_movesToHighlightState() {
        val stateMachine = NotebookImportStateMachine()
        stateMachine.startImport()

        val result = stateMachine.onBooksParsed(sampleBooks)

        assertTrue(result is NotebookImportStateMachine.BooksUpdateResult.Ready)
        assertEquals(ImportState.LOADING_HIGHLIGHTS, stateMachine.state)
        assertEquals(sampleBooks.first(), stateMachine.currentBook())
        assertEquals(sampleBooks.size, stateMachine.totalBooks)
    }

    @Test
    fun advanceToNextBook_iteratesThroughAllBooks() {
        val stateMachine = NotebookImportStateMachine()
        stateMachine.startImport()
        stateMachine.onBooksParsed(sampleBooks)

        val firstAdvance = stateMachine.advanceToNextBook()
        assertTrue(firstAdvance is NotebookImportStateMachine.HighlightProcessingResult.Next)
        assertEquals(sampleBooks[1], (firstAdvance as NotebookImportStateMachine.HighlightProcessingResult.Next).nextBook)
        assertEquals(sampleBooks[1], stateMachine.currentBook())

        val secondAdvance = stateMachine.advanceToNextBook()
        assertTrue(secondAdvance is NotebookImportStateMachine.HighlightProcessingResult.Completed)
        assertEquals(ImportState.FINISHED, stateMachine.state)
    }

    @Test
    fun advanceToNextBook_whenCalledInWrongState_returnsInvalidState() {
        val stateMachine = NotebookImportStateMachine()

        val result = stateMachine.advanceToNextBook()

        assertTrue(result is NotebookImportStateMachine.HighlightProcessingResult.InvalidState)
    }
}
