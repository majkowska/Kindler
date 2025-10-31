package com.kindler

import android.util.Log
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.reflect.KProperty

/**
 * Google Keep data models for notes, lists, and related metadata.
 *
 * This file contains the domain models for Google Keep synchronization,
 * including note types, list items, annotations, timestamps, and settings.
 */

/* ========== Constants ========== */

private const val TAG = "GKeepModels"
private const val SORT_MIN = 1_000_000_000L
private const val SORT_MAX = 9_999_999_999L
private const val ALPHANUMERIC_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val RANDOM_ID_LENGTH = 12
private const val DEBUG_LOGGING = false

/* ========== Exceptions (stand-ins for .exception module) ========== */

class MergeException(message: String = "Merge conflict") : RuntimeException(message)

class InvalidException(message: String) : RuntimeException(message)

private fun currentEpochSeconds(): Double = System.currentTimeMillis() / 1000.0

/* ========== Enums ========== */

enum class NodeType(val wire: String) {
    Note("NOTE"),
    List("LIST"),
    ListItem("LIST_ITEM"),
    Blob("BLOB");

    companion object {
        fun fromWireOrNull(s: String): NodeType? = entries.firstOrNull { it.wire == s }
        fun fromWire(s: String) = fromWireOrNull(s)
            ?: throw InvalidException("Unknown NodeType: $s")
    }
}

enum class BlobType(val wire: String) {
    Audio("AUDIO"),
    Image("IMAGE"),
    Drawing("DRAWING");

    companion object {
        fun fromWireOrNull(s: String): BlobType? = entries.firstOrNull { it.wire == s }
        fun fromWire(s: String) = fromWireOrNull(s)
            ?: throw InvalidException("Unknown BlobType: $s")
    }
}

enum class ColorValue(val wire: String) {
    White("DEFAULT"),
    Red("RED"),
    Orange("ORANGE"),
    Yellow("YELLOW"),
    Green("GREEN"),
    Teal("TEAL"),
    Blue("BLUE"),
    DarkBlue("CERULEAN"),
    Purple("PURPLE"),
    Pink("PINK"),
    Brown("BROWN"),
    Gray("GRAY");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
            ?: throw InvalidException("Unknown ColorValue: $s")
    }
}

enum class CategoryValue(val wire: String) {
    Books("BOOKS"),
    Food("FOOD"),
    Movies("MOVIES"),
    Music("MUSIC"),
    Places("PLACES"),
    Quotes("QUOTES"),
    Travel("TRAVEL"),
    TV("TV");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
            ?: throw InvalidException("Unknown CategoryValue: $s")
    }
}

enum class NewListItemPlacementValue(val wire: String) {
    Top("TOP"), Bottom("BOTTOM");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
            ?: throw InvalidException("Unknown NewListItemPlacementValue: $s")
    }
}

enum class GraveyardStateValue(val wire: String) {
    Expanded("EXPANDED"), Collapsed("COLLAPSED");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
            ?: throw InvalidException("Unknown GraveyardStateValue: $s")
    }
}

enum class CheckedListItemsPolicyValue(val wire: String) {
    Default("DEFAULT"), Graveyard("GRAVEYARD");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
            ?: throw InvalidException("Unknown CheckedListItemsPolicyValue: $s")
    }
}

enum class ShareRequestValue(val wire: String) {
    Add("WR"), Remove("RM");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
            ?: throw InvalidException("Unknown ShareRequestValue: $s")
    }
}

enum class RoleValue(val wire: String) {
    Owner("O"), User("W");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
            ?: throw InvalidException("Unknown RoleValue: $s")
    }
}

/* ========== Element base ========== */

/**
 * Base class for all Google Keep data model elements.
 * Provides dirty tracking for change detection and serialization support.
 */
open class Element {
    protected var dirtyFlag: Boolean = false
    open fun load(raw: Map<*, *>) { dirtyFlag = (raw["_dirty"] as? Boolean) == true }
    open fun save(clean: Boolean = true): MutableMap<String, Any?> {
        val ret = mutableMapOf<String, Any?>()
        if (!clean) ret["_dirty"] = dirtyFlag else dirtyFlag = false
        return ret
    }
    protected fun <T> dirtyProperty(
        initialValue: T,
        onChange: () -> Unit = { dirtyFlag = true }
    ): DirtyDelegate<T> = DirtyDelegate(initialValue, onChange)
    open val dirty: Boolean get() = dirtyFlag
}

class DirtyDelegate<T>(
    initialValue: T,
    private val onChange: () -> Unit
) {
    private var value: T = initialValue

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
        if (value == newValue) return
        value = newValue
        onChange()
    }

    fun setRaw(newValue: T) {
        value = newValue
    }
}

/* ========== Annotations ========== */

/**
 * Base class for note annotations (web links, categories, task assists).
 */
