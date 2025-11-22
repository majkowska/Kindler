package com.kindler

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import java.io.File
import java.nio.file.Files
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HighlightsKeepExporterTest {

    @Test
    fun exportToKeep_restoresPersistedStateBeforeSync() {
        val tempDir = createTempDir()
        val stateFile = File(tempDir, KEEP_STATE_FILE_NAME)
        try {
            val persistedStateJson = JSONObject(
                mapOf(
                    "keep_version" to "v1",
                    "labels" to listOf(mapOf("mainId" to "label-1", "name" to "Kindler export")),
                    "nodes" to listOf(mapOf("id" to "note-1", "parentId" to RootId.ID))
                )
            ).toString()
            stateFile.writeText(persistedStateJson)

            val highlightsStore = mockk<HighlightsFileStore>()
            every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
                books = emptyList(),
                hasMore = false
            )

            val keepSync = mockk<GKeepSync>()
            val existingLabel = Label()
            every { keepSync.findLabel(any()) } returns existingLabel
            justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
            justRun { keepSync.restore(any()) }
            justRun { keepSync.sync(any()) }
            every { keepSync.dump() } returns persistedStateJson

            val exporter = HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            )

            exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

            verifySequence {
                keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, false, null)
                keepSync.restore(persistedStateJson)
                keepSync.sync(false)
                keepSync.findLabel("Kindler export")
                keepSync.sync(false)
                keepSync.dump()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun exportToKeep_resyncsWhenInitialSyncRequiresIt() {
        val tempDir = createTempDir()
        val stateFile = File(tempDir, KEEP_STATE_FILE_NAME)
        val persistedStateJson = JSONObject(
            mapOf(
                "keep_version" to "v10",
                "labels" to emptyList<Any>(),
                "nodes" to emptyList<Any>()
            )
        ).toString()
        stateFile.writeText(persistedStateJson)

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
            books = emptyList(),
            hasMore = false
        )

        val keepSync = mockk<GKeepSync>()
        val existingLabel = Label()
        every { keepSync.findLabel(any()) } returns existingLabel
        justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
        justRun { keepSync.restore(any()) }
        justRun { keepSync.clear() }
        var syncCallCount = 0
        every { keepSync.sync(false) } answers {
            if (syncCallCount++ == 0) {
                throw ResyncRequiredException("Full resync required")
            }
        }
        justRun { keepSync.sync(true) }
        every { keepSync.dump() } returns JSONObject().toString()

        val exporter = HighlightsKeepExporter(
            highlightsFileStore = highlightsStore,
            keepSync = keepSync,
            filesDir = tempDir
        )

        exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

        verifySequence {
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, false, null)
            keepSync.restore(persistedStateJson)
            keepSync.sync(false)
            keepSync.clear()
            keepSync.sync(true)
            keepSync.findLabel("Kindler export")
            keepSync.sync(false)
            keepSync.dump()
        }
    }

    @Test
    fun exportToKeep_persistsDumpAfterSync() {
        val tempDir = createTempDir()
        val stateFile = File(tempDir, KEEP_STATE_FILE_NAME)
        try {
            val highlightsStore = mockk<HighlightsFileStore>()
            every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
                books = emptyList(),
                hasMore = false
            )

            val keepSync = mockk<GKeepSync>()
            val existingLabel = Label()
            every { keepSync.findLabel(any()) } returns existingLabel
            justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
            justRun { keepSync.restore(any()) }
            justRun { keepSync.sync(any()) }
            val dumpStateJson = JSONObject(
                mapOf(
                    "keep_version" to "v42",
                    "labels" to listOf(mapOf("mainId" to "label-1")),
                    "nodes" to listOf(mapOf("id" to "note-1"))
                )
            ).toString()
            every { keepSync.dump() } returns dumpStateJson

            val exporter = HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            )

            exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

            assertTrue(stateFile.exists())
            val expectedState = JSONObject(dumpStateJson)
            val writtenState = JSONObject(stateFile.readText())
            assertEquals(
                expectedState.toString(),
                writtenState.toString()
            )

            verifySequence {
                keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, false, null)
                keepSync.sync(false)
                keepSync.findLabel("Kindler export")
                keepSync.sync(false)
                keepSync.dump()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun exportToKeep_ignoresMalformedStateFile() {
        val tempDir = createTempDir()
        val stateFile = File(tempDir, KEEP_STATE_FILE_NAME)
        stateFile.writeText("{not-json")

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
            books = emptyList(),
            hasMore = false
        )

        val keepSync = mockk<GKeepSync>()
        val existingLabel = Label()
        every { keepSync.findLabel(any()) } returns existingLabel
        justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
        justRun { keepSync.sync(any()) }
        every { keepSync.dump() } returns JSONObject().toString()

        val exporter = HighlightsKeepExporter(
            highlightsFileStore = highlightsStore,
            keepSync = keepSync,
            filesDir = tempDir
        )

        exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

        verify {
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, false, null)
        }
    }

    @Test
    fun exportToKeep_doesNotHandleResyncDuringFinalSync() {
        val tempDir = createTempDir()
        val stateFile = File(tempDir, KEEP_STATE_FILE_NAME)
        stateFile.writeText(
            JSONObject(
                mapOf(
                    "keep_version" to "v1",
                    "labels" to emptyList<Any>(),
                    "nodes" to emptyList<Any>()
                )
            ).toString()
        )

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
            books = listOf(
                BookEntry(
                    asin = "ASIN123",
                    title = "Title",
                    author = "Author",
                    lastAccessedDate = "today",
                    highlights = listOf(HighlightEntry("Highlight", "Note"))
                )
            ),
            hasMore = false
        )

        val keepSync = mockk<GKeepSync>()
        val existingLabel = Label()
        every { keepSync.findLabel(any()) } returns existingLabel
        justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
        justRun { keepSync.restore(any()) }
        every { keepSync.get(any()) } returns null
        every { keepSync.createNote(any(), any(), any()) } returns Note()
        var syncCallCount = 0
        every { keepSync.sync(false) } answers {
            if (syncCallCount++ == 0) {
                Unit
            } else {
                throw ResyncRequiredException("Full resync required")
            }
        }
        assertThrows(ResyncRequiredException::class.java) {
            HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            ).exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)
        }

        verify(exactly = 2) { keepSync.sync(false) }
        verify(exactly = 0) { keepSync.sync(true) }
        verify(exactly = 0) { keepSync.clear() }
    }

    @Test
    fun exportToKeep_discardsStateWhenAuthenticationFailsDueToInvalidData() {
        val tempDir = createTempDir()
        val stateFile = File(tempDir, KEEP_STATE_FILE_NAME)
        val invalidStateJson = JSONObject(
            mapOf(
                "keep_version" to "v1",
                "labels" to listOf(mapOf("mainId" to "label-1", "name" to "Kindler export"))
            )
        ).toString()
        stateFile.writeText(invalidStateJson)

        val highlightsStore = mockk<HighlightsFileStore>()
        every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
            books = emptyList(),
            hasMore = false
        )

        val keepSync = mockk<GKeepSync>()
        val existingLabel = Label()
        every { keepSync.findLabel(any()) } returns existingLabel
        justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
        every { keepSync.restore(invalidStateJson) } throws IllegalArgumentException("State missing nodes")
        justRun { keepSync.clear() }
        justRun { keepSync.sync(false) }
        val dumpStateJson = JSONObject(
            mapOf(
                "keep_version" to "v2",
                "labels" to emptyList<Any>(),
                "nodes" to emptyList<Any>()
            )
        ).toString()
        every { keepSync.dump() } returns dumpStateJson

        val exporter = HighlightsKeepExporter(
            highlightsFileStore = highlightsStore,
            keepSync = keepSync,
            filesDir = tempDir
        )

        exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

        assertTrue(stateFile.exists())
        val writtenState = JSONObject(stateFile.readText())
        assertEquals(JSONObject(dumpStateJson).toString(), writtenState.toString())

        verifySequence {
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, false, null)
            keepSync.restore(invalidStateJson)
            keepSync.clear()
            keepSync.sync(false)
            keepSync.findLabel("Kindler export")
            keepSync.sync(false)
            keepSync.dump()
        }
    }

    @Test
    fun exportToKeep_onlyCreatesNotesForHighlightsWithAnnotations() {
        val tempDir = createTempDir()
        try {
            val highlightWithNote = HighlightEntry(
                highlight = "Highlight with note",
                note = "Interesting thought"
            )
            val highlightWithoutNote = HighlightEntry(
                highlight = "Highlight without note",
                note = ""
            )
            val book = BookEntry(
                asin = "ASIN123",
                title = "Sample Book",
                author = "Sample Author",
                lastAccessedDate = "today",
                highlights = listOf(highlightWithNote, highlightWithoutNote)
            )

            val highlightsStore = mockk<HighlightsFileStore>()
            every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
                books = listOf(book),
                hasMore = false
            )

            val keepSync = mockk<GKeepSync>()
            val existingLabel = Label()
            every { keepSync.findLabel(any()) } returns existingLabel
            justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
            justRun { keepSync.sync(any()) }
            every { keepSync.dump() } returns JSONObject().toString()
            every { keepSync.get(any()) } returns null
            every { keepSync.createNote(any(), any(), any()) } answers { Note() }

            val exporter = HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            )

            exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

            val expectedNoteId = "${book.asin}.${
                Integer.toHexString((highlightWithNote.highlight + highlightWithNote.note).hashCode())
            }"
            verify(exactly = 1) {
                keepSync.createNote(eq(""), any(), eq(expectedNoteId))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun exportToKeep_formatsNoteBodyAndTitleForKeep() {
        val tempDir = createTempDir()
        try {
            val highlightEntry = HighlightEntry(
                highlight = "To be or not to be",
                note = "Sounds familiar"
            )
            val book = BookEntry(
                asin = "ASIN456",
                title = "Hamlet",
                author = "William Shakespeare",
                lastAccessedDate = "yesterday",
                highlights = listOf(highlightEntry)
            )

            val highlightsStore = mockk<HighlightsFileStore>()
            every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
                books = listOf(book),
                hasMore = false
            )

            val keepSync = mockk<GKeepSync>()
            val existingLabel = Label()
            every { keepSync.findLabel(any()) } returns existingLabel
            justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
            justRun { keepSync.sync(any()) }
            every { keepSync.dump() } returns JSONObject().toString()
            every { keepSync.get(any()) } returns null
            every { keepSync.createNote(any(), any(), any()) } answers { Note() }

            val exporter = HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            )

            exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

            val expectedText = listOf(
                highlightEntry.highlight,
                "**Note:** ${highlightEntry.note}",
                "--*${book.title}* by ${book.author}"
            ).joinToString("\n")

            verify {
                keepSync.createNote(eq(""), eq(expectedText), any())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun exportToKeep_wrapsAuthenticationTimeouts() {
        val tempDir = createTempDir()
        try {
            val highlightsStore = mockk<HighlightsFileStore>()
            every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
                books = emptyList(),
                hasMore = false
            )

            val keepSync = mockk<GKeepSync>()
            every { keepSync.authenticate(any(), any(), any(), any(), any()) } throws SocketTimeoutException("timeout")

            val exporter = HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            )

            assertThrows(KeepSyncTimeoutException::class.java) {
                exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun exportToKeep_wrapsSyncTimeouts() {
        val tempDir = createTempDir()
        try {
            val highlightsStore = mockk<HighlightsFileStore>()
            every { highlightsStore.loadBooks(any(), any()) } returns HighlightsFileStore.LoadResult(
                books = emptyList(),
                hasMore = false
            )

            val keepSync = mockk<GKeepSync>()
            val existingLabel = Label()
            every { keepSync.findLabel(any()) } returns existingLabel
            justRun { keepSync.authenticate(any(), any(), any(), any(), any()) }
            every { keepSync.sync() } throws SocketTimeoutException("timeout")
            every { keepSync.dump() } returns JSONObject().toString()

            val exporter = HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            )

            assertThrows(KeepSyncTimeoutException::class.java) {
                exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createTempDir(): File =
        Files.createTempDirectory("keep-exporter-test").toFile()

    companion object {
        private const val TEST_EMAIL = "user@example.com"
        private const val TEST_MASTER_TOKEN = "token"
    }
}
