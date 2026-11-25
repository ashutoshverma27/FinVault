@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.finvault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.finvault.app.data.local.AccountEntity
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.domain.ledger.TransactionCommand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Initial values for the transaction form. */
data class TransactionDraft(
    val type: TransactionCommand.Type = TransactionCommand.Type.EXPENSE,
    val title: String = "",
    val amountMinor: Long? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val fromAccountId: String = FinVaultRepository.BANK,
    val toAccountId: String = FinVaultRepository.CASH,
    val expenseCategoryId: String = FinVaultRepository.FOOD_EXPENSE,
    val incomeCategoryId: String = FinVaultRepository.SALARY_INCOME,
    val note: String = ""
)

/**
 * Bottom sheet wrapper around [TransactionForm].
 * [header] is shown above the form (used by SMS review to show the message).
 */
@Composable
fun TransactionSheet(
    title: String,
    draft: TransactionDraft,
    accounts: List<AccountEntity>,
    submitLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (TransactionCommand, (String?) -> Unit) -> Unit,
    header: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null,
    autoFocusAmount: Boolean = true
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            header?.let {
                it()
                Spacer(Modifier.height(16.dp))
            }
            TransactionForm(
                draft = draft,
                accounts = accounts,
                submitLabel = submitLabel,
                onSubmit = onSubmit,
                secondaryAction = secondaryAction,
                autoFocusAmount = autoFocusAmount
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun TransactionForm(
    draft: TransactionDraft,
    accounts: List<AccountEntity>,
    submitLabel: String,
    onSubmit: (TransactionCommand, (String?) -> Unit) -> Unit,
    secondaryAction: (@Composable () -> Unit)?,
    autoFocusAmount: Boolean
) {
    var type by remember { mutableStateOf(draft.type) }
    var title by remember { mutableStateOf(draft.title) }
    var amount by remember { mutableStateOf(draft.amountMinor?.let(::amountForInput).orEmpty()) }
    var note by remember { mutableStateOf(draft.note) }
    var fromAccountId by remember { mutableStateOf(draft.fromAccountId) }
    var toAccountId by remember { mutableStateOf(draft.toAccountId) }
    var expenseCategoryId by remember { mutableStateOf(draft.expenseCategoryId) }
    var incomeCategoryId by remember { mutableStateOf(draft.incomeCategoryId) }
    var occurredAt by remember { mutableStateOf(draft.occurredAt) }
    var showDatePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val paymentAccounts = accounts.filter { it.accountType == "ASSET" || it.accountType == "LIABILITY" }
    val receivingAccounts = accounts.filter { it.accountType == "ASSET" }
    val expenseCategories = accounts.filter { it.accountType == "EXPENSE" }
    val incomeCategories = accounts.filter { it.accountType == "INCOME" }

    val accent = when (type) {
        TransactionCommand.Type.EXPENSE -> FvColors.Expense
        TransactionCommand.Type.INCOME -> FvColors.Income
        TransactionCommand.Type.TRANSFER -> FvColors.Transfer
    }

    val amountFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocusAmount && amount.isEmpty()) {
            runCatching { amountFocus.requestFocus() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = TransactionCommand.Type.entries
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = type == option,
                    onClick = {
                        type = option
                        error = null
                        if (option == TransactionCommand.Type.INCOME &&
                            receivingAccounts.none { it.id == fromAccountId }
                        ) {
                            fromAccountId = FinVaultRepository.BANK
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        // Large amount entry.
        OutlinedTextField(
            value = amount,
            onValueChange = { value ->
                if (value.length <= 14 && value.all { it.isDigit() || it == '.' || it == ',' }) {
                    amount = value
                    error = null
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(amountFocus),
            textStyle = MaterialTheme.typography.displaySmall.copy(color = accent),
            prefix = {
                Text(
                    "₹ ",
                    style = MaterialTheme.typography.displaySmall,
                    color = accent.copy(alpha = 0.7f)
                )
            },
            placeholder = {
                Text(
                    "0",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Next
            ),
            shape = RoundedCornerShape(20.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = accent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = accent
            )
        )

        OutlinedTextField(
            value = title,
            onValueChange = { title = it; error = null },
            label = { Text("Description") },
            placeholder = { Text("e.g. Groceries, Rent, Salary") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        when (type) {
            TransactionCommand.Type.EXPENSE -> {
                ChipGroup("Category", expenseCategories, expenseCategoryId, ::categoryVisual) {
                    expenseCategoryId = it
                }
                ChipGroup("Paid from", paymentAccounts, fromAccountId, ::accountVisual) {
                    fromAccountId = it
                }
            }

            TransactionCommand.Type.INCOME -> {
                ChipGroup("Category", incomeCategories, incomeCategoryId, ::categoryVisual) {
                    incomeCategoryId = it
                }
                ChipGroup("Received into", receivingAccounts, fromAccountId, ::accountVisual) {
                    fromAccountId = it
                }
            }

            TransactionCommand.Type.TRANSFER -> {
                ChipGroup("From", paymentAccounts, fromAccountId, ::accountVisual) {
                    fromAccountId = it
                }
                ChipGroup("To", paymentAccounts.filter { it.id != fromAccountId }, toAccountId, ::accountVisual) {
                    toAccountId = it
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { showDatePicker = true },
                label = { Text(dayLabel(occurredAt.toLocalDate())) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )

        error?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            secondaryAction?.invoke()
            Button(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                enabled = !saving,
                shape = RoundedCornerShape(16.dp),
                onClick = {
                    val minor = parseAmountInput(amount)
                    when {
                        minor == null -> {
                            error = "Enter a valid amount (up to 2 decimal places)."
                            return@Button
                        }
                        title.isBlank() -> {
                            error = "Add a short description."
                            return@Button
                        }
                        type == TransactionCommand.Type.TRANSFER && fromAccountId == toAccountId -> {
                            error = "Choose two different accounts."
                            return@Button
                        }
                    }
                    val command = when (type) {
                        TransactionCommand.Type.EXPENSE -> TransactionCommand(
                            title = title.trim(), amountMinor = minor!!, type = type,
                            fromAccountId = fromAccountId, categoryAccountId = expenseCategoryId,
                            note = note, occurredAt = occurredAt
                        )
                        TransactionCommand.Type.INCOME -> TransactionCommand(
                            title = title.trim(), amountMinor = minor!!, type = type,
                            toAccountId = fromAccountId, categoryAccountId = incomeCategoryId,
                            note = note, occurredAt = occurredAt
                        )
                        TransactionCommand.Type.TRANSFER -> TransactionCommand(
                            title = title.trim(), amountMinor = minor!!, type = type,
                            fromAccountId = fromAccountId, toAccountId = toAccountId,
                            note = note, occurredAt = occurredAt
                        )
                    }
                    saving = true
                    onSubmit(command) { message ->
                        saving = false
                        error = message
                    }
                }
            ) {
                Text(if (saving) "Saving…" else submitLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showDatePicker) {
        TransactionDatePicker(
            initial = occurredAt,
            onDismiss = { showDatePicker = false },
            onPicked = { picked ->
                occurredAt = picked
                showDatePicker = false
            }
        )
    }
}

@Composable
private fun ChipGroup(
    label: String,
    options: List<AccountEntity>,
    selectedId: String,
    visualFor: (String?) -> Visual,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            options.forEach { option ->
                val visual = visualFor(option.id)
                FilterChip(
                    selected = option.id == selectedId,
                    onClick = { onSelected(option.id) },
                    label = { Text(option.name) },
                    leadingIcon = {
                        Icon(
                            visual.icon,
                            contentDescription = null,
                            tint = if (option.id == selectedId) MaterialTheme.colorScheme.onSecondaryContainer else visual.color,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            }
        }
    }
}

@Composable
private fun TransactionDatePicker(
    initial: Long,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val initialDate = initial.toLocalDate()
    val todayUtcEnd = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    val state = rememberDatePickerState(
        // DatePicker works in UTC midnight millis.
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis < todayUtcEnd
            override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis == null) {
                    onDismiss()
                    return@TextButton
                }
                val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                val result = when {
                    picked == initialDate -> initial
                    picked == LocalDate.now() -> System.currentTimeMillis()
                    // Keep the original time of day so ordering within a day is stable.
                    else -> picked.atTime(Instant.ofEpochMilli(initial).atZone(zone).toLocalTime())
                        .atZone(zone).toInstant().toEpochMilli()
                }
                onPicked(result)
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state)
    }
}

/** Small outlined secondary button sized to sit beside the primary submit button. */
@Composable
fun SheetSecondaryButton(text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(text, color = color)
    }
}
