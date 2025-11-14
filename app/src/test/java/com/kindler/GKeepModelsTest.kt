package com.kindler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GKeepAnnotationsTest {

    @Test
    fun weblink_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip { WebLink() }
        assertEquals(original, cloned)
    }

    @Test
    fun category_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip {
            Category().apply { category = CategoryValue.Books }
        }
        assertEquals(original, cloned)
    }

    @Test
    fun taskAssist_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip { TaskAssist() }
        assertEquals(original, cloned)
    }

    @Test
    fun weblink_shouldTrackDirtyFlagsForProperties() {
        val link = WebLink()

        clean(link)
        assertNull(link.title)
        assertEquals("", link.url)
        assertNull(link.imageUrl)
        assertEquals("", link.provenanceUrl)
        assertNull(link.description)

        clean(link)
        link.title = "Title"
        assertTrue(link.dirty)
        assertEquals("Title", link.title)

        clean(link)
        link.url = "https://example.com"
        assertTrue(link.dirty)
        assertEquals("https://example.com", link.url)

        clean(link)
        link.imageUrl = "https://example.com/image.png"
        assertTrue(link.dirty)
        assertEquals("https://example.com/image.png", link.imageUrl)

        clean(link)
        link.provenanceUrl = "https://origin.example.com"
        assertTrue(link.dirty)
        assertEquals("https://origin.example.com", link.provenanceUrl)

        clean(link)
        link.description = "Description"
        assertTrue(link.dirty)
        assertEquals("Description", link.description)
    }

    @Test
    fun category_shouldUpdateCategoryAndTrackDirty() {
        val category = Category()
        category.category = CategoryValue.TV

        clean(category)
        category.category = CategoryValue.Books
        assertTrue(category.dirty)
        assertEquals(CategoryValue.Books, category.category)
    }

    @Test
    fun taskAssist_shouldUpdateSuggestAndTrackDirty() {
        val taskAssist = TaskAssist()

        clean(taskAssist)
        taskAssist.suggest = "UNKNOWN"
        assertTrue(taskAssist.dirty)
        assertEquals("UNKNOWN", taskAssist.suggest)
    }
}

class GKeepContextTest {

    @Test
    fun context_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip {
            Context().apply {
                val link = WebLink().apply {
                    id = "weblink-id"
                    url = "https://keep.example"
                }
                contextEntries(this)["webLink"] = link
            }
        }
        assertEquals(original, cloned)
    }

    @Test
    fun context_shouldReportDirtyWhenContainedAnnotationDirty() {
        val context = Context()
        val link = WebLink().apply {
            id = "entry"
            url = "https://keep.example"
        }
        contextEntries(context)["webLink"] = link

        clean(context)
        link.url = "https://keep.example/updated"
        assertTrue(link.dirty)
        assertTrue(context.dirty)

        clean(context)
        assertFalse(context.dirty)
    }
}

class NodeAnnotationsTest {

    @Test
    fun nodeAnnotations_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip { NodeAnnotations() }
        assertEquals(original, cloned)
    }

    @Test
    fun nodeAnnotations_shouldManageCategoryAndLinks() {
        val annotations = NodeAnnotations()

        clean(annotations)
        annotations.category = CategoryValue.Books
        assertTrue(annotations.dirty)
        assertEquals(CategoryValue.Books, annotations.category)

        clean(annotations)
        annotations.category = null
        assertTrue(annotations.dirty)
        assertNull(annotations.category)

        val categoryAnnotation = Category().apply { category = CategoryValue.TV }
        clean(annotations)
        clean(categoryAnnotation)
        annotations.append(categoryAnnotation)
        assertTrue(annotations.dirty)
        assertEquals(CategoryValue.TV, annotations.category)

        val link = WebLink()
        clean(link)

        clean(annotations)
        annotations.append(link)
        assertTrue(annotations.dirty)
        assertEquals(listOf(link), annotations.links)
        assertEquals(2, annotations.all().size)

        clean(annotations)
        annotations.remove(link)
        assertTrue(annotations.dirty)
        assertTrue(annotations.links.isEmpty())
    }
}

class NodeTimestampsTest {

    @Test
    fun timestamps_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip { NodeTimestamps(0.0) }
        assertEquals(original, cloned)
    }

    @Test
    fun timestamps_shouldUpdateFieldsAndReportDirty() {
        val timestamps = NodeTimestamps(0.0)
        val reference = NodeTimestamps.fromEpoch(42.0)

        clean(timestamps)
        timestamps.created = reference
        assertTrue(timestamps.dirty)
        assertEquals(reference, timestamps.created)

        clean(timestamps)
        timestamps.deleted = reference
        assertTrue(timestamps.dirty)
        assertEquals(reference, timestamps.deleted)

        clean(timestamps)
        timestamps.trashed = reference
        assertTrue(timestamps.dirty)
        assertEquals(reference, timestamps.trashed)

        clean(timestamps)
        timestamps.updated = reference
        assertTrue(timestamps.dirty)
        assertEquals(reference, timestamps.updated)

        clean(timestamps)
        timestamps.edited = reference
        assertTrue(timestamps.dirty)
        assertEquals(reference, timestamps.edited)
    }
}

