@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.finvault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Switch
import com.finvault.app.data.local.AccountEntity
import com.finvault.app.data.local.TransactionListItem

private enum class TypeFilter(val label: String, val type: String?) {
    ALL("All", null),
    EXPENSE("Expenses", "EXPENSE"),
    INCOME("Income", "INCOME"),
    TRANSFER("Transfers", "TRANSFER"),
    REFUND("Refunds", "REFUND")
}

@Composable
fun ActivityScreen(
    transactions: List<TransactionListItem>,
    contentPadding: PaddingValues,
    onTransactionClick: (TransactionListItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(TypeFilter.ALL) }

    val filtered = remember(transactions, query, filter) {
        val q = query.trim().lowercase()
        transactions.filter { item ->
            (filter.type == null || item.type == filter.type) &&
                    (q.isEmpty() ||
                            item.title.lowercase().contains(q) ||
                            item.categoryName.orEmpty().lowercase().contains(q) ||
                            item.note.lowercase().contains(q))
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.occurredAt.toLocalDate() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search transactions") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TypeFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    visual = Visual(Icons.AutoMirrored.Rounded.ReceiptLong, FvColors.Slate),
                    title = if (transactions.isEmpty()) "No transactions yet" else "No matches",
                    body = if (transactions.isEmpty()) "Tap + to add one, or review detected SMS."
                    else "Try a different search or filter."
                )
            }
        }

        grouped.forEach { (date, items) ->
            item(key = "header-$date") {
                val dayNet = items.sumOf {
                    when (it.type) {
                        "INCOME", "REFUND" -> it.amountMinor
                        "EXPENSE" -> -it.amountMinor
                        else -> 0L
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        dayLabel(date),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (dayNet != 0L) {
                        Text(
                            (if (dayNet > 0) "+" else "−") + money(kotlin.math.abs(dayNet)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item(key = "group-$date") {
                FvCard(contentPadding = 6.dp) {
                    items.forEach { item ->
                        TransactionRow(item, onClick = { onTransactionClick(item) }, showDate = false)
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionDetailSheet(
    item: TransactionListItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    accounts: List<AccountEntity> = emptyList(),
    /** (categoryId, applyToMerchant) */
    onChangeCategory: (String, Boolean) -> Unit = { _, _ -> },
    /** (otherAccountId, rememberPayee) */
    onMarkOwnTransfer: (String, Boolean) -> Unit = { _, _ -> }
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableStateOf(false) }
    var pickingAccount by remember { mutableStateOf(false) }
    var applyToMerchant by remember { mutableStateOf(true) }
    val visual = transactionVisual(item)
    val sign = when (item.type) {
        "INCOME", "REFUND" -> "+"
        "EXPENSE" -> "−"
        else -> ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconBadge(visual, size = 60.dp, iconSize = 30.dp)
            Spacer(Modifier.height(12.dp))
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "$sign${money(item.amountMinor)}",
                style = MaterialTheme.typography.headlineLarge,
                color = typeColor(item.type)
            )
            Spacer(Modifier.height(20.dp))

            FvCard(contentPadding = 16.dp) {
                DetailRow("Type", item.type.lowercase().replaceFirstChar { it.uppercase() })
                when (item.type) {
                    "EXPENSE" -> {
                        DetailRow("Category", item.categoryName ?: "—")
                        DetailRow("Paid from", item.fromName ?: "—")
                    }
                    "INCOME" -> {
                        DetailRow("Category", item.categoryName ?: "—")
                        DetailRow("Received into", item.toName ?: "—")
                    }
                    "REFUND" -> {
                        DetailRow("Category", item.categoryName ?: "—")
                        DetailRow("Refunded to", item.toName ?: "—")
                    }
                    else -> {
                        DetailRow("From", item.fromName ?: "—")
                        DetailRow("To", item.toName ?: "—")
                    }
                }
                DetailRow("Date", dateTimeLabel(item.occurredAt))
                if (item.note.isNotBlank()) DetailRow("Note", item.note)
                if (item.sourceObservationId != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sms, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Added from a bank SMS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (item.type == "EXPENSE" || item.type == "REFUND") {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { pickingCategory = !pickingCategory; pickingAccount = false },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Change category") }
                if (pickingCategory) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Apply to all “${item.title}” transactions",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = applyToMerchant, onCheckedChange = { applyToMerchant = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        accounts.filter { it.accountType == "EXPENSE" }.forEach { category ->
                            FilterChip(
                                selected = category.id == item.categoryId,
                                onClick = { onChangeCategory(category.id, applyToMerchant) },
                                label = { Text(category.name) },
                                leadingIcon = {
                                    Icon(categoryVisual(category.id).icon, null, Modifier.size(18.dp), tint = categoryVisual(category.id).color)
                                }
                            )
                        }
                    }
                }
            }

            if (item.type == "EXPENSE" || item.type == "INCOME") {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { pickingAccount = !pickingAccount; pickingCategory = false },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (item.type == "EXPENSE") "Sent to my own account" else "Came from my own account")
                }
                if (pickingAccount) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Always treat “${item.title}” as my account",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = applyToMerchant, onCheckedChange = { applyToMerchant = it })
                    }
                    Spacer(Modifier.height(8.dp))
                    val ownAccounts = accounts.filter {
                        (it.accountType == "ASSET" || it.accountType == "LIABILITY") &&
                                it.name != item.fromName && it.name != item.toName
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ownAccounts.forEach { account ->
                            FilterChip(
                                selected = false,
                                onClick = { onMarkOwnTransfer(account.id, applyToMerchant) },
                                label = { Text(account.name) },
                                leadingIcon = {
                                    Icon(accountVisual(account.id).icon, null, Modifier.size(18.dp), tint = accountVisual(account.id).color)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete transaction")
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = {
                Text(
                    if (item.sourceObservationId != null)
                        "It will be removed from your ledger and balances, and the SMS will be ignored so it isn't added again."
                    else
                        "It will be removed from your ledger and balances. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
