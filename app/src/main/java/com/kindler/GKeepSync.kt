package com.kindler

import android.util.Log
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import svarzee.gps.gpsoauth.Gpsoauth
import svarzee.gps.gpsoauth.Gpsoauth.TokenRequestFailed
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class GKeepSync {
    private val keepApi = KeepAPI()
    private var keepVersion: String? = null
    private val labels = mutableMapOf<String, Label>()
    private val nodes = mutableMapOf<String, Node>()
    private val sidMap = mutableMapOf<String, String>()

    init {
        clear()
    }

    private fun clear() {
        keepVersion = null
        labels.clear()
        nodes.clear()
        sidMap.clear()

        val rootNode = Root()
        nodes[RootId.ID] = rootNode
    }

    private fun refreshServerIdIndex() {
        sidMap.clear()
        nodes.values.forEach { node ->
            node.serverId?.let { sidMap[it] = node.id }
        }
    }

    private fun verifyNodeReachability() {
        val foundIds = mutableSetOf<String>()
        val stack = ArrayDeque<Node>()
        nodes[RootId.ID]?.let { stack.addLast(it) }

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!foundIds.add(node.id)) continue
            node.children.forEach { child -> stack.addLast(child) }
        }

        nodes.keys.forEach { nodeId ->
            if (nodeId !in foundIds) {
                Log.e(LOG_TAG, "Dangling node: $nodeId")
            }
        }

        foundIds.forEach { nodeId ->
            if (nodeId !in nodes) {
                Log.e(LOG_TAG, "Unregistered node: $nodeId")
            }
        }
    }

    private fun allNodes(): Collection<Node> = nodes.values

    private fun findDirtyNodes(): List<Node> {
        // Ensure the internal map has all child nodes referenced by parents.
        val currentNodes = nodes.values.toList()
        currentNodes.forEach { node ->
            node.children.forEach { child ->
                if (child.id !in nodes) {
                    nodes[child.id] = child
                }
            }
        }

        return nodes.values.filter { it.dirty }
    }

    private fun parseUserInfo(raw: JSONObject?) {
        val incomingLabels = mutableMapOf<String, Label>()
        val labelsArray = raw?.optJSONArray("labels")

        if (labelsArray != null) {
            for (index in 0 until labelsArray.length()) {
                val labelObject = labelsArray.optJSONObject(index) ?: continue
                val mainId = labelObject.optString("mainId")
                if (mainId.isBlank()) continue

                val existingLabel = labels.remove(mainId)
                val label = existingLabel ?: Label()
                if (existingLabel != null) {
                    Log.d(LOG_TAG, "Updated label: $mainId")
                } else {
                    Log.d(LOG_TAG, "Created label: $mainId")
                }

                label.load(jsonObjectToMap(labelObject))
                incomingLabels[mainId] = label
            }
        }

        labels.keys.forEach { labelId ->
            Log.d(LOG_TAG, "Deleted label: $labelId")
        }

        labels.clear()
        labels.putAll(incomingLabels)
    }

    private fun parseNodes(raw: JSONArray?) {
        if (raw == null) return

        val createdNodes = mutableListOf<Node>()
        val deletedNodes = mutableListOf<Node>()
        val listItemNodes = mutableListOf<ListItem>()

        for (index in 0 until raw.length()) {
            val rawNodeJson = raw.optJSONObject(index) ?: continue
            val rawNode = jsonObjectToMap(rawNodeJson)
            val nodeId = rawNode["id"] as? String ?: continue

            val existingNode = nodes[nodeId]
            var node: Node? = existingNode

            if (existingNode != null) {
                if (rawNode.containsKey("parentId")) {
                    existingNode.load(rawNode)
                    existingNode.serverId?.let { sidMap[it] = existingNode.id }
                    Log.d(LOG_TAG, "Updated node: $nodeId")
                } else {
                    deletedNodes += existingNode
                }
            } else {
                val createdNode = nodeFromPayload(rawNode)
                if (createdNode == null) {
                    Log.d(LOG_TAG, "Discarded unknown node")
                } else {
                    nodes[nodeId] = createdNode
                    createdNode.serverId?.let { sidMap[it] = createdNode.id }
                    createdNodes += createdNode
                    node = createdNode
                    Log.d(LOG_TAG, "Created node: $nodeId")
                }
            }

            val listItem = node as? ListItem
            if (listItem != null) {
                listItemNodes += listItem
            }
        }

        listItemNodes.forEach { item ->
            val previous = item.prevSuperListItemId
            val current = item.superListItemId
            if (previous == current) return@forEach

            if (!previous.isNullOrEmpty()) {
                (nodes[previous] as? ListItem)?.dedent(item, false)
            }
            if (!current.isNullOrEmpty()) {
                (nodes[current] as? ListItem)?.indent(item, false)
            }
        }

        createdNodes.forEach { node ->
            Log.d(LOG_TAG, "Attached node: ${'$'}{node.id} to ${'$'}{node.parentId}")
            val parentNode = node.parentId?.let { nodes[it] }
            parentNode?.append(node, false)
        }

        deletedNodes.forEach { node ->
            node.parent?.remove(node, false)
            nodes.remove(node.id)
            node.serverId?.let { sidMap.remove(it) }
            Log.d(LOG_TAG, "Deleted node: ${'$'}{node.id}")
        }

        allNodes().forEach { node ->
            val topLevelNode = node as? TopLevelNode ?: return@forEach
            topLevelNode.labels.hydrate { labelId -> labels[labelId] }
        }
    }

    @Throws(
        APIException::class,
        APIAuth.LoginException::class,
        TokenRequestFailed::class,
        IOException::class,
        ResyncRequiredException::class,
        UpgradeRecommendedException::class
    )
    fun syncNotes() {
        while (true) {
            Log.d(LOG_TAG, "Starting keep sync: $keepVersion")

            val dirtyNodeRefs = findDirtyNodes()
            val dirtyNodes = dirtyNodeRefs.map { it.save() }
            val dirtyLabels = labels.values.filter { it.dirty }
            val labelsUpdated = dirtyLabels.isNotEmpty()
            val labelsPayload = if (labelsUpdated) {
                labels.values.map { it.save() }
            } else {
                emptyList()
            }

            val changes = keepApi.changes(
                targetVersion = keepVersion,
                nodes = dirtyNodes,
                labels = labelsPayload
            )

            if (changes.optBoolean("forceFullResync")) {
                throw ResyncRequiredException("Full resync required")
            }

            if (changes.optBoolean("upgradeRecommended")) {
                throw UpgradeRecommendedException("Upgrade recommended")
            }

            changes.optJSONObject("userInfo")?.let { parseUserInfo(it) }
            changes.optJSONArray("nodes")?.let { parseNodes(it) }

            keepVersion = changes.getString("toVersion")
            Log.d(LOG_TAG, "Finishing sync: $keepVersion")
            dirtyNodeRefs.forEach { it.clearDirty() }
            dirtyLabels.forEach { it.clearDirty() }

            if (!changes.optBoolean("truncated")) {
                break
            }
        }
    }

    @Throws(
        APIException::class,
        APIAuth.LoginException::class,
        TokenRequestFailed::class,
        IOException::class,
        ResyncRequiredException::class,
        UpgradeRecommendedException::class
    )
    fun sync(resync: Boolean = false) {
        if (resync) {
            clear()
        }

        syncNotes()

        if (DEBUG) {
            refreshServerIdIndex()
            verifyNodeReachability()
        }
    }

    @Throws(
        APIException::class,
        APIAuth.LoginException::class,
        TokenRequestFailed::class,
        IOException::class,
        ResyncRequiredException::class,
        UpgradeRecommendedException::class
    )
    fun authenticate(
        email: String,
        masterToken: String,
        state: String? = null,
        sync: Boolean = true,
        androidId: String? = null
    ) {
        val auth = APIAuth(GOOGLE_KEEP_SCOPES)
        auth.load(email, masterToken, androidId?: BuildConfig.GOOGLE_ANDROID_ID)
        load(auth, state, sync)
    }

    @Throws(
        APIException::class,
        APIAuth.LoginException::class,
        TokenRequestFailed::class,
        IOException::class,
        ResyncRequiredException::class,
        UpgradeRecommendedException::class
    )
    fun load(auth: APIAuth, state: String? = null, sync: Boolean = true) {
        keepApi.setAuth(auth)

        state?.let { restore(it) }
        if (sync) {
            sync()
        }
    }

    /**
     * Restore a previously serialized state of labels, nodes, and Keep version.
     */
    fun restore(stateJson: String) {
        clear()

        val state = JSONObject(stateJson)
        val labelsArray = toJsonArray(state.opt("labels"), "labels")
        val nodesArray = toJsonArray(state.opt("nodes"), "nodes")

        parseUserInfo(JSONObject().apply { put("labels", labelsArray) })
        parseNodes(nodesArray)

        val versionValue = (state.opt("keep_version") ?: state.opt("keepVersion"))
            ?: throw IllegalArgumentException("State missing keep_version")
        keepVersion = when (versionValue) {
            JSONObject.NULL -> null
            is String -> versionValue
            else -> throw IllegalArgumentException("keep_version must be a String")
        }
    }

    /**
     * Serialize Keep state for persistence.
     */
    fun dump(): String {
        val rootNode = nodes[RootId.ID]
        val orderedNodes = mutableListOf<Node>()

        rootNode?.children?.forEach { node ->
            orderedNodes += node
            node.children.forEach { child -> orderedNodes += child }
        }

        val serializedNodes = orderedNodes.map { it.save(includeDirty = true) }
        val serializedLabels = labels.values.map { it.save(includeDirty = true) }

        val payload = JSONObject()
        payload.put("keep_version", keepVersion ?: JSONObject.NULL)
        payload.put("labels", JSONArray(serializedLabels))
        payload.put("nodes", JSONArray(serializedNodes))
        return payload.toString()
    }

    /**
     * Retrieve a top-level note or list by its local or server ID.
     */
    fun get(nodeId: String): TopLevelNode? {
        val rootNode = nodes[RootId.ID] ?: return null
        val directMatch = rootNode.get(nodeId) as? TopLevelNode
        if (directMatch != null) return directMatch

        val localId = sidMap[nodeId] ?: return null
        return rootNode.get(localId) as? TopLevelNode
    }

    /**
     * Locate a label by name.
     */
    fun findLabel(name: String): Label? {
        val target = name.lowercase()
        return labels.values.firstOrNull { !it.deleted && it.name.lowercase() == target }
    }

    /**
     * Create a new note and register it for syncing.
     */
    fun createNote(title: String? = null, text: String? = null, id: String? = null): Note {
        val node = Note()
        if (id != null) {
            node.id = id
        }
        if (title != null) {
            node.title = title
        }
        if (text != null) {
            node.setText(text)
        }
        add(node)
        return node
    }

    /**
     * Create a new label.
     *
     * @throws LabelException if a label with the same name already exists.
     */
    fun createLabel(name: String): Label {
        if (findLabel(name) != null) {
            throw LabelException("Label exists")
        }
        val label = Label()
        label.name = name
        labels[label.id] = label
        return label
    }

    /**
     * Delete an existing label by ID and detach it from all notes.
     */
    fun deleteLabel(labelId: String) {
        val label = labels[labelId] ?: return
        label.deleted = true
        nodes.values.forEach { node ->
            val topLevelNode = node as? TopLevelNode ?: return@forEach
            topLevelNode.labels.remove(label)
        }
    }

    /**
     * Register a top-level node for syncing.
     */
    @Throws(InvalidException::class)
    fun add(node: Node) {
        if (node.parentId != RootId.ID) {
            throw InvalidException("Not a top level node")
        }

        nodes[node.id] = node
        val parentNode = nodes[node.parentId]
            ?: throw InvalidException("Parent node not found")
        parentNode.append(node, false)
    }

    private fun jsonObjectToMap(jsonObject: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = when (val rawValue = jsonObject.get(key)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(rawValue)
                is JSONArray -> jsonArrayToList(rawValue)
                else -> rawValue
            }
            map[key] = value
        }
        return map
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until array.length()) {
            val value = when (val entry = array.get(i)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(entry)
                is JSONArray -> jsonArrayToList(entry)
                else -> entry
            }
            list += value
        }
        return list
    }

    private fun toJsonArray(value: Any?, fieldName: String): JSONArray {
        if (value == null) {
            throw IllegalArgumentException("State missing $fieldName")
        }

        return when (value) {
            is JSONArray -> value
            is Collection<*> -> JSONArray(value)
            is Array<*> -> JSONArray(value.toList())
            is String -> try {
                JSONArray(value)
            } catch (error: JSONException) {
                throw IllegalArgumentException("State field $fieldName must be a JSON array string", error)
            }
            else -> throw IllegalArgumentException("Unsupported type for $fieldName: ${value::class.java.simpleName}")
        }
    }

    companion object {
        private const val GOOGLE_KEEP_SCOPES =
            "oauth2:https://www.googleapis.com/auth/memento https://www.googleapis.com/auth/reminders"
        private const val GOOGLE_KEEP_APP = "com.google.android.keep"
        private const val LOG_TAG = "GKeepSync"
    }
}

