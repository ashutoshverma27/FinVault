package com.finvault.app.email

import android.content.Context
import android.util.Base64
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.sms.SmsIngestion
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reads bank / card transaction alert emails from Gmail (read-only) and feeds
 * them through the same parser, de-duplication and auto-posting as SMS.
 * Nothing leaves the device except the Gmail API calls themselves.
 */
object GmailSync {

    const val SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private const val PREFS = "finvault_gmail"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_LAST_SYNC = "last_sync_seconds"
    private const val KEY_NEEDS_RECONNECT = "needs_reconnect"
    private const val FIRST_SYNC_DAYS = 180
    private const val MAX_MESSAGES_PER_RUN = 500

    private const val QUERY =
        "(debited OR credited OR spent OR \"has been used\" OR refund OR refunded OR \"transaction alert\" OR txn OR \"payment received\" OR UPI) " +
                "-category:promotions -category:social"

    val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    fun isConnected(context: Context) = prefs(context).getBoolean(KEY_CONNECTED, false)
    fun needsReconnect(context: Context) = prefs(context).getBoolean(KEY_NEEDS_RECONNECT, false)
    fun lastSyncSeconds(context: Context) = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun markConnected(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONNECTED, true).putBoolean(KEY_NEEDS_RECONNECT, false).apply()
    }

    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Access token without UI, or null if the user has to approve again. */
    private suspend fun silentToken(context: Context): String? {
        val result: AuthorizationResult = Identity.getAuthorizationClient(context).authorize(request).await()
        if (result.hasResolution()) {
            prefs(context).edit().putBoolean(KEY_NEEDS_RECONNECT, true).apply()
            return null
        }
        return result.accessToken
    }

    /** Returns the number of new transaction emails stored. */
    suspend fun sync(context: Context): Int = withContext(Dispatchers.IO) {
        if (!isConnected(context)) return@withContext 0
        val token = silentToken(context) ?: return@withContext 0
        val database = FinVaultDatabase.getInstance(context)
        val startedAt = System.currentTimeMillis() / 1000

        val last = lastSyncSeconds(context)
        // Overlap a day so late-delivered mail isn't missed; duplicates are filtered.
        val window = if (last > 0) "after:${last - 86_400}" else "newer_than:${FIRST_SYNC_DAYS}d"
        val ids = listMessageIds(token, "$QUERY $window")

        var stored = 0
        ids.forEach { id ->
            val message = runCatching { getMessage(token, id) }.getOrNull() ?: return@forEach
            val observation = SmsIngestion.buildObservation(
                sender = message.sender, body = message.text, receivedAt = message.receivedAt
            ) ?: return@forEach
            if (SmsIngestion.insertIfNew(database, observation, message.text)) stored++
        }
        FinVaultRepository(database).autoPostPendingSms()
        prefs(context).edit().putLong(KEY_LAST_SYNC, startedAt).putBoolean(KEY_NEEDS_RECONNECT, false).apply()
        stored
    }

    private data class Mail(val sender: String, val text: String, val receivedAt: Long)

    private fun listMessageIds(token: String, query: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=100&q=" +
                    URLEncoder.encode(query, "UTF-8") + (pageToken?.let { "&pageToken=$it" } ?: "")
            val json = JSONObject(get(token, url))
            val messages = json.optJSONArray("messages")
            for (i in 0 until (messages?.length() ?: 0)) ids += messages!!.getJSONObject(i).getString("id")
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null && ids.size < MAX_MESSAGES_PER_RUN)
        return ids.take(MAX_MESSAGES_PER_RUN)
    }

    private fun getMessage(token: String, id: String): Mail {
        val json = JSONObject(get(token, "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full"))
        val payload = json.getJSONObject("payload")
        val headers = payload.optJSONArray("headers")
        var from = ""
        var subject = ""
        for (i in 0 until (headers?.length() ?: 0)) {
            val h = headers!!.getJSONObject(i)
            when (h.optString("name").lowercase()) {
                "from" -> from = h.optString("value")
                "subject" -> subject = h.optString("value")
            }
        }
        val plain = StringBuilder()
        val html = StringBuilder()
        collectText(payload, plain, html)
        val body = if (plain.isNotBlank()) plain.toString() else EmailText.htmlToText(html.toString())
        return Mail(
            sender = EmailText.sender(from),
            text = EmailText.clean(subject, body.ifBlank { json.optString("snippet") }),
            receivedAt = json.optString("internalDate").toLongOrNull() ?: System.currentTimeMillis()
        )
    }

    private fun collectText(part: JSONObject, plain: StringBuilder, html: StringBuilder) {
        val mime = part.optString("mimeType")
        val data = part.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isNotEmpty()) {
            val text = String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            if (mime == "text/plain") plain.append(text).append('\n')
            if (mime == "text/html") html.append(text).append('\n')
        }
        val parts = part.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) collectText(parts.getJSONObject(i), plain, html)
    }

    private fun get(token: String, url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            check(connection.responseCode == 200) { "Gmail API error ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
