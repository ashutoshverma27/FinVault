
package com.finvault.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.finvault.app.sms.SmsImportScheduler
import com.finvault.app.sms.SmsImportWorker
import kotlin.math.max

@Composable
fun SmsImportStatusSection() {
    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)

    val workFlow = remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(
            SmsImportWorker.UNIQUE_WORK_NAME
        )
    }

    val workInfos by workFlow.collectAsState(initial = emptyList())

    val activeWork = workInfos.firstOrNull {
        it.state == WorkInfo.State.RUNNING
    } ?: workInfos.firstOrNull {
        it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.BLOCKED
    }

    val displayedWork = activeWork ?: workInfos.maxByOrNull {
        it.progress.getLong(SmsImportWorker.KEY_SCANNED, 0L)
    }

    val scanned = displayedWork?.let {
        maxOf(
            it.progress.getLong(SmsImportWorker.KEY_SCANNED, 0L),
            it.outputData.getLong(SmsImportWorker.KEY_SCANNED, 0L)
        )
    } ?: 0L

    val inserted = displayedWork?.let {
        maxOf(
            it.progress.getLong(SmsImportWorker.KEY_INSERTED, 0L),
            it.outputData.getLong(SmsImportWorker.KEY_INSERTED, 0L)
        )
    } ?: 0L

    val isRunning = activeWork != null
    val isCompleted =
        displayedWork?.state == WorkInfo.State.SUCCEEDED
    val isFailed =
        displayedWork?.state == WorkInfo.State.FAILED

    val error = displayedWork?.outputData?.getString(
        SmsImportWorker.KEY_ERROR
    )

    val hasReadPermission =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "SMS HISTORY SCAN",
                style = MaterialTheme.typography.titleMedium
            )

            when {
                !hasReadPermission -> {
                    Text(
                        "Grant SMS read permission in the SMS section first.",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                isRunning -> {
                    Text("Scanning your SMS history...")
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                isCompleted -> {
                    Text(
                        "SMS scan completed.",
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                isFailed -> {
                    Text(
                        error ?: "SMS scan failed. You can try again.",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> {
                    Text(
                        "Scan your available SMS history in the background."
                    )
                }
            }

            if (scanned > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Messages scanned")
                    Text("$scanned")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("New financial candidates")
                    Text("$inserted")
                }
            }

            Button(
                onClick = {
                    SmsImportScheduler.startOrResume(context)
                },
                enabled = hasReadPermission &&
                        !isRunning &&
                        !isCompleted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        isRunning -> "Scan in progress"
                        isCompleted -> "Scan completed"
                        isFailed -> "Resume SMS scan"
                        else -> "Scan all SMS"
                    }
                )
            }

            Text(
                "Messages are processed in small background batches. " +
                        "Only financial candidates are added to the review queue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