sealed class Annotation : Element() {
    var id: String? = genId()
    override fun load(raw: Map<*, *>) {
        super.load(raw); id = raw["id"] as? String
    }
    override fun save(clean: Boolean): MutableMap<String, Any?> {
        if (id == null) return mutableMapOf()
        return super.save(clean).apply { put("id", id) }
    }

    companion object {
        private fun genId(): String = UUID.randomUUID().toString()
    }
}

class WebLink : Annotation() {
    private val titleDelegate = dirtyProperty<String?>(null)
    var title: String? by titleDelegate

    private val urlDelegate = dirtyProperty("")
    var url: String by urlDelegate

    private val imageUrlDelegate = dirtyProperty<String?>(null)
    var imageUrl: String? by imageUrlDelegate

    private val provenanceUrlDelegate = dirtyProperty("")
    var provenanceUrl: String by provenanceUrlDelegate

    private val descriptionDelegate = dirtyProperty<String?>(null)
    var description: String? by descriptionDelegate

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        val wl = raw["webLink"] as? Map<*, *> ?: return
        (wl["title"] as? String)?.let { titleDelegate.setRaw(it) }
        (wl["url"] as? String)?.let { urlDelegate.setRaw(it) }
        (wl["imageUrl"] as? String)?.let { imageUrlDelegate.setRaw(it) }
        (wl["provenanceUrl"] as? String)?.let { provenanceUrlDelegate.setRaw(it) }
        (wl["description"] as? String)?.let { descriptionDelegate.setRaw(it) }
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("webLink", mapOf(
            "title" to title,
            "url" to url,
            "imageUrl" to imageUrl,
            "provenanceUrl" to provenanceUrl,
            "description" to description
        ))
    }
}

class Category : Annotation() {
    private val categoryDelegate = dirtyProperty<CategoryValue?>(null)
    var category: CategoryValue? by categoryDelegate

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        val tc = raw["topicCategory"] as? Map<*, *> ?: return
        (tc["category"] as? String)?.let { categoryDelegate.setRaw(CategoryValue.fromWire(it)) }
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("topicCategory", mapOf("category" to category?.wire))
    }
}

class TaskAssist : Annotation() {
    private val suggestDelegate = dirtyProperty<String?>(null)
    var suggest: String? by suggestDelegate

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        suggestDelegate.setRaw((raw["taskAssist"] as? Map<*, *>)?.get("suggestType") as? String)
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("taskAssist", mapOf("suggestType" to suggest))
    }
}

class Context : Annotation() {
    private val entries: MutableMap<String, Annotation> = linkedMapOf()

    fun all(): List<Annotation> = entries.values.toList()

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        entries.clear()

        val ctx = raw["context"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        for ((k, v) in ctx) {
            val key = k as? String ?: continue
            val vMap = v as? Map<*, *> ?: continue
            val ann = NodeAnnotations.fromJson(mapOf(key to vMap))
            if (ann != null) entries[key] = ann
        }
    }

    override fun save(clean: Boolean): MutableMap<String, Any?> {
        val ret = super.save(clean)
        val ctx = mutableMapOf<String, Any?>()
        entries.values.forEach { entry ->
            // merge each saved sub-annotation (e.g., {"webLink": {...}}) into context
            ctx.putAll(entry.save(clean))
        }
        ret["context"] = ctx
        return ret
    }

    override val dirty: Boolean
        get() = super.dirty || entries.values.any { it.dirty }
}

class NodeAnnotations : Element() {
    private val annotations: MutableMap<String, Annotation> = linkedMapOf()

    fun all(): List<Annotation> = annotations.values.toList()

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        annotations.clear()
        val arr = raw["annotations"] as? List<*> ?: return
        for (item in arr) {
            val ann = fromJson(item as? Map<*, *> ?: continue) ?: continue
            val id = ann.id ?: continue
            annotations[id] = ann
        }
    }

    override fun save(clean: Boolean): MutableMap<String, Any?> {
        val ret = super.save(clean).apply { put("kind", "notes#annotationsGroup") }
        if (annotations.isNotEmpty()) {
            ret["annotations"] = annotations.values.map { it.save(clean) }
        }
        return ret
    }

    private fun categoryNode(): Category? =
        annotations.values.firstOrNull { it is Category } as? Category

    var category: CategoryValue?
        get() = categoryNode()?.category
        set(value) {
            val node = categoryNode()
            if (value == null) {
                if (node != null) annotations.remove(node.id)
            } else {
                val n = node ?: Category().also { annotations[it.id!!] = it }
                n.category = value
            }
            dirtyFlag = true
        }

    val links: List<WebLink>
        get() = annotations.values.filterIsInstance<WebLink>()

    fun append(annotation: Annotation): Annotation {
        annotations[annotation.id!!] = annotation
        dirtyFlag = true
        return annotation
    }

    fun remove(annotation: Annotation) {
        annotation.id?.let { annotations.remove(it) }
        dirtyFlag = true
    }

    override val dirty: Boolean
        get() = super.dirty || annotations.values.any { it.dirty }

    companion object {
        fun fromJson(raw: Map<*, *>): Annotation? {
            val annotation = when {
                "webLink" in raw -> WebLink()
                "topicCategory" in raw -> Category()
                "taskAssist" in raw -> TaskAssist()
                "context" in raw -> Context()
                else -> null
            }
            if (annotation == null) {
                Log.w(TAG, "Unknown annotation type: ${raw.keys}")
                return null
            }
            annotation.load(raw)
            return annotation
        }

        internal fun wrapSave(a: Annotation, clean: Boolean): Map<String, Any?> = a.save(clean)
    }
}

