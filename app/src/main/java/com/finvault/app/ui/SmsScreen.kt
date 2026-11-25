@file:OptIn(ExperimentalLayoutApi::class)

package com.finvault.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.finvault.app.data.local.AccountEntity
import com.finvault.app.data.local.SmsObservationEntity
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.domain.ledger.TransactionCommand
import com.finvault.app.sms.ParsedSms
import com.finvault.app.sms.SmsBodyCrypto
import com.finvault.app.sms.SmsImportScheduler
import com.finvault.app.sms.SmsImportWorker
import com.finvault.app.sms.SmsParser

/** Decrypted body plus a fresh parse, used for suggestions in the UI. */
data class SmsDetails(val body: String, val parsed: ParsedSms?)

fun smsDetails(item: SmsObservationEntity): SmsDetails {
    val body = SmsBodyCrypto.decrypt(item.body)
    return SmsDetails(body, SmsParser.parse(body))
}

fun smsDraft(item: SmsObservationEntity, details: SmsDetails): TransactionDraft {
    val parsed = details.parsed
    val type = runCatching { TransactionCommand.Type.valueOf(item.suggestedType) }
        .getOrDefault(TransactionCommand.Type.EXPENSE)
    val category = parsed?.suggestedCategoryId
    return TransactionDraft(
        type = type,
        title = item.suggestedTitle.takeUnless { it == "SMS transaction" } ?: (parsed?.title ?: "Payment"),
        amountMinor = item.suggestedAmountMinor,
        occurredAt = item.receivedAt,
        fromAccountId = parsed?.suggestedAccountId ?: FinVaultRepository.BANK,
        toAccountId = parsed?.suggestedDestinationId ?: FinVaultRepository.CASH,
        expenseCategoryId = category?.takeIf { it.startsWith("expense_") } ?: FinVaultRepository.GENERAL_EXPENSE,
        incomeCategoryId = category?.takeIf { it.startsWith("income_") } ?: FinVaultRepository.OTHER_INCOME,
        note = "From SMS · ${prettySender(item.sender)}"
    )
}

fun draftToCommand(draft: TransactionDraft): TransactionCommand? {
    val amount = draft.amountMinor ?: return null
    return when (draft.type) {
        TransactionCommand.Type.EXPENSE -> TransactionCommand(
            draft.title, amount, draft.type,
            fromAccountId = draft.fromAccountId, categoryAccountId = draft.expenseCategoryId,
            note = draft.note, occurredAt = draft.occurredAt
        )
        TransactionCommand.Type.INCOME -> TransactionCommand(
            draft.title, amount, draft.type,
            toAccountId = draft.fromAccountId, categoryAccountId = draft.incomeCategoryId,
            note = draft.note, occurredAt = draft.occurredAt
        )
        TransactionCommand.Type.TRANSFER -> TransactionCommand(
            draft.title, amount, draft.type,
            fromAccountId = draft.fromAccountId, toAccountId = draft.toAccountId,
            note = draft.note, occurredAt = draft.occurredAt
        )
    }
}

@Composable
fun SmsScreen(
    pending: List<SmsObservationEntity>,
    pendingCount: Int,
    accounts: List<AccountEntity>,
    contentPadding: PaddingValues,
    onReview: (SmsObservationEntity) -> Unit,
    onQuickAdd: (SmsObservationEntity, TransactionCommand) -> Unit,
    onIgnore: (SmsObservationEntity) -> Unit
) {
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SmsStatusCard() }

        item {
            SectionHeader(
                "To review",
                trailing = if (pendingCount > 0) pendingCount.toString() else null
            )
        }

        if (pending.isEmpty()) {
            item {
                FvCard {
                    EmptyState(
                        visual = Visual(Icons.Rounded.DoneAll, FvColors.Mint),
                        title = "All caught up",
                        body = "New bank and card SMS will show up here for a quick check before they're added to your ledger."
                    )
                }
            }
        } else {
            items(pending, key = { it.id }) { item ->
                SmsReviewCard(
                    item = item,
                    accountNames = accountNames,
                    onReview = { onReview(item) },
                    onQuickAdd = { command -> onQuickAdd(item, command) },
                    onIgnore = { onIgnore(item) }
                )
            }
            if (pendingCount > pending.size) {
                item {
                    Text(
                        "Showing the latest ${pending.size} of $pendingCount. Review these to see more.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SmsStatusCard() {
    val context = LocalContext.current
    var permissionTick by remember { mutableIntStateOf(0) }
    var deniedOnce by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionTick++ }

    val (hasRead, hasReceive) = remember(permissionTick) {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        granted(Manifest.permission.READ_SMS) to granted(Manifest.permission.RECEIVE_SMS)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionTick++
        val readGranted = result[Manifest.permission.READ_SMS] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (readGranted) {
            SmsImportScheduler.startOrResume(context)
        }
        if (result.values.any { !it }) deniedOnce = true
    }

    val requestPermissions = {
        launcher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
    }

    val openSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    if (!hasRead) {
        FvCard {
            IconBadge(Visual(Icons.Rounded.Sms, FvColors.Mint), size = 52.dp, iconSize = 26.dp)
            Spacer(Modifier.height(14.dp))
            Text("Track spending automatically", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "FinVault can read your bank, card and UPI alerts and suggest transactions. You approve each one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            PrivacyPoint(Icons.Rounded.Lock, "Processed on this device — nothing is uploaded")
            PrivacyPoint(Icons.Rounded.Block, "OTPs, promotions and personal chats are never saved")
            PrivacyPoint(Icons.Rounded.EnhancedEncryption, "Saved message text is encrypted")
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = requestPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Allow SMS access", fontWeight = FontWeight.SemiBold) }
            if (deniedOnce) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Permission was denied. If Android no longer asks, enable SMS for FinVault in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = openSettings) { Text("Open app settings") }
            }
        }
        return
    }

    // Permission granted: show sync status.
    val workManager = remember { WorkManager.getInstance(context) }
    val workFlow = remember { workManager.getWorkInfosForUniqueWorkFlow(SmsImportWorker.UNIQUE_WORK_NAME) }
    val workInfos by workFlow.collectAsState(initial = emptyList())

    val active = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: workInfos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    val latest = active ?: workInfos.lastOrNull()
    val scanned = latest?.let {
        maxOf(it.progress.getLong(SmsImportWorker.KEY_SCANNED, 0L), it.outputData.getLong(SmsImportWorker.KEY_SCANNED, 0L))
    } ?: 0L
    val found = latest?.let {
        maxOf(it.progress.getLong(SmsImportWorker.KEY_INSERTED, 0L), it.outputData.getLong(SmsImportWorker.KEY_INSERTED, 0L))
    } ?: 0L
    val failed = latest?.state == WorkInfo.State.FAILED
    val error = latest?.outputData?.getString(SmsImportWorker.KEY_ERROR)
    val running = active != null

    FvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                Visual(
                    if (failed) Icons.Rounded.Warning else Icons.Rounded.Sms,
                    if (failed) FvColors.Coral else FvColors.Mint
                )
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        running -> "Scanning messages…"
                        failed -> "Scan stopped"
                        else -> "SMS detection is on"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when {
                        failed -> error ?: "Something went wrong. Try again."
                        scanned > 0 -> "$scanned messages checked · $found transactions found"
                        else -> "Checks your inbox for bank and card alerts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (running) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Pill(
                text = if (hasReceive) "Live detection on" else "Live detection off",
                color = if (hasReceive) FvColors.Mint else FvColors.Amber,
                icon = if (hasReceive) Icons.Rounded.Check else Icons.Rounded.Info
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { SmsImportScheduler.startOrResume(context) },
                enabled = !running
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (failed) "Retry" else "Check now")
            }
            if (!hasReceive) {
                OutlinedButton(onClick = if (deniedOnce) openSettings else requestPermissions) {
                    Text("Turn on live detection")
                }
            }
        }
    }
}