class NodeSettingsTest {

    @Test
    fun settings_shouldUpdateEachField() {
        val settings = NodeSettings()

        clean(settings)
        settings.newListItemPlacement = NewListItemPlacementValue.Top
        assertTrue(settings.dirty)
        assertEquals(NewListItemPlacementValue.Top, settings.newListItemPlacement)

        clean(settings)
        settings.graveyardState = GraveyardStateValue.Expanded
        assertTrue(settings.dirty)
        assertEquals(GraveyardStateValue.Expanded, settings.graveyardState)

        clean(settings)
        settings.checkedListItemsPolicy = CheckedListItemsPolicyValue.Default
        assertTrue(settings.dirty)
        assertEquals(CheckedListItemsPolicyValue.Default, settings.checkedListItemsPolicy)
    }
}

class NodeLabelsTest {

    @Test
    fun labels_shouldTrackAddAndRemove() {
        val labels = NodeLabels()
        labels.load(null)

        val label = Label().apply { name = "Label" }

        clean(labels)
        labels.add(label)
        assertTrue(labels.dirty)
        assertEquals(label, labels.get(label.id))
        assertEquals(listOf(label), labels.all())

        clean(labels)
        labels.remove(label)
        assertTrue(labels.dirty)
        assertTrue(labels.all().isEmpty())
    }
}

class NodeModelTest {

    @Test
    fun node_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip { Node(nodeType = NodeType.Note) }
        assertEquals(original, cloned)
    }

    @Test
    fun node_shouldTrackChangesToCoreFields() {
        val node = Node(nodeType = NodeType.Note)
        val reference = NodeTimestamps.fromEpoch(123.0)

        clean(node)
        node.timestamps.created = reference
        assertTrue(node.dirty)
        assertEquals(reference, node.timestamps.created)

        clean(node)
        node.sort = 42L
        assertTrue(node.dirty)
        assertEquals(42L, node.sort)

        clean(node)
        node.setText("Text")
        assertTrue(node.dirty)
        assertEquals("Text", node.text)

        clean(node)
        node.settings.newListItemPlacement = NewListItemPlacementValue.Top
        assertTrue(node.dirty)
        assertEquals(NewListItemPlacementValue.Top, node.settings.newListItemPlacement)

        clean(node)
        node.annotations.category = CategoryValue.Books
        assertTrue(node.dirty)
        assertEquals(CategoryValue.Books, node.annotations.category)

        clean(node)
        node.annotations.category = null
        assertTrue(node.dirty)
        assertNull(node.annotations.category)
    }

    @Test
    fun node_shouldPropagateDirtyFromChildren() {
        val node = Node(nodeType = NodeType.Note)
        val child = ListItem(parentId = node.id)
        node.append(child)

        clean(node)
        child.setText("Child text")
        assertTrue(child.dirty)
        assertTrue(node.dirty)

        clean(node)
        child.checked = true
        assertTrue(child.dirty)
        assertTrue(node.dirty)
    }

    @Test
    fun node_deletedSetterShouldUpdateTimestamps() {
        val node = Node(nodeType = NodeType.Note)

        clean(node)
        node.deleted = true
        assertTrue(node.dirty)
        assertNotNull(node.timestamps.deleted)

        clean(node)
        node.deleted = false
        assertTrue(node.dirty)
        assertNull(node.timestamps.deleted)
    }

    @Test
    fun node_saveShouldRequireType() {
        val node = Node()
        try {
            node.save(includeDirty = true)
            fail("Expected IllegalStateException when saving node without type")
        } catch (_: IllegalStateException) {
            // Expected
        }
    }
}

class RootModelTest {

    @Test
    fun root_shouldNeverBeDirty() {
        val root = Root()
        assertFalse(root.dirty)
    }
}

class TimestampsMixinTest {

    private class TestElement : Element(), TimestampsMixin {
        override val timestamps = NodeTimestamps(0.0)
        override fun markTimestampsDirty() {
            dirtyFlag = true
        }
    }