/* ========== Timestamp helpers ========== */

/**
 * Manages creation, modification, and deletion timestamps for nodes.
 */
class NodeTimestamps(createTime: Double? = null) : Element() {
    private val baseTime = createTime?.let { fromEpoch(it) } ?: now()

    private val createdDelegate = dirtyProperty(baseTime)
    var created: ZonedDateTime by createdDelegate

    private val deletedDelegate = dirtyProperty<ZonedDateTime?>(null)
    var deleted: ZonedDateTime? by deletedDelegate

    private val trashedDelegate = dirtyProperty<ZonedDateTime?>(null)
    var trashed: ZonedDateTime? by trashedDelegate

    private val updatedDelegate = dirtyProperty(baseTime)
    var updated: ZonedDateTime by updatedDelegate

    private val editedDelegate = dirtyProperty<ZonedDateTime?>(baseTime)
    var edited: ZonedDateTime? by editedDelegate

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        (raw["created"] as? String)?.let { createdDelegate.setRaw(strToDt(it)) }
        deletedDelegate.setRaw((raw["deleted"] as? String)?.let { strToDt(it) })
        trashedDelegate.setRaw((raw["trashed"] as? String)?.let { strToDt(it) })
        (raw["updated"] as? String)?.let { updatedDelegate.setRaw(strToDt(it)) }
        editedDelegate.setRaw((raw["userEdited"] as? String)?.let { strToDt(it) })
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("kind", "notes#timestamps")
        put("created", dtToStr(created))
        deleted?.let { put("deleted", dtToStr(it)) }
        trashed?.let { put("trashed", dtToStr(it)) }
        put("updated", dtToStr(updated))
        edited?.let { put("userEdited", dtToStr(it)) }
    }

    companion object {
        private val FMT: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendLiteral('.')
            .appendFraction(ChronoField.MICRO_OF_SECOND, 6, 6, false)
            .appendLiteral('Z')
            .toFormatter()
            .withZone(ZoneOffset.UTC)

        fun strToDt(s: String): ZonedDateTime = ZonedDateTime.parse(s, FMT)
        fun dtToStr(dt: ZonedDateTime): String = dt.format(FMT)
        fun fromEpoch(sec: Double): ZonedDateTime {
            val seconds = sec.toLong()
            val nanos = ((sec - seconds) * 1_000_000_000).roundToLong()
            return Instant.ofEpochSecond(seconds, nanos).atZone(ZoneOffset.UTC)
        }
        fun now(): ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)
        fun zero(): ZonedDateTime = fromEpoch(0.0)
        fun intToStr(sec: Long): String = dtToStr(fromEpoch(sec.toDouble()))
    }
}

/* ========== Settings, Collaborators, Labels ========== */

class NodeSettings : Element() {
    private val newListPlacementDelegate =
        dirtyProperty(NewListItemPlacementValue.Bottom)
    var newListItemPlacement: NewListItemPlacementValue by newListPlacementDelegate

    private val graveyardStateDelegate = dirtyProperty(GraveyardStateValue.Collapsed)
    var graveyardState: GraveyardStateValue by graveyardStateDelegate

    private val checkedItemsPolicyDelegate =
        dirtyProperty(CheckedListItemsPolicyValue.Graveyard)
    var checkedListItemsPolicy: CheckedListItemsPolicyValue by checkedItemsPolicyDelegate

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        (raw["newListItemPlacement"] as? String)?.let {
            newListPlacementDelegate.setRaw(NewListItemPlacementValue.fromWire(it))
        }
        (raw["graveyardState"] as? String)?.let {
            graveyardStateDelegate.setRaw(GraveyardStateValue.fromWire(it))
        }
        (raw["checkedListItemsPolicy"] as? String)?.let {
            checkedItemsPolicyDelegate.setRaw(CheckedListItemsPolicyValue.fromWire(it))
        }
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("newListItemPlacement", newListItemPlacement.wire)
        put("graveyardState", graveyardState.wire)
        put("checkedListItemsPolicy", checkedListItemsPolicy.wire)
    }
}