class APIAuth(private val scopes: String) {
    companion object {
        private const val GOOGLE_KEEP_APP = "com.google.android.keep"
        private val GPSOAUTH_CONNECTION_SPEC = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .cipherSuites(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
                CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA
            )
            .tlsVersions(TlsVersion.TLS_1_2)
            .build()

        private fun createGpsoauthHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectionSpecs(listOf(GPSOAUTH_CONNECTION_SPEC))
                .build()
        }
    }

    private val gpsoauth = Gpsoauth(createGpsoauthHttpClient())
    private var authToken: String? = null
    private var email: String? = null
    private var masterToken: String? = null
    private var androidId: String? = null

    @Throws(TokenRequestFailed::class, LoginException::class)
    fun load(email: String, masterToken: String, androidId: String) {
        this.email = email
        this.masterToken = masterToken
        this.androidId = androidId
        this.authToken = null

        refresh()
    }

    @Throws(TokenRequestFailed::class, LoginException::class)
    fun refresh(): String {
        val email = this.email ?: throw LoginException("Email is not set")
        val masterToken = this.masterToken ?: throw LoginException("Master token is not set")
        val androidId = this.androidId ?: throw LoginException("Device id is not set")

        val accessToken = gpsoauth.performOAuthForToken(
            email,
            masterToken,
            androidId,
            scopes,
            GOOGLE_KEEP_APP,
            BuildConfig.GOOGLE_CLIENT_SIGNATURE
        )
        val token = accessToken.token

        authToken = token
        return token
    }

    fun getAuthToken(): String? = authToken

    class LoginException(message: String) : IOException(message)
}

