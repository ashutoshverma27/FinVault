package com.finvault.app.invest

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Refreshes mutual fund NAVs (AMFI) and stock closing prices (NSE) once a day. */
class PriceUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        runCatching { PortfolioRepository(applicationContext).refreshPrices() }
            .fold({ Result.success() }, { if (runAttemptCount < 3) Result.retry() else Result.failure() })

    companion object {
        private const val NAME = "finvault_daily_prices"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
