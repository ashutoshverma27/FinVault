
package com.finvault.app.sms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SmsImportScheduler {

    fun startOrResume(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmsImportWorker>()
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .addTag(SmsImportWorker.TAG)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                SmsImportWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
    }
}
