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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val PAGE_SIZE = 100
    private const val PAGES_PER_RUN = 3
    private const val PARALLEL_FETCHES = 4
    private const val KEY_BACKLOG_QUERY = "backlog_query"
    private const val KEY_BACKLOG_STARTED = "backlog_started_seconds"
    private const val KEY_PAGE_TOKEN = "page_token"

    private const val QUERY =
        "(debited OR credited OR spent OR \"has been used\" OR refund OR refunded OR \"transaction alert\" OR txn OR \"payment received\" OR UPI) " +
                "-category:promotions -category:social"

    val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_SYNC_VERSION = "sync_version"
    private const val SYNC_VERSION = 3
    private const val KEY_LAST_SCANNED = "last_scanned"
    private const val KEY_LAST_DETECTED = "last_detected"
    private const val KEY_TOTAL_SCANNED = "total_scanned"
    private const val KEY_LAST_RUN = "last_run_millis"

    data class Status(
        val connected: Boolean,
        val needsReconnect: Boolean,
        val lastRunMillis: Long,
        val lastScanned: Int,
        val lastDetected: Int,
        val totalScanned: Long,
        val lastError: String?,
        /** A multi-page sync is under way (it continues automatically). */
        val inProgress: Boolean = false
    )

    fun status(context: Context): Status {
        val p = prefs(context)
        return Status(
            connected = p.getBoolean(KEY_CONNECTED, false),
            needsReconnect = p.getBoolean(KEY_NEEDS_RECONNECT, false),
            lastRunMillis = p.getLong(KEY_LAST_RUN, 0L),
            lastScanned = p.getInt(KEY_LAST_SCANNED, 0),
            lastDetected = p.getInt(KEY_LAST_DETECTED, 0),
            totalScanned = p.getLong(KEY_TOTAL_SCANNED, 0L),
            lastError = p.getString(KEY_LAST_ERROR, null),
            inProgress = p.getString(KEY_BACKLOG_QUERY, null) != null
        )
    }

    fun prefsName() = PREFS

    fun recordError(context: Context, error: Throwable) {
        prefs(context).edit()
            .putString(KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName)
            .putLong(KEY_LAST_RUN, System.currentTimeMillis())
            .apply()
    }

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
            prefs(context).edit().putBoolean(KEY_NEEDS_RECONNECT, true)
                .putString(KEY_LAST_ERROR, "Gmail permission expired. Tap Connect Gmail again.")
                .putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()
            return null
        }
        return result.accessToken
    }

    private val syncLock = Mutex()

    /** Outcome of one bounded run. [more] = the backlog isn't finished; run again. */
    data class RunResult(val stored: Int, val more: Boolean)

    /**
     * Syncs in pages of 100 and saves the position after every page, so a run
     * that Android stops (it may stop background work at any time, and always
     * after 10 minutes) continues where it left off instead of starting over.
     */
    suspend fun sync(context: Context): RunResult = syncLock.withLock {
        withContext(Dispatchers.IO) { syncLocked(context) }
    }

    private suspend fun syncLocked(context: Context): RunResult {
        if (!isConnected(context)) return RunResult(0, false)
        val token = silentToken(context) ?: return RunResult(0, false)
        val database = FinVaultDatabase.getInstance(context)
        val p = prefs(context)

        // Parser fixes: re-check the full history once so emails missed before are picked up.
        if (p.getInt(KEY_SYNC_VERSION, 1) < SYNC_VERSION) {
            p.edit().remove(KEY_LAST_SYNC).remove(KEY_BACKLOG_QUERY).remove(KEY_PAGE_TOKEN)
                .putInt(KEY_SYNC_VERSION, SYNC_VERSION).apply()
        }

        // Start a new backlog, or continue the saved one.
        if (p.getString(KEY_BACKLOG_QUERY, null) == null) {
            val last = p.getLong(KEY_LAST_SYNC, 0L)
            // Overlap a day so late-delivered mail isn't missed; duplicates are filtered.
            val window = if (last > 0) "after:${last - 86_400}" else "newer_than:${FIRST_SYNC_DAYS}d"
            p.edit()
                .putString(KEY_BACKLOG_QUERY, "$QUERY $window")
                .putLong(KEY_BACKLOG_STARTED, System.currentTimeMillis() / 1000)
                .remove(KEY_PAGE_TOKEN)
                .putInt(KEY_LAST_SCANNED, 0)
                .putInt(KEY_LAST_DETECTED, 0)
                .apply()
        }
        val query = p.getString(KEY_BACKLOG_QUERY, null)!!
        var pageToken = p.getString(KEY_PAGE_TOKEN, null)
        var stored = 0

        repeat(PAGES_PER_RUN) {
            val page = try {
                listPage(token, query, pageToken)
            } catch (e: IllegalStateException) {
                // Expired page token: restart this backlog from the top (duplicates are skipped).
                if (pageToken != null && e.message?.contains("400") == true) {
                    p.edit().remove(KEY_PAGE_TOKEN).apply()
                    return RunResult(stored, true)
                }
                throw e
            }

            val mails = coroutineScope {
                page.ids.chunked(PARALLEL_FETCHES).flatMap { chunk ->
                    chunk.map { id -> async { runCatching { getMessage(token, id) }.getOrNull() } }.awaitAll()
                }.filterNotNull()
            }
            var detected = 0
            mails.forEach { message ->
                val observation = SmsIngestion.buildObservation(
                    sender = message.sender, body = message.text, receivedAt = message.receivedAt
                ) ?: return@forEach
                detected++
                if (SmsIngestion.insertIfNew(database, observation, message.text)) stored++
            }
            FinVaultRepository(database).autoPostPendingSms()

            pageToken = page.nextPageToken
            p.edit()
                .putString(KEY_PAGE_TOKEN, pageToken)
                .putInt(KEY_LAST_SCANNED, p.getInt(KEY_LAST_SCANNED, 0) + page.ids.size)
                .putInt(KEY_LAST_DETECTED, p.getInt(KEY_LAST_DETECTED, 0) + detected)
                .putLong(KEY_TOTAL_SCANNED, p.getLong(KEY_TOTAL_SCANNED, 0L) + page.ids.size)
                .putLong(KEY_LAST_RUN, System.currentTimeMillis())
                .putBoolean(KEY_NEEDS_RECONNECT, false)
                .remove(KEY_LAST_ERROR)
                .apply()

            if (pageToken == null) {
                // Backlog finished: next time only look at mail since it started.
                p.edit()
                    .putLong(KEY_LAST_SYNC, p.getLong(KEY_BACKLOG_STARTED, System.currentTimeMillis() / 1000))
                    .remove(KEY_BACKLOG_QUERY)
                    .remove(KEY_PAGE_TOKEN)
                    .apply()
                FinVaultRepository(database).removeEmailDuplicates()
                return RunResult(stored, false)
            }
        }
        return RunResult(stored, true)
    }

    private data class Page(val ids: List<String>, val nextPageToken: String?)

    private fun listPage(token: String, query: String, pageToken: String?): Page {
        val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=$PAGE_SIZE&q=" +
                URLEncoder.encode(query, "UTF-8") + (pageToken?.let { "&pageToken=$it" } ?: "")
        val json = JSONObject(get(token, url))
        val messages = json.optJSONArray("messages")
        val ids = (0 until (messages?.length() ?: 0)).map { messages!!.getJSONObject(it).getString("id") }
        return Page(ids, json.optString("nextPageToken").takeIf { it.isNotEmpty() })
    }

    private data class Mail(val sender: String, val text: String, val receivedAt: Long)

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

    /** Retries Gmail's per-user rate limit (429 / 403 rateLimitExceeded) with exponential backoff. */
    private fun get(token: String, url: String): String {
        var delayMs = 1_000L
        repeat(6) { attempt ->
            try {
                return getOnce(token, url)
            } catch (e: RateLimited) {
                if (attempt == 5) throw IllegalStateException("Gmail rate limit — will continue automatically")
                Thread.sleep(delayMs + (0..500).random())
                delayMs *= 2
            }
        }
        error("unreachable")
    }

    private class RateLimited : Exception()

    private fun getOnce(token: String, url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) {
                val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                if (connection.responseCode == 429 ||
                    (connection.responseCode == 403 && body.contains("rateLimit", ignoreCase = true))
                ) throw RateLimited()
                val detail = body.takeIf { it.isNotEmpty() }
                    ?.let { runCatching { JSONObject(it).getJSONObject("error").getString("message") }.getOrNull() }
                error("Gmail API error ${connection.responseCode}${detail?.let { ": $it" } ?: ""}")
            }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
