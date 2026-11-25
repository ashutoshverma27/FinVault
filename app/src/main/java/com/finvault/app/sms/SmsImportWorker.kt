
package com.finvault.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Imports financial SMS from the device inbox.
 *
 * Phase 1 (full scan): walks the inbox from newest to oldest in pages,
 * resuming from a checkpoint if the process is stopped.
 *
 * Phase 2 (catch-up): once the full scan has completed, each run imports
 * only messages newer than the newest message already seen. This picks up
 * anything the live receiver missed (permission granted late, receiver
 * disabled, process killed, etc.).
 */
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

            val database = FinVaultDatabase.getInstance(applicationContext)
            val dao = database.smsObservationDao()

            upgradeScanVersionIfNeeded(database)

            // Encrypt SMS bodies saved by the older implementation.
            encryptLegacyBodies(dao)

            var checkpoint = readCheckpoint()

            if (!checkpoint.completed) {
                var pagesProcessed = 0

                while (pagesProcessed < MAX_PAGES_PER_WORKER) {
                    checkpoint = readCheckpoint()

                    val rows = readOlderPage(checkpoint)

                    if (rows.isEmpty()) {
                        markCompleted(checkpoint)
                        break
                    }

                    // The newest message seen becomes the catch-up watermark.
                    if (!checkpoint.hasPosition && !preferences.contains(KEY_WATERMARK_DATE)) {
                        saveWatermark(rows.first())
                    }

                    val newlyInserted = ingest(database, rows)

                    val lastRow = rows.last()

                    if (checkpoint.hasPosition) {
                        val movedBackwards =
                            lastRow.date < checkpoint.date ||
                                    (lastRow.date == checkpoint.date && lastRow.id < checkpoint.smsId)

                        if (!movedBackwards) {
                            throw IllegalStateException(
                                "SMS pagination did not advance. Scan stopped to avoid repeating messages."
                            )
                        }
                    }

                    checkpoint = checkpoint.copy(
                        hasPosition = true,
                        date = lastRow.date,
                        smsId = lastRow.id,
                        scanned = checkpoint.scanned + rows.size,
                        inserted = checkpoint.inserted + newlyInserted
                    )

                    saveCheckpoint(checkpoint)
                    reportProgress(checkpoint, complete = false)

                    pagesProcessed++

                    // A short page means there are no more older messages.
                    if (rows.size < PAGE_SIZE) {
                        markCompleted(checkpoint)
                        break
                    }
                }

                if (!readCheckpoint().completed) {
                    // Keep each worker bounded. WorkManager runs the continuation
                    // as a separate task, so a very large inbox doesn't occupy one
                    // worker execution indefinitely.
                    enqueueContinuation()
                    return Result.success(output(readCheckpoint(), complete = false))
                }
            }

            catchUpNewMessages(database)

            val finalCheckpoint = readCheckpoint()
            reportProgress(finalCheckpoint, complete = true)
            Result.success(output(finalCheckpoint, complete = true))

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

    private suspend fun ingest(database: FinVaultDatabase, rows: List<SmsRow>): Long {
        var inserted = 0L
        rows.forEach { row ->
            val observation = SmsIngestion.buildObservation(
                sender = row.sender,
                body = row.body,
                receivedAt = row.date
            ) ?: return@forEach

            if (SmsIngestion.insertIfNew(database, observation, row.body)) {
                inserted++
            }
        }
        return inserted
    }

    private suspend fun catchUpNewMessages(database: FinVaultDatabase) {
        var pages = 0
        while (pages < MAX_PAGES_PER_WORKER) {
            val watermarkDate = preferences.getLong(KEY_WATERMARK_DATE, 0L)
            val watermarkId = preferences.getLong(KEY_WATERMARK_ID, 0L)

            val rows = readNewerPage(watermarkDate, watermarkId)
            if (rows.isEmpty()) return

            val inserted = ingest(database, rows)
            val checkpoint = readCheckpoint()
            val updated = checkpoint.copy(
                scanned = checkpoint.scanned + rows.size,
                inserted = checkpoint.inserted + inserted
            )
            markCompleted(updated)
            saveWatermark(rows.last())
            reportProgress(updated, complete = false)

            if (rows.size < PAGE_SIZE) return
            pages++
        }
        enqueueContinuation()
    }

    /**
     * Version 1 of the scan sorted the inbox oldest-first by mistake, so on
     * large inboxes it only ever saw the oldest few hundred messages and then
     * reported "completed". Restart the full scan once for those installs and
     * re-check queued items with the current parser. Already posted or ignored
     * messages keep their status because inserts are de-duplicated.
     */
    private suspend fun upgradeScanVersionIfNeeded(database: FinVaultDatabase) {
        if (preferences.getInt(KEY_SCAN_VERSION, 1) >= CURRENT_SCAN_VERSION) return

        SmsIngestion.reclassifyPending(database)

        val saved = preferences.edit()
            .remove(KEY_HAS_POSITION)
            .remove(KEY_LAST_DATE)
            .remove(KEY_LAST_ID)
            .remove(KEY_SCANNED)
            .remove(KEY_INSERTED)
            .remove(KEY_COMPLETED)
            .remove(KEY_WATERMARK_DATE)
            .remove(KEY_WATERMARK_ID)
            .putInt(KEY_SCAN_VERSION, CURRENT_SCAN_VERSION)
            .commit()

        check(saved) { "Could not reset SMS scan state." }
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

    /** Next page of messages older than the checkpoint, newest first. */
    private fun readOlderPage(checkpoint: Checkpoint): List<SmsRow> {
        val selection = if (checkpoint.hasPosition) {
            "${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${BaseColumns._ID} < ?)"
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

        return query(
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC, ${BaseColumns._ID} DESC"
        )
    }

    /** Next page of messages newer than the watermark, oldest first. */
    private fun readNewerPage(date: Long, id: Long): List<SmsRow> =
        query(
            "${Telephony.Sms.DATE} > ? OR (${Telephony.Sms.DATE} = ? AND ${BaseColumns._ID} > ?)",
            arrayOf(date.toString(), date.toString(), id.toString()),
            "${Telephony.Sms.DATE} ASC, ${BaseColumns._ID} ASC"
        )

    private fun query(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String
    ): List<SmsRow> {
        val projection = arrayOf(
            BaseColumns._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        // Use an explicit SQL sort order. The Bundle-based QUERY_ARG_SORT_COLUMNS
        // + QUERY_ARG_SORT_DIRECTION is turned into "date, _id DESC" by the
        // framework, which sorts by date ASCENDING and broke pagination.
        //
        // Only the inbox is read: sent messages and drafts are not bank alerts.
        val cursor = applicationContext.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        ) ?: throw IllegalStateException("Could not read SMS provider.")

        val rows = ArrayList<SmsRow>(PAGE_SIZE)

        cursor.use { c ->
            val idIndex = c.getColumnIndexOrThrow(BaseColumns._ID)
            val senderIndex = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = c.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (rows.size < PAGE_SIZE && c.moveToNext()) {
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

    private suspend fun reportProgress(checkpoint: Checkpoint, complete: Boolean) {
        setProgress(output(checkpoint, complete))
    }

    private fun output(checkpoint: Checkpoint, complete: Boolean) =
        workDataOf(
            KEY_SCANNED to checkpoint.scanned,
            KEY_INSERTED to checkpoint.inserted,
            KEY_COMPLETE to complete
        )

    private fun readCheckpoint(): Checkpoint =
        Checkpoint(
            hasPosition = preferences.getBoolean(KEY_HAS_POSITION, false),
            date = preferences.getLong(KEY_LAST_DATE, 0L),
            smsId = preferences.getLong(KEY_LAST_ID, 0L),
            scanned = preferences.getLong(KEY_SCANNED, 0L),
            inserted = preferences.getLong(KEY_INSERTED, 0L),
            completed = preferences.getBoolean(KEY_COMPLETED, false)
        )

    private fun saveCheckpoint(checkpoint: Checkpoint) =
        writeCheckpoint(checkpoint, completed = false)

    private fun markCompleted(checkpoint: Checkpoint) =
        writeCheckpoint(checkpoint, completed = true)

    private fun writeCheckpoint(checkpoint: Checkpoint, completed: Boolean) {
        val saved = preferences.edit()
            .putBoolean(KEY_HAS_POSITION, checkpoint.hasPosition)
            .putLong(KEY_LAST_DATE, checkpoint.date)
            .putLong(KEY_LAST_ID, checkpoint.smsId)
            .putLong(KEY_SCANNED, checkpoint.scanned)
            .putLong(KEY_INSERTED, checkpoint.inserted)
            .putBoolean(KEY_COMPLETED, completed)
            .putInt(KEY_SCAN_VERSION, CURRENT_SCAN_VERSION)
            .commit()

        check(saved) { "Could not save SMS scan checkpoint." }
    }

    private fun saveWatermark(row: SmsRow) {
        val currentDate = preferences.getLong(KEY_WATERMARK_DATE, Long.MIN_VALUE)
        val currentId = preferences.getLong(KEY_WATERMARK_ID, Long.MIN_VALUE)
        val isNewer = row.date > currentDate || (row.date == currentDate && row.id > currentId)
        if (!isNewer) return

        val saved = preferences.edit()
            .putLong(KEY_WATERMARK_DATE, row.date)
            .putLong(KEY_WATERMARK_ID, row.id)
            .commit()

        check(saved) { "Could not save SMS sync position." }
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
        private const val KEY_WATERMARK_DATE = "watermark_date"
        private const val KEY_WATERMARK_ID = "watermark_id"
        private const val KEY_SCAN_VERSION = "scan_version"

        private const val CURRENT_SCAN_VERSION = 2

        private const val PAGE_SIZE = 100
        private const val MAX_PAGES_PER_WORKER = 10
        private const val MAX_RETRIES = 5

        /** True once the full history scan has finished at least once. */
        fun isFullScanComplete(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_COMPLETED, false) &&
                    prefs.getInt(KEY_SCAN_VERSION, 1) >= CURRENT_SCAN_VERSION
        }
    }
}