open class API(
    protected val baseUrl: String,
    private var auth: APIAuth? = null
) {
    companion object {
        internal const val RETRY_COUNT = 4
        internal const val INITIAL_DELAY_SECONDS = 2L
        internal const val MAX_DELAY_SECONDS = 60L
        private const val USER_AGENT = "x-gkeepapi/${BuildConfig.VERSION_NAME} (https://github.com/kiwiz/gkeepapi)"
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_USER_AGENT = "User-Agent"
        internal const val HTTP_TOO_MANY_REQUESTS = 429
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient()
    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .build()
    }

    fun getAuth(): APIAuth? = auth

    fun setAuth(auth: APIAuth) {
        this.auth = auth
    }

    @Throws(APIException::class, APIAuth.LoginException::class, TokenRequestFailed::class, IOException::class)
    fun send(request: APIRequest): JSONObject {
        var attempts = 0
        var delaySeconds = INITIAL_DELAY_SECONDS

        while (true) {
            val response = sendRaw(request)
            val payload = response.use { resp ->
                val bodyString = resp.body?.string().orEmpty()
                if (bodyString.isBlank()) {
                    JSONObject()
                } else {
                    try {
                        JSONObject(bodyString)
                    } catch (error: JSONException) {
                        throw IOException("Failed to parse JSON response", error)
                    }
                }
            }

            if (!payload.has("error")) {
                return payload
            }

            val error = payload.getJSONObject("error")
            val code = error.getInt("code")
            attempts += 1

            if (attempts > RETRY_COUNT) {
                throw APIException(code, error)
            }

            if (code == HTTP_TOO_MANY_REQUESTS) {
                TimeUnit.SECONDS.sleep(delaySeconds)
                delaySeconds = min(delaySeconds * 2, MAX_DELAY_SECONDS)
                continue
            }

            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val apiAuth = auth ?: throw APIAuth.LoginException("Not logged in")
                apiAuth.refresh()
                continue
            }

            throw APIException(code, error)
        }
    }

    @Throws(APIAuth.LoginException::class, IOException::class)
    protected fun sendRaw(request: APIRequest): Response {
        val apiAuth = auth ?: throw APIAuth.LoginException("Not logged in")
        val authToken = apiAuth.getAuthToken() ?: throw APIAuth.LoginException("Not logged in")

        val headers = Headers.Builder().apply {
            set(HEADER_USER_AGENT, USER_AGENT)
            request.headers?.forEach { (key, value) -> set(key, value) }
            set(HEADER_AUTHORIZATION, "OAuth $authToken")
        }.build()

        val method = request.method
        val requestBuilder = Request.Builder()
            .url(request.url)
            .headers(headers)

        when (method) {
            HttpMethod.Get -> requestBuilder.get()
            is HttpMethod.Post -> {
                val body = method.body.toString().toRequestBody(JSON_MEDIA_TYPE)
                requestBuilder.post(body)
            }
        }

        val callClient = if (request.allowRedirects) client else noRedirectClient

        return callClient.newCall(requestBuilder.build()).execute()
    }

}