class NodeCollaborators : Element() {
    private val collaborators: MutableMap<String, Any> = linkedMapOf()

    fun add(email: String) {
        collaborators.putIfAbsent(email, ShareRequestValue.Add)
        dirtyFlag = true
    }

    fun remove(email: String) {
        val cur = collaborators[email]
        when (cur) {
            null -> Unit
            ShareRequestValue.Add -> collaborators.remove(email)
            else -> collaborators[email] = ShareRequestValue.Remove
        }
        dirtyFlag = true
    }

    fun all(): List<String> = collaborators.filter { (_, v) ->
        val isRole = v is RoleValue && (v == RoleValue.Owner || v == RoleValue.User)
        isRole || v == ShareRequestValue.Add
    }.keys.toList()

    fun load(collaboratorsRaw: List<Map<*, *>>, requestsRawWithFlag: MutableList<Any?>) {
        dirtyFlag = if (requestsRawWithFlag.isNotEmpty() &&
            requestsRawWithFlag.last() is Boolean
        ) {
            requestsRawWithFlag.removeAt(requestsRawWithFlag.lastIndex) as Boolean
        } else {
            false
        }

        collaborators.clear()

        collaboratorsRaw.forEach { m ->
            val email = m["email"] as? String ?: return@forEach
            val roleWire = m["role"] as? String ?: return@forEach
            collaborators[email] = RoleValue.fromWire(roleWire)
        }

        requestsRawWithFlag.forEach { r ->
            val rm = r as? Map<*, *> ?: return@forEach
            val email = rm["email"] as? String ?: return@forEach
            val type = rm["type"] as? String ?: return@forEach
            collaborators[email] = ShareRequestValue.fromWire(type)
        }
    }

    fun saveNodeCollaborators(
        clean: Boolean
    ): Pair<List<Map<String, Any?>>, MutableList<Any?>> {
        val collabs = mutableListOf<Map<String, Any?>>()
        val requests = mutableListOf<Any?>()
        collaborators.forEach { (email, action) ->
            when (action) {
                is ShareRequestValue -> {
                    requests += mapOf("email" to email, "type" to action.wire)
                }
                is RoleValue -> {
                    collabs += mapOf(
                        "email" to email,
                        "role" to action.wire,
                        "auxiliary_type" to "None"
                    )
                }
            }
        }
        if (!clean) requests += dirtyFlag else dirtyFlag = false
        return collabs to requests
    }
}

class Label : Element() {
    var id: String = generateId(Instant.now().epochSecond)

    private val nameDelegate = dirtyProperty("", ::touchEdited)
    var name: String by nameDelegate

    val timestamps = NodeTimestamps(currentEpochSeconds())

    private val mergedDelegate = dirtyProperty(NodeTimestamps.zero(), ::touchUnedited)
    var merged: ZonedDateTime by mergedDelegate

    private fun touch(edited: Boolean = false) {
        dirtyFlag = true
        val dt = NodeTimestamps.now()
        timestamps.updated = dt
        if (edited) timestamps.edited = dt
    }

    private fun touchEdited() = touch(true)
    private fun touchUnedited() = touch()

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        id = raw["mainId"] as? String ?: id
        (raw["name"] as? String)?.let { nameDelegate.setRaw(it) }
        (raw["timestamps"] as? Map<*, *>)?.let { timestamps.load(it) }
        mergedDelegate.setRaw((raw["lastMerged"] as? String)?.let { NodeTimestamps.strToDt(it) }
            ?: NodeTimestamps.zero())
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("mainId", id)
        put("name", name)
        put("timestamps", timestamps.save(clean))
        put("lastMerged", NodeTimestamps.dtToStr(merged))
    }

    override val dirty: Boolean get() = super.dirty || timestamps.dirty

    override fun toString() = name

    companion object {
        private fun generateId(epochSec: Long): String {
            val randChars = (1..RANDOM_ID_LENGTH).joinToString("") {
                ALPHANUMERIC_CHARS.random().toString()
            }
            return "tag.$randChars.${epochSec.toString(16)}"
        }
    }
}

class NodeLabels : Element() {
    private val labels: MutableMap<String, Label?> = linkedMapOf()

    fun load(raw: Any?) {
        // Custom wire format (list + trailing bool)
        val arr = when (raw) {
            is MutableList<*> -> raw.toMutableList()
            is List<*> -> raw.toMutableList()
            else -> mutableListOf()
        }
        dirtyFlag = if (arr.isNotEmpty() && arr.last() is Boolean) {
            arr.removeAt(arr.lastIndex) as Boolean
        } else {
            false
        }
        labels.clear()
        arr.forEach { m ->
            val mm = m as? Map<*, *> ?: return@forEach
            val id = mm["labelId"] as? String ?: return@forEach
            labels[id] = null
        }
    }

