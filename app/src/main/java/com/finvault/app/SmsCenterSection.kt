package com.finvault.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finvault.app.data.local.AccountEntity
import com.finvault.app.data.local.SmsObservationEntity
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.domain.ledger.TransactionCommand
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.finvault.app.sms.SmsBodyCrypto

@Composable
fun SmsCenterSection(
    viewModel: FinanceViewModel,
    accounts: List<AccountEntity>
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val pendingSms by viewModel.pendingSms.collectAsStateWithLifecycle()

    var selectedSms by remember {
        mutableStateOf<SmsObservationEntity?>(null)
    }

    var importMessage by remember {
        mutableStateOf("Import your existing inbox and enable new SMS detection.")
    }

    var isImporting by remember {
        mutableStateOf(false)
    }

    var liveDetectionEnabled by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val runImport: () -> Unit = {
        isImporting = true

        viewModel.importExistingSms { result ->
            isImporting = false

            importMessage = result.fold(
                onSuccess = {
                    "Full SMS history scan queued or resumed. " +
                            "You can leave this screen; scanning continues in the background."
                },
                onFailure = { error ->
                    "Could not start SMS scan: ${error.message ?: "Unknown error"}"
                }
            )
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val readGranted =
                result[Manifest.permission.READ_SMS] == true ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_SMS
                        ) == PackageManager.PERMISSION_GRANTED

            val receiveGranted =
                result[Manifest.permission.RECEIVE_SMS] == true ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECEIVE_SMS
                        ) == PackageManager.PERMISSION_GRANTED

            liveDetectionEnabled = receiveGranted

            if (readGranted) {
                runImport()
            } else {
                importMessage =
                    "SMS read permission was not granted. Existing messages cannot be imported."
            }

            if (receiveGranted) {
                importMessage += " New incoming SMS detection is enabled."
            } else {
                importMessage += " New incoming SMS detection is not enabled."
            }
        }

    val requestPermissionsOrImport: () -> Unit = {

        val readGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED

        val receiveGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED

        liveDetectionEnabled = receiveGranted

        if (readGranted && receiveGranted) {
            runImport()
        } else {
            val missingPermissions = mutableListOf<String>()

            if (!readGranted) {
                missingPermissions.add(Manifest.permission.READ_SMS)
            }

            if (!receiveGranted) {
                missingPermissions.add(Manifest.permission.RECEIVE_SMS)
            }

            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Text(
            "SMS DETECTION",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                Text(
                    "Bank SMS import",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    if (liveDetectionEnabled) {
                        "New incoming SMS detection is enabled on this device."
                    } else {
                        "New incoming SMS detection is currently disabled."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "FinVault scans all available SMS messages for likely financial transactions. " +
                            "Detected messages go to review first; they are not posted automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )

                Button(
                    onClick = requestPermissionsOrImport,
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isImporting) {
                            "Scanning SMS…"
                        } else {
                            "Scan full SMS history / Enable detection"
                        }
                    )
                }

                Text(
                    importMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SmsImportStatusSection()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "MESSAGES TO REVIEW",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                pendingSms.size.toString(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (pendingSms.isEmpty()) {
            Text(
                "No detected SMS transactions waiting for review.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            pendingSms.forEach { item ->

                SmsReviewCard(
                    item = item,
                    onReview = {
                        selectedSms = item
                    },
                    onIgnore = {
                        viewModel.ignoreSms(item.id) { result ->
                            importMessage = result.fold(
                                onSuccess = { "Message ignored." },
                                onFailure = {
                                    "Could not ignore message: ${it.message}"
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    selectedSms?.let { item ->

        SmsReviewDialog(
            item = item,
            accounts = accounts,
            onDismiss = {
                selectedSms = null
            },
            onIgnore = {
                viewModel.ignoreSms(item.id) { result ->
                    result.onSuccess {
                        selectedSms = null
                        importMessage = "Message ignored."
                    }.onFailure {
                        importMessage = "Could not ignore message: ${it.message}"
                    }
                }
            },
            onApprove = { command, callback ->
                viewModel.approveSms(
                    smsId = item.id,
                    command = command,
                    onResult = callback
                )
            }
        )
    }
}

@Composable
private fun SmsReviewCard(
    item: SmsObservationEntity,
    onReview: () -> Unit,
    onIgnore: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.sender,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        smsDate(item.receivedAt),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    smsMoney(item.suggestedAmountMinor),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                SmsBodyCrypto.decrypt(item.body),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "Suggested: ${item.suggestedType} • Confidence: ${item.confidence}%",
                style = MaterialTheme.typography.labelSmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onIgnore) {
                    Text("Ignore")
                }

                Button(onClick = onReview) {
                    Text("Review")
                }
            }
        }
    }
}

@Composable
private fun SmsReviewDialog(
    item: SmsObservationEntity,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    onApprove: (
        TransactionCommand,
        (Result<Unit>) -> Unit
    ) -> Unit
) {
    val initialType = runCatching {
        TransactionCommand.Type.valueOf(item.suggestedType)
    }.getOrDefault(TransactionCommand.Type.EXPENSE)

    var type by remember(item.id) {
        mutableStateOf(initialType)
    }

    var title by remember(item.id) {
        mutableStateOf(item.suggestedTitle)
    }

    var amount by remember(item.id) {
        mutableStateOf(smsAmountForInput(item.suggestedAmountMinor))
    }

    var paymentAccountId by remember(item.id) {
        mutableStateOf(FinVaultRepository.BANK)
    }

    var destinationAccountId by remember(item.id) {
        mutableStateOf(FinVaultRepository.CASH)
    }

    var categoryAccountId by remember(item.id) {
        mutableStateOf(
            if (initialType == TransactionCommand.Type.INCOME) {
                FinVaultRepository.OTHER_INCOME
            } else {
                FinVaultRepository.GENERAL_EXPENSE
            }
        )
    }

    var error by remember(item.id) {
        mutableStateOf<String?>(null)
    }

    var saving by remember(item.id) {
        mutableStateOf(false)
    }

    val paymentAccounts = accounts.filter {
        it.accountType == "ASSET" || it.accountType == "LIABILITY"
    }

    val receivingAccounts = accounts.filter {
        it.accountType == "ASSET"
    }

    val categories = when (type) {
        TransactionCommand.Type.EXPENSE ->
            accounts.filter { it.accountType == "EXPENSE" }

        TransactionCommand.Type.INCOME ->
            accounts.filter { it.accountType == "INCOME" }

        TransactionCommand.Type.TRANSFER ->
            emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,

        title = {
            Text("Review SMS transaction")
        },

        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {

                Text(
                    "From: ${item.sender}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    SmsBodyCrypto.decrypt(item.body),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    item.reviewNote,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )

                if (item.manualOnly) {
                    Text(
                        "This message looks like a refund or reversal. " +
                                "The current ledger does not yet support the correct accounting " +
                                "for refunds, so do not post it as ordinary income. " +
                                "Use manual transaction entry instead.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TransactionCommand.Type.entries.forEach { option ->

                            FilterChip(
                                selected = type == option,
                                onClick = {
                                    type = option
                                    error = null

                                    categoryAccountId =
                                        if (option == TransactionCommand.Type.INCOME) {
                                            FinVaultRepository.OTHER_INCOME
                                        } else {
                                            FinVaultRepository.GENERAL_EXPENSE
                                        }

                                    if (
                                        option == TransactionCommand.Type.INCOME &&
                                        accounts.firstOrNull {
                                            it.id == paymentAccountId
                                        }?.accountType == "LIABILITY"
                                    ) {
                                        paymentAccountId = FinVaultRepository.BANK
                                    }
                                },
                                label = {
                                    Text(
                                        option.name.lowercase()
                                            .replaceFirstChar { it.uppercase() }
                                    )
                                }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = amount,
                        onValueChange = {
                            amount = it
                            error = null
                        },
                        label = { Text("Amount (₹)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    when (type) {

                        TransactionCommand.Type.EXPENSE -> {
                            SmsAccountDropdown(
                                label = "Paid from",
                                accounts = paymentAccounts,
                                selectedId = paymentAccountId
                            ) {
                                paymentAccountId = it
                            }

                            SmsAccountDropdown(
                                label = "Expense category",
                                accounts = categories,
                                selectedId = categoryAccountId
                            ) {
                                categoryAccountId = it
                            }
                        }

                        TransactionCommand.Type.INCOME -> {
                            SmsAccountDropdown(
                                label = "Received into",
                                accounts = receivingAccounts,
                                selectedId = paymentAccountId
                            ) {
                                paymentAccountId = it
                            }

                            SmsAccountDropdown(
                                label = "Income category",
                                accounts = categories,
                                selectedId = categoryAccountId
                            ) {
                                categoryAccountId = it
                            }
                        }

                        TransactionCommand.Type.TRANSFER -> {
                            SmsAccountDropdown(
                                label = "From account",
                                accounts = paymentAccounts,
                                selectedId = paymentAccountId
                            ) {
                                paymentAccountId = it
                            }

                            SmsAccountDropdown(
                                label = "To account",
                                accounts = paymentAccounts,
                                selectedId = destinationAccountId
                            ) {
                                destinationAccountId = it
                            }
                        }
                    }
                }

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },

        confirmButton = {
            if (!item.manualOnly) {
                TextButton(
                    enabled = !saving,
                    onClick = {

                        val amountMinor = parseSmsAmount(amount)

                        if (title.isBlank() || amountMinor == null) {
                            error = "Enter a description and a valid amount."
                            return@TextButton
                        }

                        val command = when (type) {

                            TransactionCommand.Type.EXPENSE ->
                                TransactionCommand(
                                    title = title.trim(),
                                    amountMinor = amountMinor,
                                    type = type,
                                    fromAccountId = paymentAccountId,
                                    categoryAccountId = categoryAccountId,
                                    note = "Imported from SMS sender ${item.sender}",
                                    occurredAt = item.receivedAt
                                )

                            TransactionCommand.Type.INCOME ->
                                TransactionCommand(
                                    title = title.trim(),
                                    amountMinor = amountMinor,
                                    type = type,
                                    toAccountId = paymentAccountId,
                                    categoryAccountId = categoryAccountId,
                                    note = "Imported from SMS sender ${item.sender}",
                                    occurredAt = item.receivedAt
                                )

                            TransactionCommand.Type.TRANSFER ->
                                TransactionCommand(
                                    title = title.trim(),
                                    amountMinor = amountMinor,
                                    type = type,
                                    fromAccountId = paymentAccountId,
                                    toAccountId = destinationAccountId,
                                    note = "Imported from SMS sender ${item.sender}",
                                    occurredAt = item.receivedAt
                                )
                        }

                        saving = true

                        onApprove(command) { result ->
                            saving = false

                            result.fold(
                                onSuccess = {
                                    onDismiss()
                                },
                                onFailure = {
                                    error = it.message ?: "Could not post transaction."
                                }
                            )
                        }
                    }
                ) {
                    Text(if (saving) "Saving…" else "Add to ledger")
                }
            }
        },

        dismissButton = {
            Row {
                TextButton(onClick = onIgnore) {
                    Text("Ignore")
                }

                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun SmsAccountDropdown(
    label: String,
    accounts: List<AccountEntity>,
    selectedId: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember(label) {
        mutableStateOf(false)
    }

    val selected = accounts.firstOrNull {
        it.id == selectedId
    }

    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selected?.name ?: "Choose account")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text(account.name) },
                        onClick = {
                            onSelected(account.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private fun parseSmsAmount(value: String): Long? =
    try {
        BigDecimal(value.trim().replace(",", ""))
            .setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .longValueExact()
            .takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

private fun smsAmountForInput(amountMinor: Long): String =
    BigDecimal.valueOf(amountMinor, 2).toPlainString()

private fun smsMoney(amountMinor: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        .format(BigDecimal.valueOf(amountMinor, 2))

private fun smsDate(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        .format(Date(timestamp))