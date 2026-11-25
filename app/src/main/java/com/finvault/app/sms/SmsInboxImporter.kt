
package com.finvault.app.sms

import android.content.Context
import androidx.room.withTransaction
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.local.SmsObservationEntity
import java.security.MessageDigest
import java.util.Locale

object SmsIngestion {

    /**
     * The live receiver and the inbox scan can report the same SMS with
     * timestamps that differ by seconds or minutes (network vs. device
     * clock). Messages with identical text inside this window are treated
     * as the same message.
     */
    private const val DUPLICATE_WINDOW_MILLIS = 30 * 60_000L

    /**
     * Banks, card issuers and wallets send from alphanumeric headers
     * (e.g. "VM-HDFCBK") or short codes. A full mobile number is a person,
     * whose "I paid 500" messages should not become ledger candidates.
     */
    fun isPersonalNumber(sender: String): Boolean {
        val digits = sender.filter { it.isDigit() }
        val hasLetters = sender.any { it.isLetter() }
        return !hasLetters && digits.length >= 10
    }

    fun buildObservation(
        sender: String,
        body: String,
        receivedAt: Long
    ): SmsObservationEntity? {

        if (isPersonalNumber(sender)) return null

        val parsed = SmsParser.parse(body) ?: return null

        val normalizedSender = sender.trim().lowercase(Locale.ROOT)
        val normalizedBody = normalizeBody(body)

        // Keep the original, unencrypted text in the fingerprint.
        // This makes live SMS and historical import deduplicate.
        val canonical =
            "$normalizedSender|${receivedAt / 60_000L}|$normalizedBody"

        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return SmsObservationEntity(
            id = fingerprint,
            sender = sender.take(100),
            body = SmsBodyCrypto.encrypt(body.take(3000)),
            receivedAt = receivedAt,
            suggestedAmountMinor = parsed.amountMinor,
            suggestedType = parsed.suggestedType,
            suggestedTitle = parsed.title,
            confidence = parsed.confidence,
            reviewNote = parsed.reviewNote,
            manualOnly = parsed.manualOnly,
            status = "NEEDS_REVIEW"
        )
    }

    /**
     * Inserts the observation unless the same message (same text, close
     * timestamp) is already stored in any status. Returns true if inserted.
     */
    suspend fun insertIfNew(
        database: FinVaultDatabase,
        observation: SmsObservationEntity,
        plainBody: String
    ): Boolean = database.withTransaction {
        val dao = database.smsObservationDao()
        val normalized = normalizeBody(plainBody)

        val duplicate = dao.findNearby(
            fromMillis = observation.receivedAt - DUPLICATE_WINDOW_MILLIS,
            toMillis = observation.receivedAt + DUPLICATE_WINDOW_MILLIS,
            amountMinor = observation.suggestedAmountMinor
        ).any { existing ->
            existing.id == observation.id ||
                    normalizeBody(SmsBodyCrypto.decrypt(existing.body)) == normalized
        }

        !duplicate && dao.insert(observation) != -1L
    }

    suspend fun storeIncoming(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long
    ) {
        val observation =
            buildObservation(sender, body, receivedAt) ?: return

        insertIfNew(
            FinVaultDatabase.getInstance(context),
            observation,
            body
        )
    }

    /**
     * Re-runs the current parser on messages still waiting for review.
     * Messages the parser now rejects (promotions, OTPs, reminders, personal
     * chats) are dismissed; the rest get refreshed suggestions.
     */
    suspend fun reclassifyPending(database: FinVaultDatabase): Int {
        val dao = database.smsObservationDao()
        var dismissed = 0

        dao.getAllPending().forEach { item ->
            val plain = SmsBodyCrypto.decrypt(item.body)
            val parsed = if (isPersonalNumber(item.sender)) null else SmsParser.parse(plain)

            if (parsed == null) {
                dao.updateStatus(item.id, "IGNORED")
                dismissed++
            } else {
                dao.updateSuggestion(
                    id = item.id,
                    amountMinor = parsed.amountMinor,
                    type = parsed.suggestedType,
                    title = parsed.title,
                    confidence = parsed.confidence,
                    reviewNote = parsed.reviewNote,
                    manualOnly = parsed.manualOnly
                )
            }
        }

        return dismissed
    }

    private fun normalizeBody(body: String): String =
        body.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)
}

object SmsInboxImporter {

    fun enqueueFullImport(context: Context) {
        SmsImportScheduler.startOrResume(context)
    }
}
