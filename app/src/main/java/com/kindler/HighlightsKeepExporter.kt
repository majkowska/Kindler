package com.kindler

import android.util.Log
import org.json.JSONObject
import svarzee.gps.gpsoauth.Gpsoauth.TokenRequestFailed
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.net.SocketTimeoutException

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
        authenticateWithoutState(email, masterToken)
        restoreState(cachedState)
        performSyncWithRetries(resyncOnForceFull = true)

        val label = keepSync.findLabel(labelName) ?: keepSync.createLabel(labelName)

        var fromStart = true
        while (true) {
            val loadResult = highlightsFileStore.loadBooks(limit = 50, fromStart = fromStart)
            fromStart = false

            if (loadResult.books.isEmpty() && !loadResult.hasMore) {
                break
            }

            loadResult.books.forEach { book ->
                book.highlights
                    .filter { it.note.isNotBlank() }
                    .forEach { highlightEntry ->
                        val contentHash = (highlightEntry.highlight + highlightEntry.note).hashCode()
                        val noteId = "${book.asin}.${Integer.toHexString(contentHash)}"

                        if (keepSync.get(noteId) == null) {
                            val note = keepSync.createNote(
                                title = "",
                                text = buildKeepNoteText(book, highlightEntry),
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

        performSyncWithRetries(resyncOnForceFull = false)
        persistState(keepSync.dump())
    }

    private fun loadPersistedState(): String? {
        if (!keepStateFile.exists()) {
            return null
        }

        val rawState = try {
            keepStateFile.readText(StandardCharsets.UTF_8)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read cached Keep state", error)
            return null
        }

        if (rawState.isBlank()) {
            return null
        }

        return try {
            JSONObject(rawState)
            rawState
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring invalid cached Keep state", error)
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

    private fun authenticateWithoutState(
        email: String,
        masterToken: String
    ) {
        retryWithTimeoutHandling("authenticating with Google Keep") {
            keepSync.authenticate(email, masterToken, state = null, sync = false)
        }
    }

    private fun restoreState(state: String?) {
        if (state.isNullOrBlank()) {
            return
        }

        try {
            keepSync.restore(state)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Persisted Keep state is invalid, clearing local cache", error)
            keepSync.clear()
        }
    }

    private fun performSyncWithRetries(resyncOnForceFull: Boolean) {
        try {
            retryWithTimeoutHandling("syncing with Google Keep") {
                keepSync.sync(resync = false)
            }
        } catch (error: ResyncRequiredException) {
            if (!resyncOnForceFull) {
                throw error
            }
            Log.w(TAG, "Keep requested a full resync; retrying sync from scratch", error)
            keepSync.clear()
            retryWithTimeoutHandling("syncing with Google Keep") {
                keepSync.sync(resync = true)
            }
        }
    }

    private fun retryWithTimeoutHandling(
        operation: String,
        block: () -> Unit
    ) {
        var lastError: SocketTimeoutException? = null
        repeat(MAX_TIMEOUT_ATTEMPTS) { attempt ->
            try {
                block()
                return
            } catch (timeout: SocketTimeoutException) {
                lastError = timeout
                Log.w(TAG, "Timeout while $operation (attempt ${attempt + 1})", timeout)
            } catch (error: Exception) {
                throw error
            }
        }
        val cause = lastError ?: SocketTimeoutException("Timeout while $operation")
        throw KeepSyncTimeoutException("Timed out while $operation", cause)
    }

    private fun buildKeepNoteText(book: BookEntry, highlightEntry: HighlightEntry): String {
        val highlightText = highlightEntry.highlight.trim()
        val noteText = highlightEntry.note.trim()
        val titleText = book.title.trim()
        val authorText = book.author.trim()

        val builder = StringBuilder()
        if (highlightText.isNotEmpty()) {
            builder.append(highlightText)
        } else {
            builder.append("Highlight")
        }
        builder.append("\n")
        builder.append("**Note:** ").append(noteText)
        builder.append("\n")
        builder.append("--")
        val hasTitle = titleText.isNotEmpty()
        val hasAuthor = authorText.isNotEmpty()
        if (hasTitle) {
            builder.append("*").append(titleText).append("*")
        }
        if (hasTitle && hasAuthor) {
            builder.append(" by ").append(authorText)
        } else if (!hasTitle && hasAuthor) {
            builder.append(authorText)
        }

        return builder.toString()
    }

    companion object {
        private const val TAG = "HighlightsKeepExporter"
        private const val MAX_TIMEOUT_ATTEMPTS = 2
    }
}

class KeepSyncTimeoutException(message: String, cause: Throwable) : IOException(message, cause)