@Composable
private fun PrivacyPoint(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = FvColors.Mint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SmsReviewCard(
    item: SmsObservationEntity,
    accountNames: Map<String, String>,
    onReview: () -> Unit,
    onQuickAdd: (TransactionCommand) -> Unit,
    onIgnore: () -> Unit
) {
    val details = remember(item.id, item.body) { smsDetails(item) }
    val draft = remember(item, details) { smsDraft(item, details) }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }

    val typeLabel = when (draft.type) {
        TransactionCommand.Type.EXPENSE -> "Expense"
        TransactionCommand.Type.INCOME -> "Income"
        TransactionCommand.Type.TRANSFER -> "Transfer"
    }
    val typeColor = typeColor(draft.type.name)
    val sign = when (draft.type) {
        TransactionCommand.Type.EXPENSE -> "−"
        TransactionCommand.Type.INCOME -> "+"
        TransactionCommand.Type.TRANSFER -> ""
    }
    val visual = when (draft.type) {
        TransactionCommand.Type.EXPENSE -> categoryVisual(draft.expenseCategoryId)
        TransactionCommand.Type.INCOME -> categoryVisual(draft.incomeCategoryId)
        TransactionCommand.Type.TRANSFER -> accountVisual(draft.toAccountId)
    }
    val suggestion = when (draft.type) {
        TransactionCommand.Type.EXPENSE -> listOfNotNull(
            accountNames[draft.expenseCategoryId], accountNames[draft.fromAccountId]
        )
        TransactionCommand.Type.INCOME -> listOfNotNull(
            accountNames[draft.incomeCategoryId], accountNames[draft.fromAccountId]
        )
        TransactionCommand.Type.TRANSFER -> listOfNotNull(
            "${accountNames[draft.fromAccountId] ?: "?"} → ${accountNames[draft.toAccountId] ?: "?"}"
        )
    }

    FvCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(visual)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    draft.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${prettySender(item.sender)} · ${dateTimeLabel(item.receivedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "$sign${money(item.suggestedAmountMinor)}",
                style = MaterialTheme.typography.titleMedium,
                color = typeColor
            )
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Pill(typeLabel, typeColor)
            suggestion.forEach { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            if (item.confidence < 70) Pill("Check details", FvColors.Amber, icon = Icons.Rounded.Info)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            details.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { expanded = !expanded }
                .padding(12.dp)
                .animateContentSize()
        )

        if (item.manualOnly) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, null, tint = FvColors.Amber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(item.reviewNote, style = MaterialTheme.typography.bodySmall, color = FvColors.Amber)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onIgnore) { Text("Ignore") }
            if (!item.manualOnly) {
                OutlinedButton(onClick = onReview) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                Button(onClick = { draftToCommand(draft)?.let(onQuickAdd) }) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
        }
    }
}

/** Header shown above the form when reviewing an SMS in the bottom sheet. */
@Composable
fun SmsReviewHeader(item: SmsObservationEntity, details: SmsDetails) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Sms, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "${prettySender(item.sender)} · ${dateTimeLabel(item.receivedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(details.body, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(item.reviewNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