    fun saveWire(clean: Boolean = true): MutableList<Any?> {
        val now = NodeTimestamps.now()
        val ret = labels.map { (id, label) ->
            val deletedTime = if (label == null) now else NodeTimestamps.zero()
            mapOf(
                "labelId" to id,
                "deleted" to NodeTimestamps.dtToStr(deletedTime)
            )
        }.toMutableList<Any?>()
        if (!clean) ret += dirtyFlag else dirtyFlag = false
        return ret
    }

    fun add(label: Label) { labels[label.id] = label; dirtyFlag = true }
    fun remove(label: Label) { if (labels.containsKey(label.id)) labels[label.id] = null; dirtyFlag = true }
    fun get(labelId: String): Label? = labels[labelId]
    fun all(): List<Label> = labels.values.filterNotNull()
}

/* ========== Node hierarchy ========== */

/**
 * Mixin interface for timestamp management in nodes.
 */
interface TimestampsMixin {
    val timestamps: NodeTimestamps
    var dirtyFlagForMixin: Boolean

    fun touch(edited: Boolean = false) {
        dirtyFlagForMixin = true
        val dt = NodeTimestamps.now()
        timestamps.updated = dt
        if (edited) timestamps.edited = dt
    }

    var trashed: Boolean
        get() = timestamps.trashed?.isAfter(NodeTimestamps.zero()) == true
        set(value) {
            timestamps.trashed = if (value) NodeTimestamps.now() else NodeTimestamps.zero()
        }

    var deleted: Boolean
        get() = timestamps.deleted?.isAfter(NodeTimestamps.zero()) == true
        set(value) {
            timestamps.deleted = if (value) NodeTimestamps.now() else null
        }
}

/**
 * Base class for all Google Keep nodes (notes, lists, list items, blobs).
 * Provides hierarchical structure with parent-child relationships.
 */
open class Node(
    idString: String? = null,
    nodeType: NodeType? = null,
    var parentId: String? = null
) : Element(), TimestampsMixin {
    var id: String = idString ?: generateId(Instant.now().toEpochMilli())
    var serverId: String? = null
    var type: NodeType? = nodeType
    private val sortDelegate = dirtyProperty(Random.nextLong(SORT_MIN, SORT_MAX)) { touch() }
    var sort: Long by sortDelegate
    private var version: Long? = null
    protected var textString: String = ""
    private val _children: MutableMap<String, Node> = linkedMapOf()
    override val timestamps: NodeTimestamps = NodeTimestamps(currentEpochSeconds())
    val settings = NodeSettings()
    val annotations = NodeAnnotations()
    var parent: Node? = null
    var moved: Boolean = false

    private fun parseSortValue(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    override var dirtyFlagForMixin: Boolean
        get() = dirtyFlag
        set(v) { dirtyFlag = v }

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        val t = NodeType.fromWire(raw["type"] as String)
        if ((raw["kind"] as? String) != "notes#node")
            Log.w(TAG, "Unknown node kind: ${raw["kind"]}")
        if ("mergeConflict" in raw) throw MergeException()

        id = raw["id"] as? String ?: id
        serverId = raw["serverId"] as? String ?: serverId
        parentId = raw["parentId"] as? String
        parseSortValue(raw["sortValue"])?.let { sortDelegate.setRaw(it) }
        version = (raw["baseVersion"] as? Number)?.toLong() ?: version
        textString = (raw["text"] as? String) ?: textString
        (raw["timestamps"] as? Map<*, *>)?.let { timestamps.load(it) }
        (raw["nodeSettings"] as? Map<*, *>)?.let { settings.load(it) }
        (raw["annotationsGroup"] as? Map<*, *>)?.let { annotations.load(it) }
        type = t
    }

    override fun save(clean: Boolean): MutableMap<String, Any?> {
        val ret = super.save(clean).apply {
            put("id", id)
            put("kind", "notes#node")
            put("type", type!!.wire)
            put("parentId", parentId)
            put("sortValue", sort)
            if (!moved && version != null) put("baseVersion", version)
            put("text", textString)
            serverId?.let { put("serverId", it) }
            put("timestamps", timestamps.save(clean))
            put("nodeSettings", settings.save(clean))
            put("annotationsGroup", annotations.save(clean))
        }
        return ret
    }

    open val text: String get() = textString
    open fun setText(value: String) {
        textString = value
        timestamps.edited = NodeTimestamps.now()
        touch(true)
    }

    val children: List<Node> get() = _children.values.toList()
    fun get(nodeId: String): Node? = _children[nodeId]

    fun append(node: Node, dirty: Boolean = true): Node {
        _children[node.id] = node
        node.parent = this
        if (dirty) touch()
        return node
    }

    fun remove(node: Node, dirty: Boolean = true) {
        if (_children.containsKey(node.id)) {
            _children[node.id]?.parent = null
            _children.remove(node.id)
        }
        if (dirty) touch()
    }

    val isNew: Boolean get() = serverId == null

    override val dirty: Boolean
        get() = super.dirty || timestamps.dirty || annotations.dirty ||
            settings.dirty || children.any { it.dirty }

    companion object {
        private fun generateId(ms: Long): String {
            val r = Random.nextULong().toString(16).padStart(16, '0')
            return "${ms.toString(16)}.$r"
        }
    }
}

