package com.kindler

import android.util.Log
import io.github.rukins.gpsoauth.Auth
import io.github.rukins.gpsoauth.exception.AuthError
import io.github.rukins.gpsoauth.model.AccessToken
import io.github.rukins.gpsoauth.model.AccessTokenRequestParams
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

class GKeepSync {
    private val keepApi = KeepAPI()
    private val mediaApi = MediaAPI()

    private var keepVersion: String? = null
    private val labels = mutableMapOf<String, Label>()
    private val nodes = mutableMapOf<String, Node>()
    private val sidMap = mutableMapOf<String, Node>()

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

    companion object {
        private const val GOOGLE_KEEP_SCOPES =
            "oauth2:https://www.googleapis.com/auth/memento https://www.googleapis.com/auth/reminders"
        private const val GOOGLE_KEEP_APP = "com.google.android.keep"
    }

    @Throws(AuthError::class)
    private fun getAccessToken(): AccessToken {
        val auth = Auth()
        val accessTokenParams = AccessTokenRequestParams
            .withDefaultValues()
            .masterToken(BuildConfig.MASTER_TOKEN)
            .email(BuildConfig.GOOGLE_ACCOUNT_EMAIL)
            .androidId(BuildConfig.GOOGLE_ANDROID_ID)
            .scopes(GOOGLE_KEEP_SCOPES)
            .app(GOOGLE_KEEP_APP)
            .clientSig(BuildConfig.GOOGLE_CLIENT_SIGNATURE)
            .build()

        return auth.getAccessToken(accessTokenParams)
    }
}

class APIAuth(private val scopes: String) {
    companion object {
        private const val GOOGLE_KEEP_APP = "com.google.android.keep"
    }

    private var authToken: String? = null
    private var email: String? = null
    private var masterToken: String? = null
    private var androidId: String? = null

    @Throws(AuthError::class, LoginException::class)
    fun load(email: String, masterToken: String, deviceId: String): Boolean {
        this.email = email
        this.masterToken = masterToken
        this.androidId = deviceId
        this.authToken = null

        refresh()
        return true
    }

    @Throws(AuthError::class, LoginException::class)
    fun refresh(): String {
        val email = this.email ?: throw LoginException("Email is not set")
        val masterToken = this.masterToken ?: throw LoginException("Master token is not set")
        val androidId = this.androidId ?: throw LoginException("Device id is not set")

        val accessTokenParams = AccessTokenRequestParams
            .withDefaultValues()
            .masterToken(masterToken)
            .email(email)
            .androidId(androidId)
            .scopes(scopes)
            .app(GOOGLE_KEEP_APP)
            .clientSig(BuildConfig.GOOGLE_CLIENT_SIGNATURE)
            .build()

        val accessToken: AccessToken = Auth().getAccessToken(accessTokenParams)
        val token = accessToken.accessToken

        if (token.isNullOrBlank()) {
            val errorMessage = accessToken.issueAdvice ?: "Unable to refresh OAuth token"
            throw LoginException(errorMessage)
        }

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
        internal const val RETRY_COUNT = 2
        internal const val INITIAL_DELAY_SECONDS = 2L
        internal const val MAX_DELAY_SECONDS = 60L
        private const val USER_AGENT = "x-gkeepapi/${BuildConfig.VERSION_NAME} (https://github.com/kiwiz/gkeepapi)"
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_USER_AGENT = "User-Agent"
        internal const val HTTP_TOO_MANY_REQUESTS = 429
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient()

    fun getAuth(): APIAuth? = auth

    fun setAuth(auth: APIAuth) {
        this.auth = auth
    }

    @Throws(APIException::class, APIAuth.LoginException::class, AuthError::class, IOException::class)
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

            if (code == HTTP_TOO_MANY_REQUESTS) {
                TimeUnit.SECONDS.sleep(delaySeconds)
                delaySeconds = min(delaySeconds * 2, MAX_DELAY_SECONDS)
                continue
            }

            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                if (attempts >= RETRY_COUNT) {
                    throw APIException(code, error)
                }

                val apiAuth = auth ?: throw APIAuth.LoginException("Not logged in")
                apiAuth.refresh()
                attempts += 1
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

        val method = request.method.uppercase()
        val requestBuilder = Request.Builder()
            .url(request.url)
            .headers(headers)

        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> {
                val body = request.json?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: throw IllegalArgumentException("POST requests require a JSON body")
                requestBuilder.post(body)
            }
            else -> requestBuilder.method(method, null)
        }

        val callClient = if (request.allowRedirects) {
            client
        } else {
            client.newBuilder()
                .followRedirects(false)
                .build()
        }

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

    @Throws(APIException::class, APIAuth.LoginException::class, AuthError::class, IOException::class)
    fun changes(
        targetVersion: String? = null,
        nodes: List<Any?> = emptyList(),
        labels: List<Any?> = emptyList()
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
                method = "POST",
                json = params
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

    private fun toJsonArray(items: List<Any?>): JSONArray {
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

    private fun currentEpochSeconds(): Double = System.currentTimeMillis() / 1000.0

    private fun generateSessionId(epochSeconds: Double): String {
        val timestampMillis = (epochSeconds * 1000).toLong()
        val randomComponent = Random.nextLong(1_000_000_000L, 10_000_000_000L)
        return "s--${timestampMillis}--${randomComponent}"
    }
}

class MediaAPI(auth: APIAuth? = null) : API(API_URL, auth) {
    companion object {
        private const val API_URL = "https://keep.google.com/media/v2/"
    }

    @Throws(APIAuth.LoginException::class, AuthError::class, IOException::class)
    fun get(blob: Blob): String {
        val parentServerId = blob.parent?.serverId
            ?: throw IllegalArgumentException("Blob parent must have a serverId")
        val blobServerId = blob.serverId
            ?: throw IllegalArgumentException("Blob must have a serverId")

        val path = buildString {
            append(parentServerId)
            append('/')
            append(blobServerId)

            val nodeBlob = blob.blob
            if (nodeBlob is NodeDrawing) {
                val drawingId = nodeBlob.drawingInfo?.drawingId
                if (!drawingId.isNullOrBlank()) {
                    append('/')
                    append(drawingId)
                }
            }
        }

        return sendRaw(
            APIRequest(
                url = baseUrl + path,
                method = "GET",
                allowRedirects = false
            )
        ).use { response ->
            response.header("location")
                ?: throw IOException("Missing redirect location header")
        }
    }
}

data class APIRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>? = null,
    val json: JSONObject? = null,
    val allowRedirects: Boolean = true
)

class APIException(code: Int, val error: JSONObject) : IOException("API error $code: $error")
