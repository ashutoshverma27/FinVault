
package com.finvault.app.sms

import android.content.Context
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.local.SmsObservationEntity
import java.security.MessageDigest
import java.util.Locale

object SmsIngestion {

    fun buildObservation(
        sender: String,
        body: String,
        receivedAt: Long
    ): SmsObservationEntity? {

        val parsed = SmsParser.parse(body) ?: return null

        val normalizedSender = sender.trim().lowercase(Locale.ROOT)
        val normalizedBody = body.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)

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

    suspend fun storeIncoming(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long
    ) {
        val observation =
            buildObservation(sender, body, receivedAt) ?: return

        FinVaultDatabase.getInstance(context)
            .smsObservationDao()
            .insert(observation)
    }
}

object SmsInboxImporter {

    fun enqueueFullImport(context: Context) {
        SmsImportScheduler.startOrResume(context)
    }
}