object RootId { const val ID = "root" }

class Root : Node(idString = RootId.ID) {
    override val dirty: Boolean get() = false
}

/* ========== Top-level nodes ========== */

/**
 * Base class for top-level note types (Note and ListNode).
 * Provides color, archiving, pinning, labels, and collaboration features.
 */
abstract class TopLevelNode(type: NodeType) : Node(nodeType = type, parentId = RootId.ID) {
    private val colorDelegate = dirtyProperty(ColorValue.White) { touch(true) }
    var color: ColorValue by colorDelegate

    private val archivedDelegate = dirtyProperty(false) { touch() }
    var archived: Boolean by archivedDelegate

    private val pinnedDelegate = dirtyProperty(false) { touch() }
    var pinned: Boolean by pinnedDelegate

    private val titleDelegate = dirtyProperty("") { touch(true) }
    var title: String by titleDelegate

    val labels = NodeLabels()
    val collaborators = NodeCollaborators()

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        colorDelegate.setRaw((raw["color"] as? String)?.let { ColorValue.fromWire(it) }
            ?: ColorValue.White)
        archivedDelegate.setRaw(raw["isArchived"] as? Boolean ?: false)
        pinnedDelegate.setRaw(raw["isPinned"] as? Boolean ?: false)
        titleDelegate.setRaw(raw["title"] as? String ?: "")
        labels.load(raw["labelIds"])

        val roleInfo: List<Map<*, *>> =
            (raw["roleInfo"] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()

        val shareReqs: MutableList<Any?> = when (val sr = raw["shareRequests"]) {
            is MutableList<*> -> sr.map { it }.toMutableList()
            is List<*> -> sr.toMutableList()
            else -> mutableListOf()
        }

        collaborators.load(roleInfo, shareReqs)
        moved = "moved" in raw
    }

    override fun save(clean: Boolean): MutableMap<String, Any?> {
        val ret = super.save(clean).apply {
            put("color", color.wire)
            put("isArchived", archived)
            put("isPinned", pinned)
            put("title", title)
        }
        val labelWire = labels.saveWire(clean)
        if (labelWire.isNotEmpty()) ret["labelIds"] = labelWire

        val (collabs, requests) = collaborators.saveNodeCollaborators(clean)
        ret["collaborators"] = collabs
        if (requests.isNotEmpty()) ret["shareRequests"] = requests
        return ret
    }

    override val dirty: Boolean get() = super.dirty || labels.dirty || collaborators.dirty

    val blobs: List<Blob> get() = children.filterIsInstance<Blob>()
    val images: List<NodeImage> get() = blobs.mapNotNull { it.blob as? NodeImage }
    val drawings: List<NodeDrawing> get() = blobs.mapNotNull { it.blob as? NodeDrawing }
    val audio: List<NodeAudio> get() = blobs.mapNotNull { it.blob as? NodeAudio }

    val url: String get() = "https://keep.google.com/u/0/#${type!!.wire}/$id"
}

/**
 * Represents a standard Google Keep note with title and text content.
 */
class Note : TopLevelNode(NodeType.Note) {
    private fun textItem(): ListItem? = children.firstOrNull { it is ListItem } as? ListItem

    override fun setText(value: String) {
        val node = textItem() ?: ListItem(parentId = id).also { append(it, true) }
        node.setText(value)
        touch(true)
    }

    override val text: String
        get() = textItem()?.text ?: super.text

    override fun toString(): String = "$title\n$text"
}

/**
 * Represents a Google Keep checklist with checkable list items.
 * Supports item ordering, checked/unchecked filtering, and item indentation.
 */
class ListNode : TopLevelNode(NodeType.List) {
    companion object {
        const val SORT_DELTA = 10_000
        fun sortedItems(items: List<ListItem>): List<ListItem> {
            fun keyFor(x: ListItem): List<Long> {
                return if (x.indented) {
                    listOf(x.parentItem!!.sort, x.sort)
                } else {
                    listOf(x.sort)
                }
            }
            val comparator = compareByDescending<ListItem> { keyFor(it)[0] }
                .thenByDescending { keyFor(it).getOrNull(1) ?: Long.MIN_VALUE }
            return items.sortedWith(comparator)
        }
    }