    @Test
    fun touch_shouldUpdateTimestamps() {
        val element = TestElement()
        val baseline = NodeTimestamps.zero()

        clean(element.timestamps)
        element.touch()
        assertTrue(element.dirty)
        assertTrue(element.timestamps.updated.isAfter(baseline))
        assertEquals(baseline, element.timestamps.edited)

        clean(element)
        element.touch(true)
        assertTrue(element.timestamps.updated.isAfter(baseline))
        assertTrue(element.timestamps.edited!!.isAfter(baseline))
    }

    @Test
    fun deletedFlag_shouldReflectTimestampState() {
        val element = TestElement()

        assertFalse(element.deleted)

        element.timestamps.deleted = null
        assertFalse(element.deleted)

        element.timestamps.deleted = NodeTimestamps.zero()
        assertFalse(element.deleted)

        element.timestamps.deleted = NodeTimestamps.fromEpoch(1.0)
        assertTrue(element.deleted)
    }

    @Test
    fun trashedFlag_shouldReflectTimestampState() {
        val element = TestElement()

        assertFalse(element.trashed)

        element.timestamps.trashed = null
        assertFalse(element.trashed)

        element.timestamps.trashed = NodeTimestamps.zero()
        assertFalse(element.trashed)

        element.timestamps.trashed = NodeTimestamps.fromEpoch(1.0)
        assertTrue(element.trashed)
    }

    @Test
    fun trashToggle_shouldMarkTimestampsDirty() {
        val element = TestElement()

        clean(element.timestamps)
        element.trashed = true
        assertTrue(element.timestamps.dirty)
        assertTrue(element.trashed)

        clean(element.timestamps)
        element.trashed = false
        assertTrue(element.timestamps.dirty)
        assertFalse(element.trashed)
    }

    @Test
    fun deleteToggle_shouldMarkTimestampsDirty() {
        val element = TestElement()

        clean(element.timestamps)
        element.deleted = true
        assertTrue(element.timestamps.dirty)
        assertTrue(element.deleted)

        clean(element.timestamps)
        element.deleted = false
        assertTrue(element.timestamps.dirty)
        assertFalse(element.deleted)
    }
}

class NoteModelTest {

    @Test
    fun note_shouldRoundTripTextAndUrl() {
        val note = Note()
        note.id = "3"

        clean(note)
        note.setText("Text")
        assertTrue(note.dirty)
        assertEquals("Text", note.text)

        clean(note)
        assertEquals("https://keep.google.com/u/0/#NOTE/3", note.url)
    }

    @Test
    fun note_toStringShouldCombineTitleAndText() {
        val note = Note()

        assertEquals("", note.text)

        clean(note)
        note.title = "Title"
        note.setText("Body")
        assertEquals("Title\nBody", note.toString())
        assertEquals("Body", note.text)
    }
}

class ListNodeModelTest {

    @Test
    fun listNode_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip { ListNode() }
        assertEquals(original, cloned)
    }

    @Test
    fun listNode_addShouldPopulateCheckedAndUncheckedCollections() {
        val list = ListNode()

        clean(list)
        val unchecked = list.add("Item A", sort = 1)
        val checked = list.add("Item B", checked = true, sort = 2)

        assertTrue(list.dirty)
        assertEquals(listOf(checked), list.checkedItems)
        assertEquals(listOf(unchecked), list.uncheckedItems)
    }

    @Test
    fun listNode_itemsShouldSortBySortDescending() {
        val list = ListNode()

        val itemC = list.add("Item C", sort = 5)
        val itemA = list.add("Item A", sort = 3)
        val itemB = list.add("Item B", sort = 0)

        val order = list.items.map { it.id }
        assertEquals(listOf(itemC.id, itemA.id, itemB.id), order)
    }

    @Test
    fun listNode_indentAndDedentShouldUpdateChildState() {
        val list = ListNode()
        val parentItem = list.add("Parent", sort = 0)
        val childItem = list.add("Child", sort = 1)

        clean(list)
        parentItem.indent(childItem)
        assertTrue(childItem.indented)
        assertEquals(parentItem, childItem.parentItem)

        parentItem.dedent(childItem)
        assertFalse(childItem.indented)
        assertNull(childItem.parentItem)
    }

    @Test
    fun listNode_addWithBottomPlacementShouldUseLowerSortValue() {
        val list = ListNode()
        val top = list.add("Top", sort = 20_000)
        val bottom = list.add("Bottom", sort = 10_000)

        clean(list)
        val appended = list.add("New", sort = NewListItemPlacementValue.Bottom)

        assertTrue(appended.sort < bottom.sort)
        assertTrue(list.items.first().id == top.id)
    }
}

class ListItemModelTest {

    @Test
    fun listItem_shouldTrackTextAndCheckedState() {
        val item = ListItem()

        clean(item)
        item.setText("Text")
        assertTrue(item.dirty)
        assertEquals("Text", item.text)

        clean(item)
        item.checked = true
        assertTrue(item.dirty)
        assertTrue(item.checked)
    }
}

