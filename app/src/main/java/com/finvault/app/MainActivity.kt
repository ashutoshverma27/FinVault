package com.finvault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finvault.app.data.local.AccountBalance
import com.finvault.app.data.local.AccountEntity
import com.finvault.app.data.local.FinancialTransactionEntity
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.domain.ledger.TransactionCommand
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: FinanceViewModel by viewModels()
    private val monthlyViewModel: MonthlyFinanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinVaultTheme {
                FinVaultApp(viewModel, monthlyViewModel)
            }
        }
    }
}

@Composable
private fun FinVaultTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF8BE0C0),
        secondary = androidx.compose.ui.graphics.Color(0xFF9CB8FF),
        background = androidx.compose.ui.graphics.Color(0xFF0B1117),
        surface = androidx.compose.ui.graphics.Color(0xFF151E27),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202B36)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinVaultApp(
    viewModel: FinanceViewModel,
    monthlyViewModel: MonthlyFinanceViewModel
) {
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FinVault", fontWeight = FontWeight.Bold)
                        Text(
                            "YOUR MONEY. YOUR CONTROL.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                text = { Text("Add transaction") },
                icon = { Text("+") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { NetWorthCard(balances) }
            item {
                Text(
                    "ACCOUNTS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item { AccountSummary(balances) }
            item {
                MonthlyOverviewSection(monthlyViewModel)
            }
            item {
                SmsCenterSection(
                    viewModel = viewModel,
                    accounts = accounts
                )
            }
            item {
                Text(
                    "RECENT ACTIVITY",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (transactions.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Your ledger starts here", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Add your first income, expense or transfer. FinVault stores it on this device.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(transactions, key = { it.id }) { TransactionRow(it) }
            }
            item {
                Text(
                    "Local database • No cloud account connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showAddDialog) {
        AddTransactionDialog(
            accounts = accounts,
            onDismiss = { showAddDialog = false },
            onSave = { command, result ->
                viewModel.addTransaction(command) { saveResult ->
                    saveResult.fold(
                        onSuccess = {
                            result(null)
                            showAddDialog = false
                        },
                        onFailure = { result(it.message ?: "Could not save transaction.") }
                    )
                }
            }
        )
    }
}

@Composable
private fun NetWorthCard(balances: List<AccountBalance>) {
    val assets = balances.filter { it.accountType == "ASSET" }.sumOf { it.balanceMinor }
    val liabilities = balances.filter { it.accountType == "LIABILITY" }.sumOf { it.balanceMinor }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("TOTAL NET WORTH", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                formatInr(assets - liabilities),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Assets", formatInr(assets))
                Metric("Liabilities", formatInr(liabilities))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AccountSummary(balances: List<AccountBalance>) {
    val visible = balances.filter { it.accountType == "ASSET" || it.accountType == "LIABILITY" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { account ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(account.name, fontWeight = FontWeight.Medium)
                        Text(account.accountType, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatInr(account.balanceMinor), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(transaction: FinancialTransactionEntity) {
    val sign = when (transaction.type) {
        "INCOME" -> "+"
        "EXPENSE" -> "−"
        else -> ""
    }
    val amountColor = when (transaction.type) {
        "INCOME" -> MaterialTheme.colorScheme.primary
        "EXPENSE" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(transaction.title, fontWeight = FontWeight.SemiBold)
                Text(
                    "${transaction.type} • ${formatDate(transaction.occurredAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("$sign${formatInr(transaction.amountMinor)}",
                color = amountColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AddTransactionDialog(
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (TransactionCommand, (String?) -> Unit) -> Unit
) {
    var type by remember { mutableStateOf(TransactionCommand.Type.EXPENSE) }
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf(FinVaultRepository.BANK) }
    var selectedDestinationId by remember { mutableStateOf(FinVaultRepository.CASH) }
    var selectedCategoryId by remember { mutableStateOf(FinVaultRepository.FOOD_EXPENSE) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val paymentAccounts = accounts.filter {
        it.accountType == "ASSET" || it.accountType == "LIABILITY"
    }
    val expenseCategories = accounts.filter { it.accountType == "EXPENSE" }
    val incomeCategories = accounts.filter { it.accountType == "INCOME" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New transaction") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TransactionCommand.Type.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option; error = null },
                            label = {
                                Text(option.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Description") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it; error = null },
                    label = { Text("Amount (₹)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when (type) {
                    TransactionCommand.Type.EXPENSE -> {
                        AccountDropdown("Paid from", paymentAccounts, selectedAccountId) {
                            selectedAccountId = it
                        }
                        AccountDropdown("Category", expenseCategories, selectedCategoryId) {
                            selectedCategoryId = it
                        }
                    }
                    TransactionCommand.Type.INCOME -> {
                        AccountDropdown(
                            "Received into", paymentAccounts.filter { it.accountType == "ASSET" },
                            selectedAccountId
                        ) { selectedAccountId = it }
                        AccountDropdown("Income category", incomeCategories, selectedCategoryId) {
                            selectedCategoryId = it
                        }
                    }
                    TransactionCommand.Type.TRANSFER -> {
                        AccountDropdown("From", paymentAccounts, selectedAccountId) {
                            selectedAccountId = it
                        }
                        AccountDropdown("To", paymentAccounts, selectedDestinationId) {
                            selectedDestinationId = it
                        }
                    }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val minor = parseAmount(amount)
                    if (title.isBlank() || minor == null) {
                        error = "Enter a description and a valid amount."
                        return@TextButton
                    }
                    val command = when (type) {
                        TransactionCommand.Type.EXPENSE -> TransactionCommand(
                            title, minor, type, fromAccountId = selectedAccountId,
                            categoryAccountId = selectedCategoryId, note = note
                        )
                        TransactionCommand.Type.INCOME -> TransactionCommand(
                            title, minor, type, toAccountId = selectedAccountId,
                            categoryAccountId = selectedCategoryId, note = note
                        )
                        TransactionCommand.Type.TRANSFER -> TransactionCommand(
                            title, minor, type, fromAccountId = selectedAccountId,
                            toAccountId = selectedDestinationId, note = note
                        )
                    }
                    saving = true
                    onSave(command) { message ->
                        saving = false
                        if (message != null) error = message
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AccountDropdown(
    label: String,
    options: List<AccountEntity>,
    selectedId: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$label: ${selected?.name ?: "Choose"}")
                Text("⌄")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = { onSelected(account.id); expanded = false }
                )
            }
        }
    }
}

private fun parseAmount(value: String): Long? = try {
    BigDecimal(value.trim().replace(",", ""))
        .setScale(2, RoundingMode.UNNECESSARY)
        .movePointRight(2)
        .longValueExact()
        .takeIf { it > 0 }
} catch (_: Exception) {
    null
}

private fun formatInr(amountMinor: Long): String =
    NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        .format(BigDecimal.valueOf(amountMinor, 2))

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestamp))
