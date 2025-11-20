package com.kindler

import svarzee.gps.gpsoauth.Gpsoauth.TokenRequestFailed
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class HighlightsKeepExporter(
    private val highlightsFileStore: HighlightsFileStore,
    private val keepSync: GKeepSync = GKeepSync(),
    private val labelName: String = "Kindler export",
    filesDir: File,
    keepStateFileName: String = KEEP_STATE_FILE_NAME
) {
    private val keepStateFile: File = File(filesDir, keepStateFileName)

    @Throws(
        IOException::class,
        APIException::class,
        APIAuth.LoginException::class,
        TokenRequestFailed::class,
        ResyncRequiredException::class,
        UpgradeRecommendedException::class
    )
    fun exportToKeep(email: String, masterToken: String) {
        val cachedState = loadPersistedState()
        keepSync.authenticate(email, masterToken, state = cachedState)

        val label = keepSync.findLabel(labelName) ?: keepSync.createLabel(labelName)

        var fromStart = true
        while (true) {
            val loadResult = highlightsFileStore.loadBooks(limit = 50, fromStart = fromStart)
            fromStart = false

            if (loadResult.books.isEmpty() && !loadResult.hasMore) {
                break
            }

            loadResult.books.forEach { book ->
                book.highlights.forEach { highlightEntry ->
                    val contentHash = (highlightEntry.highlight + highlightEntry.note).hashCode()
                    val noteId = "${book.asin}.${Integer.toHexString(contentHash)}"

                    if (keepSync.get(noteId) == null) {
                        val text = StringBuilder(highlightEntry.highlight)
                        if (highlightEntry.note.isNotBlank()) {
                            text.append("\n\nNote: ").append(highlightEntry.note)
                        }

                        val note = keepSync.createNote(
                            title = book.title,
                            text = text.toString(),
                            id = noteId
                        )
                        note.labels.add(label)
                    }
                }
            }

            if (!loadResult.hasMore) {
                break
            }
        }

        keepSync.sync()
        persistState(keepSync.dump())
    }

    private fun loadPersistedState(): String? {
        if (!keepStateFile.exists()) {
            return null
        }

        return try {
            keepStateFile.readText(StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (error: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    private fun persistState(state: String) {
        val parentDir = keepStateFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Failed to create directory for Keep state file: ${parentDir.absolutePath}")
        }

        keepStateFile.writeText(state, StandardCharsets.UTF_8)
    }
}
