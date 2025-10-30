package com.kindler

import io.github.rukins.gpsoauth.Auth
import io.github.rukins.gpsoauth.exception.AuthError
import io.github.rukins.gpsoauth.model.AccessToken
import io.github.rukins.gpsoauth.model.AccessTokenRequestParams
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GKeepSync {
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

    fun addNotes(titles: String[], contents: String[]) {
        val accessToken = getAccessToken()


    }
}