    fun add(
        text: String,
        checked: Boolean = false,
        sort: Any? = null // Int (explicit) or NewListItemPlacementValue
    ): ListItem {
        val node = ListItem(parentId = id, parentServerId = serverId)
        node.checked = checked
        node.setText(text)

        val items = items
        when (sort) {
            is Int -> node.sort = sort.toLong()
            is NewListItemPlacementValue -> if (items.isNotEmpty()) {
                val func: (Long, Long) -> Long
                var delta = SORT_DELTA.toLong()
                if (sort == NewListItemPlacementValue.Bottom) { func = ::min; delta *= -1 }
                else func = ::max
                val boundary = func(items.maxOf { it.sort }, items.minOf { it.sort })
                node.sort = boundary + delta
            }
        }
        append(node, true)
        touch(true)
        return node
    }

    override val text: String
        get() = items.joinToString("\n") { it.toString() }

    fun sortItems(
        key: (ListItem) -> Comparable<*> = { it.text },
        reverse: Boolean = false
    ) {
        val comparator = compareBy<ListItem> { key(it) }
            .let { if (reverse) it.reversed() else it }
        val sorted = filterItems(null).sortedWith(comparator)
        var sortVal = Random.nextLong(SORT_MIN, SORT_MAX)
        for (n in sorted) {
            n.sort = sortVal
            sortVal -= SORT_DELTA
        }
    }

    private fun filterItems(checked: Boolean?): List<ListItem> {
        return children.filterIsInstance<ListItem>()
            .filter { !it.deleted && (checked == null || it.checked == checked) }
    }

    val items: List<ListItem> get() = sortedItems(filterItems(null))
    val checkedItems: List<ListItem> get() = sortedItems(filterItems(true))
    val uncheckedItems: List<ListItem> get() = sortedItems(filterItems(false))

    override fun toString(): String = (listOf(title) + items.map { it.toString() }).joinToString("\n")
}

/* ========== ListItem ========== */

/**
 * Represents a single item in a Google Keep checklist.
 * Supports checked/unchecked state, indentation, and sub-items.
 */
class ListItem(
    parentId: String? = null,
    val parentServerId: String? = null,
    var superListItemId: String? = null
) : Node(nodeType = NodeType.ListItem, parentId = parentId) {

    private val subitems: MutableMap<String, ListItem> = linkedMapOf()
    var parentItem: ListItem? = null
        private set
    var prevSuperListItemId: String? = null
        private set
    private val checkedDelegate = dirtyProperty(false) { touch(true) }
    var checked: Boolean by checkedDelegate

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        prevSuperListItemId = superListItemId
        superListItemId = raw["superListItemId"] as? String
        checkedDelegate.setRaw(raw["checked"] as? Boolean ?: false)
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("parentServerId", parentServerId)
        put("superListItemId", superListItemId)
        put("checked", checked)
    }

    fun add(text: String, checked: Boolean = false, sort: Any? = null): ListItem {
        val listParent = parent as? ListNode ?: throw InvalidException("Item has no parent list")
        val node = listParent.add(text, checked, sort)
        indent(node)
        return node
    }

    fun indent(node: ListItem, dirty: Boolean = true) {
        if (node.subitems.isNotEmpty()) return
        subitems[node.id] = node
        node.superListItemId = this.id
        node.parentItem = this
        if (dirty) node.touch(true)
    }

    fun dedent(node: ListItem, dirty: Boolean = true) {
        if (!subitems.containsKey(node.id)) return
        subitems.remove(node.id)
        node.superListItemId = ""
        node.parentItem = null
        if (dirty) node.touch(true)
    }

    val indented: Boolean get() = parentItem != null
    val subItemsSorted: List<ListItem>
        get() = ListNode.sortedItems(subitems.values.toList())

    override fun toString(): String {
        val indent = if (indented) "  " else ""
        val box = if (checked) "☑" else "☐"
        return "$indent$box $textString"
    }
}

/* ========== Blobs ========== */

open class NodeBlob(private val _type: BlobType) : Element() {
    var blobId: String? = null
    var mediaId: String? = null
    var mimeType: String = ""

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        BlobType.fromWire(raw["type"] as String) // validate type
        blobId = raw["blob_id"] as? String
        mediaId = raw["media_id"] as? String
        mimeType = raw["mimetype"] as? String ?: ""
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("kind", "notes#blob")
        put("type", _type.wire)
        blobId?.let { put("blob_id", it) }
        mediaId?.let { put("media_id", it) }
        put("mimetype", mimeType)
    }
}

class NodeAudio : NodeBlob(BlobType.Audio) {
    var length: Int? = null
        private set

    override fun load(raw: Map<*, *>) {
        super.load(raw); length = (raw["length"] as? Number)?.toInt()
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        length?.let { put("length", it) }
    }
}

