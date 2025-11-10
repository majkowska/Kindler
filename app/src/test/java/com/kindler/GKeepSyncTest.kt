package com.kindler

import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GKeepSyncStateTest {

    @Test
    fun restore_shouldHydrateLabelsNodesAndDumpOriginalState() {
        val fixture = SyncStateFixtures.noteAndListState(version = "KEEP_VERSION_A")

        val sync = GKeepSync()
        sync.restore(fixture.state)

        val restoredNote = sync.get(fixture.noteId) as? Note
        assertNotNull(restoredNote)
        restoredNote!!
        assertEquals(fixture.noteTitle, restoredNote.title)
        assertEquals(fixture.noteText, restoredNote.text)

        val viaServerId = sync.get(fixture.noteServerId)
        assertSame(restoredNote, viaServerId)

        val booksLabel = sync.findLabel("Books")
        assertNotNull(booksLabel)
        assertEquals(fixture.labelsByName["Books"], booksLabel!!.id)

        assertEquals(fixture.state, sync.dump())
    }

    @Test
    fun restore_shouldPreserveListStructureAndRoundTripThroughDump() {
        val fixture = SyncStateFixtures.noteAndListState(version = "KEEP_VERSION_B")

        val sync = GKeepSync()
        sync.restore(fixture.state)

        val restoredList = sync.get(fixture.listId) as? ListNode
        assertNotNull(restoredList)
        restoredList!!
        assertTrue(restoredList.archived)
        assertEquals(fixture.listItemTexts, restoredList.items.map { it.text })
        assertEquals(fixture.labelsByName["Errands"], restoredList.labels.all().single().id)

        val viaServerId = sync.get(fixture.listServerId)
        assertSame(restoredList, viaServerId)

        assertEquals(fixture.state, sync.dump())
    }

    @Test
    fun sync_shouldSendNewNoteAndLabel() {
        val keepApi = mockk<KeepAPI>()
        var capturedTargetVersion: String? = "UNSET"
        lateinit var capturedNodes: List<Map<String, Any?>>
        lateinit var capturedLabels: List<Map<String, Any?>>
        var changesCallCount = 0

        every { keepApi.changes(any(), any(), any()) } answers {
            changesCallCount += 1
            val args = invocation.args
            @Suppress("UNCHECKED_CAST")
            capturedTargetVersion = args[0] as String?
            @Suppress("UNCHECKED_CAST")
            capturedNodes = args[1] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            capturedLabels = args[2] as List<Map<String, Any?>>
            JSONObject()
                .put("toVersion", "KEEP_VERSION_NEW")
                .put("truncated", false)
        }

        val sync = GKeepSync()
        sync.injectKeepApiForTest(keepApi)

        val label = sync.createLabel("Travel")
        val note = sync.createNote(title = "Packing list", text = "Passport")
        note.labels.add(label)

        val retrievedNote = sync.get(note.id)
        assertSame(note, retrievedNote)
        assertSame(label, retrievedNote!!.labels.get(label.id))

        sync.sync()

        assertEquals(1, changesCallCount)
        assertSame(null, capturedTargetVersion)

        val syncedNodes = capturedNodes
        assertTrue(syncedNodes.isNotEmpty())
        val nodePayload = syncedNodes.firstOrNull { it["id"] == note.id }
        assertNotNull(nodePayload)
        val labelRefs = nodePayload!!["labelIds"] as? List<*>
        assertNotNull(labelRefs)
        val labelRef = labelRefs!!.single() as Map<*, *>
        assertEquals(label.id, labelRef["labelId"])
        assertEquals("Travel", sync.findLabel("travel")?.name)

        val syncedLabels = capturedLabels
        assertEquals(1, syncedLabels.size)
        val labelPayload = syncedLabels.single()
        assertEquals(label.id, labelPayload["mainId"])
        assertEquals("Travel", labelPayload["name"])
    }

    @Test
    fun sync_shouldTreatUpdatedNoteAndLabelAsDirty() {
        val keepApi = mockk<KeepAPI>()
        val calls = mutableListOf<Triple<String?, List<Map<String, Any?>>, List<Map<String, Any?>>>>()

        every { keepApi.changes(any(), any(), any()) } answers {
            val args = invocation.args
            @Suppress("UNCHECKED_CAST")
            val targetVersion = args[0] as String?
            @Suppress("UNCHECKED_CAST")
            val nodes = args[1] as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val labels = args[2] as List<Map<String, Any?>>
            calls += Triple(targetVersion, nodes, labels)
            JSONObject()
                .put("toVersion", "KEEP_VERSION_${calls.size}")
                .put("truncated", false)
        }

        val sync = GKeepSync()
        sync.injectKeepApiForTest(keepApi)

        val label = sync.createLabel("Errands")
        val note = sync.createNote(title = "Weekend prep", text = "Buy milk")
        note.labels.add(label)

        sync.sync()
        assertEquals(1, calls.size)

        note.title = "Weekend prep updated"
        label.name = "Errands+"

        sync.sync()
        assertEquals(2, calls.size)

        val (_, _, firstCallLabels) = calls.first()
        assertTrue(firstCallLabels.any { it["mainId"] == label.id })

        val (secondTargetVersion, secondNodes, secondLabels) = calls[1]
        assertEquals("KEEP_VERSION_1", secondTargetVersion)

        val dirtyNote = secondNodes.firstOrNull { it["id"] == note.id }
        assertNotNull(dirtyNote)
        val dirtyLabel = secondLabels.firstOrNull { it["mainId"] == label.id }
        assertNotNull(dirtyLabel)
    }
}

