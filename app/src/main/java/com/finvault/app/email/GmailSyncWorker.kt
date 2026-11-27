package com.finvault.app.email

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Pulls new transaction emails from Gmail every 6 hours. */
class GmailSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        try {
            val result = GmailSync.sync(applicationContext)
            // More pages left: continue in a fresh run (each run stays well under Android's limit).
            if (result.more) enqueueContinuation(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by the system. Progress is saved; pick up again soon.
            enqueueContinuation(applicationContext)
            throw e
        } catch (e: Exception) {
            GmailSync.recordError(applicationContext, e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

    companion object {
        private const val PERIODIC = "finvault_gmail_sync"
        private const val NOW = "finvault_gmail_sync_now"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            if (!GmailSync.isConnected(context)) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GmailSyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build()
            )
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GmailSyncWorker>().setConstraints(network).build()
            )
        }

        private const val CONTINUE = "finvault_gmail_sync_continue"

        fun enqueueContinuation(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CONTINUE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<GmailSyncWorker>()
                    .setConstraints(network)
                    .setInitialDelay(5, TimeUnit.SECONDS)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(CONTINUE)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
}
