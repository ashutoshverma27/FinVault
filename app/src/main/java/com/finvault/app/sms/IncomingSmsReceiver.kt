package com.finvault.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IncomingSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val messages =
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)

                if (messages.isEmpty()) return@launch

                val sender = messages.firstOrNull()
                    ?.originatingAddress
                    .orEmpty()

                val body = messages.joinToString(separator = "") {
                    it.messageBody.orEmpty()
                }

                val receivedAt = messages.firstOrNull()
                    ?.timestampMillis
                    ?: System.currentTimeMillis()

                SmsIngestion.storeIncoming(
                    context = appContext,
                    sender = sender,
                    body = body,
                    receivedAt = receivedAt
                )

            } catch (exception: Exception) {
                android.util.Log.e(
                    "FinVaultSms",
                    "Could not process incoming SMS",
                    exception
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}