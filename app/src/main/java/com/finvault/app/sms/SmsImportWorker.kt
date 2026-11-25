
package com.finvault.app.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.local.SmsObservationEntity
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SmsImportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private data class Checkpoint(
        val hasPosition: Boolean,
        val date: Long,
        val smsId: Long,
        val scanned: Long,
        val inserted: Long,
        val completed: Boolean
    )

    private data class SmsRow(
        val id: Long,
        val sender: String,
        val body: String,
        val date: Long
    )

    private val preferences by lazy {
        applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    override suspend fun doWork(): Result {
        return try {
            val permission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.READ_SMS
            )

            if (permission != PackageManager.PERMISSION_GRANTED) {
                return Result.failure(
                    workDataOf(
                        KEY_ERROR to "READ_SMS permission is required."
                    )
                )
            }

            val dao = FinVaultDatabase
                .getInstance(applicationContext)
                .smsObservationDao()

            var checkpoint = readCheckpoint()

            if (checkpoint.completed) {
                return Result.success(
                    workDataOf(
                        KEY_SCANNED to checkpoint.scanned,
                        KEY_INSERTED to checkpoint.inserted,
                        KEY_COMPLETE to true
                    )
                )
            }

            // Encrypt SMS bodies saved by the older implementation.
            encryptLegacyBodies(dao)

            var pagesProcessed = 0

            while (pagesProcessed < MAX_PAGES_PER_WORKER) {
                checkpoint = readCheckpoint()

                val rows = readNextPage(checkpoint)

                if (rows.isEmpty()) {
                    markCompleted(checkpoint)

                    setProgress(
                        workDataOf(
                            KEY_SCANNED to checkpoint.scanned,
                            KEY_INSERTED to checkpoint.inserted,
                            KEY_COMPLETE to true
                        )
                    )

                    return Result.success(
                        workDataOf(
                            KEY_SCANNED to checkpoint.scanned,
                            KEY_INSERTED to checkpoint.inserted,
                            KEY_COMPLETE to true
                        )
                    )
                }

                val observations = rows.mapNotNull { row ->
                    SmsIngestion.buildObservation(
                        sender = row.sender,
                        body = row.body,
                        receivedAt = row.date
                    )
                }

                // The database insert and checkpoint are deliberately
                // separate. If the process stops between them, the same
                // page is retried and INSERT OR IGNORE prevents duplicates.
                val insertResults = if (observations.isEmpty()) {
                    emptyList()
                } else {
                    dao.insertAll(observations)
                }

                val newlyInserted = insertResults.count {
                    it != -1L
                }.toLong()

                val lastRow = rows.last()

                if (checkpoint.hasPosition) {
                    val movedBackwards =
                        lastRow.date < checkpoint.date ||
                                (
                                        lastRow.date == checkpoint.date &&
                                                lastRow.id < checkpoint.smsId
                                        )

                    if (!movedBackwards) {
                        throw IllegalStateException(
                            "SMS pagination did not advance. Scan stopped to avoid repeating messages."
                        )
                    }
                }

                val updated = checkpoint.copy(
                    hasPosition = true,
                    date = lastRow.date,
                    smsId = lastRow.id,
                    scanned = checkpoint.scanned + rows.size,
                    inserted = checkpoint.inserted + newlyInserted
                )

                saveCheckpoint(updated)
                checkpoint = updated

                setProgress(
                    workDataOf(
                        KEY_SCANNED to checkpoint.scanned,
                        KEY_INSERTED to checkpoint.inserted,
                        KEY_COMPLETE to false
                    )
                )

                pagesProcessed++

                // A short page means there are no more older messages.
                if (rows.size < PAGE_SIZE) {
                    markCompleted(checkpoint)

                    setProgress(
                        workDataOf(
                            KEY_SCANNED to checkpoint.scanned,
                            KEY_INSERTED to checkpoint.inserted,
                            KEY_COMPLETE to true
                        )
                    )

                    return Result.success(
                        workDataOf(
                            KEY_SCANNED to checkpoint.scanned,
                            KEY_INSERTED to checkpoint.inserted,
                            KEY_COMPLETE to true
                        )
                    )
                }
            }

            // Keep each worker bounded. WorkManager runs the continuation
            // as a separate task, so a very large inbox doesn't occupy one
            // worker execution indefinitely.
            enqueueContinuation()

            Result.success(
                workDataOf(
                    KEY_SCANNED to checkpoint.scanned,
                    KEY_INSERTED to checkpoint.inserted,
                    KEY_COMPLETE to false
                )
            )

        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission error", e)
            Result.failure(
                workDataOf(KEY_ERROR to "SMS permission was revoked.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SMS import batch failed", e)

            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        KEY_ERROR to (e.message ?: "SMS import failed.")
                    )
                )
            }
        }
    }

    private suspend fun encryptLegacyBodies(
        dao: com.finvault.app.data.local.SmsObservationDao
    ) {
        while (true) {
            val legacyRows = dao.getUnencryptedBodyBatch()

            if (legacyRows.isEmpty()) break

            legacyRows.forEach { row ->
                if (!SmsBodyCrypto.isEncrypted(row.body)) {
                    val encrypted = SmsBodyCrypto.encrypt(row.body)
                    dao.updateStoredBody(row.id, encrypted)
                }
            }
        }
    }

    private fun readNextPage(checkpoint: Checkpoint): List<SmsRow> {
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        val selection = if (checkpoint.hasPosition) {
            """
            ${Telephony.Sms.DATE} < ?
            OR (
                ${Telephony.Sms.DATE} = ?
                AND ${BaseColumns._ID} < ?
            )
            """.trimIndent()
        } else {
            null
        }

        val selectionArgs = if (checkpoint.hasPosition) {
            arrayOf(
                checkpoint.date.toString(),
                checkpoint.date.toString(),
                checkpoint.smsId.toString()
            )
        } else {
            null
        }

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val queryArgs = Bundle().apply {
                if (selection != null) {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        selection
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        selectionArgs
                    )
                }

                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(Telephony.Sms.DATE, BaseColumns._ID)
                )

                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )

                putInt(
                    ContentResolver.QUERY_ARG_LIMIT,
                    PAGE_SIZE
                )
            }

            applicationContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                queryArgs,
                null
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC, ${BaseColumns._ID} DESC"
            )
        } ?: throw IllegalStateException(
            "Could not read SMS provider."
        )

        val rows = ArrayList<SmsRow>(PAGE_SIZE)

        cursor.use { c ->
            val idIndex = c.getColumnIndexOrThrow(BaseColumns._ID)
            val senderIndex =
                c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex =
                c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex =
                c.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (c.moveToNext() && rows.size < PAGE_SIZE) {
                rows.add(
                    SmsRow(
                        id = c.getLong(idIndex),
                        sender = c.getString(senderIndex).orEmpty(),
                        body = c.getString(bodyIndex).orEmpty(),
                        date = c.getLong(dateIndex)
                    )
                )
            }
        }

        return rows
    }

    private fun readCheckpoint(): Checkpoint =
        Checkpoint(
            hasPosition = preferences.getBoolean(KEY_HAS_POSITION, false),
            date = preferences.getLong(KEY_LAST_DATE, 0L),
            smsId = preferences.getLong(KEY_LAST_ID, 0L),
            scanned = preferences.getLong(KEY_SCANNED, 0L),
            inserted = preferences.getLong(KEY_INSERTED, 0L),
            completed = preferences.getBoolean(KEY_COMPLETED, false)
        )

    private fun saveCheckpoint(checkpoint: Checkpoint) {
        val saved = preferences.edit()
            .putBoolean(KEY_HAS_POSITION, checkpoint.hasPosition)
            .putLong(KEY_LAST_DATE, checkpoint.date)
            .putLong(KEY_LAST_ID, checkpoint.smsId)
            .putLong(KEY_SCANNED, checkpoint.scanned)
            .putLong(KEY_INSERTED, checkpoint.inserted)
            .putBoolean(KEY_COMPLETED, false)
            .commit()

        if (!saved) {
            throw IllegalStateException(
                "Could not save SMS scan checkpoint."
            )
        }
    }

    private fun markCompleted(checkpoint: Checkpoint) {
        val saved = preferences.edit()
            .putBoolean(KEY_HAS_POSITION, checkpoint.hasPosition)
            .putLong(KEY_LAST_DATE, checkpoint.date)
            .putLong(KEY_LAST_ID, checkpoint.smsId)
            .putLong(KEY_SCANNED, checkpoint.scanned)
            .putLong(KEY_INSERTED, checkpoint.inserted)
            .putBoolean(KEY_COMPLETED, true)
            .commit()

        if (!saved) {
            throw IllegalStateException(
                "Could not save SMS scan completion."
            )
        }
    }

    private fun enqueueContinuation() {
        val nextRequest =
            OneTimeWorkRequestBuilder<SmsImportWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .addTag(TAG)
                .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                nextRequest
            )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "finvault_full_sms_import"
        const val TAG = "finvault_sms_import"

        const val KEY_SCANNED = "scanned"
        const val KEY_INSERTED = "inserted"
        const val KEY_COMPLETE = "complete"
        const val KEY_ERROR = "error"

        private const val PREFS_NAME = "finvault_sms_import_checkpoint"

        private const val KEY_HAS_POSITION = "has_position"
        private const val KEY_LAST_DATE = "last_date"
        private const val KEY_LAST_ID = "last_id"
        private const val KEY_COMPLETED = "completed"

        private const val PAGE_SIZE = 100
        private const val MAX_PAGES_PER_WORKER = 10
        private const val MAX_RETRIES = 5
    }
}
