package com.kindler

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HighlightsKeepExporterTest {

    @Test
    fun exportToKeep_usesPersistedStateDuringAuthentication() {
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
            justRun { keepSync.sync() }
            every { keepSync.dump() } returns persistedStateJson

            val exporter = HighlightsKeepExporter(
                highlightsFileStore = highlightsStore,
                keepSync = keepSync,
                filesDir = tempDir
            )

            exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

            verify {
                keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, persistedStateJson, true, null)
            }
        } finally {
            tempDir.deleteRecursively()
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
            justRun { keepSync.sync() }
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
                keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, true, null)
                keepSync.findLabel("Kindler export")
                keepSync.sync()
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
        justRun { keepSync.sync() }
        every { keepSync.dump() } returns JSONObject().toString()

        val exporter = HighlightsKeepExporter(
            highlightsFileStore = highlightsStore,
            keepSync = keepSync,
            filesDir = tempDir
        )

        exporter.exportToKeep(TEST_EMAIL, TEST_MASTER_TOKEN)

        verify {
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, true, null)
        }
    }

    @Test
    fun exportToKeep_discardsStateWhenAuthenticationRequiresResync() {
        val tempDir = createTempDir()
        val stateFile = File(tempDir, KEEP_STATE_FILE_NAME)
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
        every {
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, persistedStateJson, true, null)
        } throws ResyncRequiredException("Full resync required")
        justRun { keepSync.resetState() }
        justRun { keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, true, null) }
        justRun { keepSync.sync() }
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
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, persistedStateJson, true, null)
            keepSync.resetState()
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, true, null)
            keepSync.findLabel("Kindler export")
            keepSync.sync()
            keepSync.dump()
        }
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
        every {
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, invalidStateJson, true, null)
        } throws IllegalArgumentException("State missing nodes")
        justRun { keepSync.resetState() }
        justRun { keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, true, null) }
        justRun { keepSync.sync() }
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
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, invalidStateJson, true, null)
            keepSync.resetState()
            keepSync.authenticate(TEST_EMAIL, TEST_MASTER_TOKEN, null, true, null)
            keepSync.findLabel("Kindler export")
            keepSync.sync()
            keepSync.dump()
        }
    }

    private fun createTempDir(): File =
        Files.createTempDirectory("keep-exporter-test").toFile()

    companion object {
        private const val TEST_EMAIL = "user@example.com"
        private const val TEST_MASTER_TOKEN = "token"
    }
}