/**
 * Builds deterministic Keep payloads that mimic the serialized state supplied by the real API.
 * Each fixture includes user info (labels), top-level nodes, and their immediate children so
 * restore() and dump() can be exercised end-to-end.
 */
private object SyncStateFixtures {

    fun noteAndListState(version: String): StateFixture {
        val books = labelFixture(id = "label-books", name = "Books")
        val errands = labelFixture(id = "label-errands", name = "Errands")

        val noteTitle = "Trip prep"
        val noteTextValue = "Pack the passport"
        val note = Note().apply {
            id = "note-trip"
            serverId = "server-note-trip"
            title = noteTitle
            color = ColorValue.Blue
            pinned = true
        }
        note.labels.add(books.model)
        val noteText = ListItem(
            parentId = note.id,
            parentServerId = note.serverId
        ).apply {
            id = "note-trip-text"
            serverId = "server-note-text"
            setText(noteTextValue)
            sort = 9_900_000_000L
        }
        note.append(noteText, dirty = false)

        val listTitle = "Groceries"
        val list = ListNode().apply {
            id = "list-groceries"
            serverId = "server-list-groceries"
            title = listTitle
            archived = true
            color = ColorValue.Yellow
        }
        list.labels.add(errands.model)
        val listItem1 = ListItem(
            parentId = list.id,
            parentServerId = list.serverId
        ).apply {
            id = "list-item-eggs"
            serverId = "server-list-eggs"
            setText("Eggs")
            sort = 9_500_000_000L
        }
        val listItem2 = ListItem(
            parentId = list.id,
            parentServerId = list.serverId
        ).apply {
            id = "list-item-bread"
            serverId = "server-list-bread"
            setText("Bread")
            checked = true
            sort = 9_400_000_000L
        }
        list.append(listItem1, dirty = false)
        list.append(listItem2, dirty = false)

        note.clearDirty()
        list.clearDirty()

        val nodes = listOf(
            note.save(includeDirty = true),
            noteText.save(includeDirty = true),
            list.save(includeDirty = true),
            listItem1.save(includeDirty = true),
            listItem2.save(includeDirty = true)
        )

        val labels = listOf(books.state, errands.state)
        val state = linkedMapOf<String, Any?>(
            "keep_version" to version,
            "labels" to labels,
            "nodes" to nodes
        )

        return StateFixture(
            state = state,
            noteId = note.id,
            noteServerId = note.serverId!!,
            noteTitle = noteTitle,
            noteText = noteTextValue,
            listId = list.id,
            listServerId = list.serverId!!,
            listItemTexts = listOf(listItem1.text, listItem2.text),
            labelsByName = mapOf(
                "Books" to books.model.id,
                "Errands" to errands.model.id
            )
        )
    }

    private fun labelFixture(id: String, name: String): LabelFixture {
        val label = Label().apply {
            this.id = id
            this.name = name
        }
        label.clearDirty()
        return LabelFixture(label, label.save(includeDirty = true))
    }

    private data class LabelFixture(
        val model: Label,
        val state: MutableMap<String, Any?>
    )

    data class StateFixture(
        val state: Map<String, Any?>,
        val noteId: String,
        val noteServerId: String,
        val noteTitle: String,
        val noteText: String,
        val listId: String,
        val listServerId: String,
        val listItemTexts: List<String>,
        val labelsByName: Map<String, String>
    )
}

private fun GKeepSync.injectKeepApiForTest(fake: KeepAPI) {
    val field = GKeepSync::class.java.getDeclaredField("keepApi")
    field.isAccessible = true
    field.set(this, fake)
}