class KeepAPI(auth: APIAuth? = null) : API(API_URL, auth) {
    companion object {
        private const val API_URL = "https://www.googleapis.com/notes/v1/"
        private const val LOG_TAG = "KeepAPI"
        private val CAPABILITIES = listOf(
            "NC",
            "PI",
            "LB",
            "AN",
            "SH",
            "DR",
            "TR",
            "IN",
            "SNB",
            "MI",
            "CO"
        )
    }

    private val sessionId: String = generateSessionId(currentEpochSeconds())

    @Throws(APIException::class, APIAuth.LoginException::class, TokenRequestFailed::class, IOException::class)
    fun changes(
        targetVersion: String? = null,
        nodes: List<Map<String, Any?>> = emptyList(),
        labels: List<Map<String, Any?>> = emptyList()
    ): JSONObject {
        val params = JSONObject().apply {
            put("nodes", toJsonArray(nodes))
            put("clientTimestamp", NodeTimestamps.dtToStr(NodeTimestamps.now()))
            put("requestHeader", buildRequestHeader())
            targetVersion?.let { put("targetVersion", it) }
            if (labels.isNotEmpty()) {
                put("userInfo", JSONObject().apply { put("labels", toJsonArray(labels)) })
            }
        }

        Log.d(LOG_TAG, "Syncing ${'$'}{labels.size} labels and ${'$'}{nodes.size} nodes")

        return send(
            APIRequest(
                url = baseUrl + "changes",
                method = HttpMethod.Post(params)
            )
        )
    }