class NodeImage : NodeBlob(BlobType.Image) {
    var isUploaded: Boolean = false
        private set
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var byteSize: Long = 0
        private set
    var extractedText: String = ""
        private set
    var extractionStatus: String = ""
        private set

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        isUploaded = (raw["is_uploaded"] as? Boolean) ?: false
        width = (raw["width"] as? Number)?.toInt() ?: 0
        height = (raw["height"] as? Number)?.toInt() ?: 0
        byteSize = (raw["byte_size"] as? Number)?.toLong() ?: 0
        extractedText = raw["extracted_text"] as? String ?: ""
        extractionStatus = raw["extraction_status"] as? String ?: ""
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("width", width)
        put("height", height)
        put("byte_size", byteSize)
        put("extracted_text", extractedText)
        put("extraction_status", extractionStatus)
    }

    val url: String get() = throw NotImplementedError("URL generation depends on external service")
}

class NodeDrawingInfo : Element() {
    var drawingId: String = ""
    val snapshot = NodeImage()
    private var snapshotFingerprint: String = ""
    private var thumbnailGeneratedTime: ZonedDateTime = NodeTimestamps.zero()
    private var inkHash: String = ""
    private var snapshotProtoFprint: String = ""

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        drawingId = raw["drawingId"] as? String ?: drawingId
        (raw["snapshotData"] as? Map<*, *>)?.let { snapshot.load(it) }
        snapshotFingerprint = raw["snapshotFingerprint"] as? String ?: snapshotFingerprint
        thumbnailGeneratedTime = (raw["thumbnailGeneratedTime"] as? String)
            ?.let { NodeTimestamps.strToDt(it) } ?: NodeTimestamps.zero()
        inkHash = raw["inkHash"] as? String ?: ""
        snapshotProtoFprint = raw["snapshotProtoFprint"] as? String ?: snapshotProtoFprint
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("drawingId", drawingId)
        put("snapshotData", snapshot.save(clean))
        put("snapshotFingerprint", snapshotFingerprint)
        put("thumbnailGeneratedTime", NodeTimestamps.dtToStr(thumbnailGeneratedTime))
        put("inkHash", inkHash)
        put("snapshotProtoFprint", snapshotProtoFprint)
    }
}

class NodeDrawing : NodeBlob(BlobType.Drawing) {
    private var extractedText: String = ""
    private var extractionStatus: String = ""
    var drawingInfo: NodeDrawingInfo? = null
        private set

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        extractedText = raw["extracted_text"] as? String ?: ""
        extractionStatus = raw["extraction_status"] as? String ?: ""
        drawingInfo = (raw["drawingInfo"] as? Map<*, *>)?.let {
            NodeDrawingInfo().apply { load(it) }
        }
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        put("extracted_text", extractedText)
        put("extraction_status", extractionStatus)
        drawingInfo?.let { put("drawingInfo", it.save(clean)) }
    }

    fun getExtractedText(): String = drawingInfo?.snapshot?.extractedText ?: ""
}

class Blob(parentId: String? = null) : Node(nodeType = NodeType.Blob, parentId = parentId) {
    var blob: NodeBlob? = null

    override fun load(raw: Map<*, *>) {
        super.load(raw)
        blob = fromJson(raw["blob"] as? Map<*, *>)
    }

    override fun save(clean: Boolean) = super.save(clean).apply {
        blob?.let { put("blob", it.save(clean)) }
    }

    companion object {
        fun fromJson(raw: Map<*, *>?): NodeBlob? {
            raw ?: return null
            val t = raw["type"] as? String ?: return null
            val blobType = BlobType.fromWireOrNull(t)
            if (blobType == null) {
                Log.w(TAG, "Unknown blob type: $t")
                if (DEBUG_LOGGING) {
                    Log.d(TAG, "Skipping blob payload: $raw")
                }
                return null
            }
            return when (blobType) {
                BlobType.Audio -> NodeAudio()
                BlobType.Image -> NodeImage()
                BlobType.Drawing -> NodeDrawing()
            }.apply { load(raw) }
        }
    }
}

/* ========== Node factory ========== */

fun nodeFromJson(raw: Map<*, *>): Node? {
    val t = (raw["type"] as? String) ?: return null
    val nodeType = NodeType.fromWireOrNull(t)
    if (nodeType == null) {
        Log.w(TAG, "Unknown node type: $t")
        if (DEBUG_LOGGING) {
            Log.d(TAG, "Skipping node payload: $raw")
        }
        return null
    }
    val cls = when (nodeType) {
        NodeType.Note -> Note()
        NodeType.List -> ListNode()
        NodeType.ListItem -> ListItem()
        NodeType.Blob -> Blob()
    }
    cls.load(raw)
    return cls
}
