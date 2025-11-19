package com.kindler

import svarzee.gps.gpsoauth.Gpsoauth.TokenRequestFailed
import java.io.IOException

class HighlightsKeepExporter(
    private val highlightsFileStore: HighlightsFileStore
) {

    @Throws(
        IOException::class,
        APIException::class,
        APIAuth.LoginException::class,
        TokenRequestFailed::class,
        ResyncRequiredException::class,
        UpgradeRecommendedException::class
    )
    fun exportToKeep(email: String, masterToken: String) {
        val keepSync = GKeepSync()
        keepSync.authenticate(email, masterToken)

        val labelName = "Kindler export"
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
    }
}