    private fun buildRequestHeader(): JSONObject {
        val capabilities = JSONArray()
        CAPABILITIES.forEach { capability ->
            capabilities.put(JSONObject().put("type", capability))
        }

        return JSONObject().apply {
            put("clientSessionId", sessionId)
            put("clientPlatform", "ANDROID")
            put("clientVersion", JSONObject().apply {
                put("major", "9")
                put("minor", "9")
                put("build", "9")
                put("revision", "9")
            })
            put("capabilities", capabilities)
        }
    }

    private fun toJsonArray(items: Collection<*>): JSONArray {
        val array = JSONArray()
        items.forEach { item -> array.put(toJsonValue(item)) }
        return array
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject -> value
            is JSONArray -> value
            is Map<*, *> -> JSONObject(value)
            is Collection<*> -> toJsonArray(value.toList())
            is Array<*> -> toJsonArray(value.toList())
            else -> value
        }
    }

    private fun generateSessionId(epochSeconds: Double): String {
        val timestampMillis = (epochSeconds * 1000).toLong()
        val randomComponent = Random.nextLong(1_000_000_000L, 10_000_000_000L)
        return "s--${timestampMillis}--${randomComponent}"
    }
}

sealed class HttpMethod(val verb: String) {
    object Get : HttpMethod("GET")
    data class Post(val body: JSONObject) : HttpMethod("POST")
}

data class APIRequest(
    val url: String,
    val method: HttpMethod,
    val headers: Map<String, String>? = null,
    val allowRedirects: Boolean = true
)

class APIException(code: Int, error: JSONObject) : IOException("API error $code: $error")

class ResyncRequiredException(message: String) : IOException(message)

class UpgradeRecommendedException(message: String) : IOException(message)