class NodeCollaboratorsTest {

    @Test
    fun collaborators_shouldRoundTripThroughSaveLoad() {
        val collaborators = NodeCollaborators()
        collaborators.add("user@google.com")

        val saved = collaborators.saveNodeCollaborators(true)
        val loaded = NodeCollaborators().apply { load(saved.first, saved.second) }

        assertEquals(saved, loaded.saveNodeCollaborators(true))
    }

    @Test
    fun collaborators_shouldAddAndRemoveEntries() {
        val collaborators = NodeCollaborators()

        clean(collaborators)
        collaborators.add("user@google.com")
        assertTrue(collaborators.dirty)
        assertEquals(listOf("user@google.com"), collaborators.all())

        clean(collaborators)
        collaborators.remove("user@google.com")
        assertTrue(collaborators.dirty)
        assertTrue(collaborators.all().isEmpty())
    }
}

class BlobFactoryTest {

    @Test
    fun blobFactory_shouldReturnSpecificBlobTypes() {
        val image = Blob.fromPayload(
            mapOf(
                "type" to BlobType.Image.value,
                "blob_id" to "blob",
                "media_id" to "media",
                "mimetype" to "image/png"
            )
        )
        assertTrue(image is NodeImage)

        val drawing = Blob.fromPayload(mapOf("type" to BlobType.Drawing.value))
        assertTrue(drawing is NodeDrawing)

        val audio = Blob.fromPayload(mapOf("type" to BlobType.Audio.value))
        assertTrue(audio is NodeAudio)
    }
}

class LabelModelTest {

    @Test
    fun label_shouldRoundTripThroughSaveLoad() {
        val (original, cloned) = roundTrip {
            Label().apply { name = "Label" }
        }
        assertEquals(original, cloned)
    }

    @Test
    fun label_shouldTrackNameAndMergedFields() {
        val label = Label()
        val mergedTime = NodeTimestamps.fromEpoch(64.0)

        clean(label)
        label.name = "name"
        assertTrue(label.dirty)
        assertEquals("name", label.name)
        assertEquals("name", label.toString())

        clean(label)
        label.merged = mergedTime
        assertTrue(label.dirty)
        assertEquals(mergedTime, label.merged)
    }
}

class ElementSerializationTest {

    private class DirtyElement : Element() {
        var value: Int by dirtyProperty(0)
    }

    @Test
    fun save_shouldEmitDirtyFlagWhenNotClean() {
        val element = DirtyElement()
        element.value = 1

        val saved = element.save(includeDirty = true)
        assertTrue(saved.containsKey("_dirty"))
        assertTrue(saved["_dirty"] as Boolean)
    }
}

class NodeFactoryTest {

    @Test
    fun nodeFromPayload_shouldReturnNoteInstance() {
        val data = mapOf(
            "id" to "id",
            "parentId" to RootId.ID,
            "timestamps" to mapOf(
                "created" to NodeTimestamps.intToStr(0),
                "updated" to NodeTimestamps.intToStr(0)
            ),
            "nodeSettings" to mapOf(
                "newListItemPlacement" to NewListItemPlacementValue.Top.value,
                "graveyardState" to GraveyardStateValue.Collapsed.value,
                "checkedListItemsPolicy" to CheckedListItemsPolicyValue.Default.value
            ),
            "annotationsGroup" to emptyMap<String, Any?>(),
            "kind" to "notes#node",
            "type" to NodeType.Note.value,
            "text" to "",
            "sortValue" to 0
        )

        val node = nodeFromPayload(data)
        assertTrue(node is Note)
    }

    @Test
    fun nodeFromPayload_shouldReturnNullWhenTypeMissing() {
        assertNull(nodeFromPayload(emptyMap<String, Any>()))
    }
}

private fun <T : Element> roundTrip(factory: () -> T): Pair<MutableMap<String, Any?>, MutableMap<String, Any?>> {
    val original = factory()
    val clone = factory()
    val originalSave = original.save()
    clone.load(originalSave)
    val cloneSave = clone.save()
    return originalSave to cloneSave
}

private fun <T : Element> clean(element: T): T {
    when (element) {
        is Node -> {
            element.clearDirty()
            element.children.forEach { clean(it) }
        }
        is NodeAnnotations -> element.clearDirty()
        is Context -> element.clearDirty()
        else -> element.clearDirty()
    }
    assertFalse(element.dirty)
    return element
}

private fun contextEntries(context: Context): MutableMap<String, Annotation> {
    val field = Context::class.java.getDeclaredField("entries")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(context) as MutableMap<String, Annotation>
}
