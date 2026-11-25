package com.finvault.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.finvault.app.data.local.MonthlySummary
import com.finvault.app.data.local.SmsObservationEntity
import com.finvault.app.data.local.TransactionListItem
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.sms.SmsImportScheduler
import com.finvault.app.ui.ActivityScreen
import com.finvault.app.ui.FinVaultTheme
import com.finvault.app.ui.HomeScreen
import com.finvault.app.ui.InsightsScreen
import com.finvault.app.ui.LocalHideAmounts
import com.finvault.app.ui.SheetSecondaryButton
import com.finvault.app.ui.SmsReviewHeader
import com.finvault.app.ui.SmsScreen
import com.finvault.app.ui.TransactionDetailSheet
import com.finvault.app.ui.TransactionDraft
import com.finvault.app.ui.TransactionSheet
import com.finvault.app.ui.smsDetails
import com.finvault.app.ui.smsDraft
import com.finvault.app.ui.toLocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: FinanceViewModel by viewModels()
    private val monthlyViewModel: MonthlyFinanceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            FinVaultTheme {
                FinVaultApp(viewModel, monthlyViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Catch up on any SMS that arrived while the live receiver couldn't run.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            SmsImportScheduler.startOrResume(this)
        }
    }
}

private enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    HOME("Home", "FinVault", Icons.Rounded.Home),
    ACTIVITY("Activity", "Activity", Icons.AutoMirrored.Rounded.ReceiptLong),
    INSIGHTS("Insights", "Monthly overview", Icons.Rounded.Insights),
    SMS("SMS", "SMS transactions", Icons.Rounded.Sms)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinVaultApp(
    viewModel: FinanceViewModel,
    monthlyViewModel: MonthlyFinanceViewModel
) {
    val balances by viewModel.balances.collectAsStateWithLifecycle()
    val transactions by viewModel.transactionItems.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val pendingSms by viewModel.pendingSms.collectAsStateWithLifecycle()
    val pendingSmsCount by viewModel.pendingSmsCount.collectAsStateWithLifecycle()
    val autoPostedSms by viewModel.autoPostedSms.collectAsStateWithLifecycle()
    val autoPostedSmsCount by viewModel.autoPostedSmsCount.collectAsStateWithLifecycle()
    val smsBadgeCount = pendingSmsCount + autoPostedSmsCount

    val month by monthlyViewModel.selectedMonth.collectAsStateWithLifecycle()
    val summary by monthlyViewModel.summary.collectAsStateWithLifecycle()
    val categories by monthlyViewModel.categories.collectAsStateWithLifecycle()
    val trend by monthlyViewModel.spendingTrend.collectAsStateWithLifecycle()
    val monthPendingSms by monthlyViewModel.pendingSmsSummary.collectAsStateWithLifecycle()
    val monthTransactions by monthlyViewModel.monthTransactions.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var hideAmounts by rememberSaveable { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var reviewingSms by remember { mutableStateOf<SmsObservationEntity?>(null) }
    var editingAutoSms by remember { mutableStateOf<SmsObservationEntity?>(null) }
    var selectedTransaction by remember { mutableStateOf<TransactionListItem?>(null) }
    var balanceAccount by remember { mutableStateOf<com.finvault.app.data.local.AccountBalance?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun notify(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    // "This month" on Home always means the current calendar month,
    // independent of the month being browsed in Insights.
    val currentMonthSummary = remember(transactions) {
        val now = YearMonth.now()
        val inMonth = transactions.filter { YearMonth.from(it.occurredAt.toLocalDate()) == now }
        MonthlySummary(
            expenseMinor = inMonth.filter { it.type == "EXPENSE" }.sumOf { it.amountMinor },
            incomeMinor = inMonth.filter { it.type == "INCOME" }.sumOf { it.amountMinor },
            investmentMinor = inMonth.filter {
                it.type == "TRANSFER" && it.toAccountId == FinVaultRepository.INVESTMENTS
            }.sumOf { it.amountMinor }
        )
    }

    val ignoreSms: (SmsObservationEntity) -> Unit = { item ->
        viewModel.ignoreSms(item.id) { result ->
            result.onSuccess {
                notify("Message ignored", "Undo") { viewModel.restoreSms(item.id) }
            }.onFailure { notify(it.message ?: "Could not ignore message") }
        }
    }

    CompositionLocalProvider(LocalHideAmounts provides hideAmounts) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            tab.title,
                            style = if (tab == Tab.HOME) MaterialTheme.typography.headlineMedium
                            else MaterialTheme.typography.headlineSmall
                        )
                    },
                    actions = {
                        IconButton(onClick = { hideAmounts = !hideAmounts }) {
                            Icon(
                                if (hideAmounts) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (hideAmounts) "Show amounts" else "Hide amounts"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = tab != Tab.SMS,
                    enter = scaleIn(),
                    exit = scaleOut()
                ) {
                    FloatingActionButton(
                        onClick = { showAddSheet = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add transaction")
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                if (item == Tab.SMS && smsBadgeCount > 0) {
                                    BadgedBox(badge = {
                                        Badge { Text(if (smsBadgeCount > 99) "99+" else smsBadgeCount.toString()) }
                                    }) {
                                        Icon(item.icon, contentDescription = item.label)
                                    }
                                } else {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Crossfade(targetState = tab, label = "tab") { current ->
                when (current) {
                    Tab.HOME -> HomeScreen(
                        balances = balances,
                        recent = transactions,
                        monthSummary = currentMonthSummary,
                        pendingSmsCount = smsBadgeCount,
                        contentPadding = padding,
                        onAddTransaction = { showAddSheet = true },
                        onOpenInsights = {
                            monthlyViewModel.selectMonth(YearMonth.now())
                            tab = Tab.INSIGHTS
                        },
                        onOpenActivity = { tab = Tab.ACTIVITY },
                        onOpenSms = { tab = Tab.SMS },
                        onTransactionClick = { selectedTransaction = it },
                        onAccountClick = { balanceAccount = it }
                    )

                    Tab.ACTIVITY -> ActivityScreen(
                        transactions = transactions,
                        contentPadding = padding,
                        onTransactionClick = { selectedTransaction = it }
                    )

                    Tab.INSIGHTS -> InsightsScreen(
                        month = month,
                        summary = summary,
                        categories = categories,
                        trend = trend,
                        pendingSms = monthPendingSms,
                        monthTransactions = monthTransactions,
                        contentPadding = padding,
                        onPreviousMonth = monthlyViewModel::previousMonth,
                        onNextMonth = monthlyViewModel::nextMonth,
                        onSelectMonth = monthlyViewModel::selectMonth,
                        onOpenSms = { tab = Tab.SMS },
                        onTransactionClick = { selectedTransaction = it }
                    )

                    Tab.SMS -> SmsScreen(
                        pending = pendingSms,
                        pendingCount = pendingSmsCount,
                        autoPosted = autoPostedSms,
                        autoPostedCount = autoPostedSmsCount,
                        accounts = accounts,
                        contentPadding = padding,
                        onReview = { reviewingSms = it },
                        onQuickAdd = { item, command ->
                            viewModel.approveSms(item.id, command) { result ->
                                result.onSuccess { notify("Added “${command.title}” to your ledger") }
                                    .onFailure { notify(it.message ?: "Could not add transaction") }
                            }
                        },
                        onIgnore = ignoreSms,
                        onConfirm = { viewModel.confirmSms(it.id) },
                        onConfirmAll = {
                            viewModel.confirmAllAutoPosted()
                            notify("Marked $autoPostedSmsCount transactions as checked")
                        },
                        onEditAutoPosted = { editingAutoSms = it },
                        onRemoveAutoPosted = { item ->
                            viewModel.removeSmsTransaction(item.id) { result ->
                                result.onSuccess {
                                    notify("Removed from ledger", "Undo") { viewModel.restoreRemovedSms(item.id) }
                                }.onFailure { notify(it.message ?: "Could not remove transaction") }
                            }
                        }
                    )
                }
            }
        }

        if (showAddSheet) {
            TransactionSheet(
                title = "New transaction",
                draft = remember { TransactionDraft() },
                accounts = accounts,
                submitLabel = "Save",
                onDismiss = { showAddSheet = false },
                onSubmit = { command, done ->
                    viewModel.addTransaction(command) { result ->
                        result.onSuccess {
                            done(null)
                            showAddSheet = false
                            notify("Transaction saved")
                        }.onFailure { done(it.message ?: "Could not save transaction.") }
                    }
                }
            )
        }

        reviewingSms?.let { item ->
            val details = remember(item.id) { smsDetails(item) }
            val draft = remember(item.id) { smsDraft(item, details) }
            TransactionSheet(
                title = "Review SMS",
                draft = draft,
                accounts = accounts,
                submitLabel = "Add to ledger",
                autoFocusAmount = false,
                onDismiss = { reviewingSms = null },
                header = { SmsReviewHeader(item, details) },
                secondaryAction = {
                    SheetSecondaryButton("Ignore", onClick = {
                        reviewingSms = null
                        ignoreSms(item)
                    })
                },
                onSubmit = { command, done ->
                    viewModel.approveSms(item.id, command) { result ->
                        result.onSuccess {
                            done(null)
                            reviewingSms = null
                            notify("Added “${command.title}” to your ledger")
                        }.onFailure { done(it.message ?: "Could not add transaction.") }
                    }
                }
            )
        }

        editingAutoSms?.let { item ->
            val details = remember(item.id) { smsDetails(item) }
            val draft = remember(item.id) { smsDraft(item, details) }
            TransactionSheet(
                title = "Edit SMS transaction",
                draft = draft,
                accounts = accounts,
                submitLabel = "Save",
                autoFocusAmount = false,
                onDismiss = { editingAutoSms = null },
                header = { SmsReviewHeader(item, details) },
                onSubmit = { command, done ->
                    viewModel.replaceSmsTransaction(item.id, command) { result ->
                        result.onSuccess {
                            done(null)
                            editingAutoSms = null
                            notify("Transaction updated")
                        }.onFailure { done(it.message ?: "Could not update transaction.") }
                    }
                }
            )
        }

        balanceAccount?.let { account ->
            com.finvault.app.ui.SetBalanceDialog(
                account = account,
                onDismiss = { balanceAccount = null },
                onSave = { value ->
                    viewModel.setAccountBalance(account.accountId, value) { result ->
                        result.onSuccess {
                            balanceAccount = null
                            notify("${account.name} balance updated")
                        }.onFailure { notify(it.message ?: "Could not update balance") }
                    }
                }
            )
        }

        selectedTransaction?.let { item ->
            TransactionDetailSheet(
                item = item,
                onDismiss = { selectedTransaction = null },
                onDelete = {
                    viewModel.deleteTransaction(item.id) { result ->
                        result.onSuccess {
                            selectedTransaction = null
                            notify(
                                if (item.sourceObservationId != null) "Deleted · SMS moved back to review"
                                else "Transaction deleted"
                            )
                        }.onFailure { notify(it.message ?: "Could not delete transaction") }
                    }
                }
            )
        }
    }
}
