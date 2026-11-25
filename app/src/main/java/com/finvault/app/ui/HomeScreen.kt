package com.finvault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finvault.app.data.local.AccountBalance
import com.finvault.app.data.local.MonthlySummary
import com.finvault.app.data.local.TransactionListItem
import java.time.LocalTime

@Composable
fun HomeScreen(
    balances: List<AccountBalance>,
    recent: List<TransactionListItem>,
    monthSummary: MonthlySummary,
    pendingSmsCount: Int,
    contentPadding: PaddingValues,
    onAddTransaction: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSms: () -> Unit,
    onTransactionClick: (TransactionListItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Greeting() }
        item { NetWorthCard(balances) }

        if (pendingSmsCount > 0) {
            item { SmsReviewBanner(pendingSmsCount, onOpenSms) }
        }

        item { ThisMonthCard(monthSummary, onOpenInsights) }

        item { SectionHeader("Accounts") }
        item { AccountsRow(balances) }

        item {
            SectionHeader(
                "Recent activity",
                trailing = if (recent.isNotEmpty()) "See all" else null,
                onTrailingClick = onOpenActivity
            )
        }

        if (recent.isEmpty()) {
            item {
                FvCard {
                    EmptyState(
                        visual = Visual(Icons.Rounded.Lock, FvColors.Mint),
                        title = "Your ledger starts here",
                        body = "Add an expense, income or transfer — or let FinVault find transactions in your bank SMS. Everything stays on this device.",
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(onClick = onAddTransaction) {
                                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Add")
                                }
                                FilledTonalButton(onClick = onOpenSms) {
                                    Icon(Icons.Rounded.Sms, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Import SMS")
                                }
                            }
                        }
                    )
                }
            }
        } else {
            item {
                FvCard(contentPadding = 8.dp) {
                    recent.take(6).forEach { item ->
                        TransactionRow(item, onClick = { onTransactionClick(item) })
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Stored only on this device · No cloud account",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Greeting() {
    val hour = LocalTime.now().hour
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Text(
        greeting,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NetWorthCard(balances: List<AccountBalance>) {
    val assets = balances.filter { it.accountType == "ASSET" }.sumOf { it.balanceMinor }
    val liabilities = balances.filter { it.accountType == "LIABILITY" }.sumOf { it.balanceMinor }
    val netWorth = assets - liabilities

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            FvColors.MintContainer,
                            MaterialTheme.colorScheme.surfaceContainer,
                            FvColors.BlueContainer.copy(alpha = 0.6f)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column {
                Text("NET WORTH", style = EyebrowStyle, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(6.dp))
                Text(
                    money(netWorth),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat(Modifier.weight(1f), "Assets", money(assets), FvColors.Mint)
                    MiniStat(Modifier.weight(1f), "Liabilities", money(liabilities), FvColors.Coral)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(modifier: Modifier, label: String, value: String, dot: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dot)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmsReviewBanner(count: Int, onClick: () -> Unit) {
    FvCard(onClick = onClick, containerColor = FvColors.AmberContainer, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Visual(Icons.Rounded.MarkEmailUnread, FvColors.Amber))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 SMS transaction to review" else "$count SMS transactions to review",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "They're not counted until you add them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = FvColors.Amber)
        }
    }
}

@Composable
private fun ThisMonthCard(summary: MonthlySummary, onClick: () -> Unit) {
    val spentFraction = if (summary.incomeMinor > 0) {
        summary.expenseMinor.toFloat() / summary.incomeMinor.toFloat()
    } else if (summary.expenseMinor > 0) 1f else 0f

    FvCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("THIS MONTH", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Insights", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text("Spent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(summary.expenseMinor), style = MaterialTheme.typography.headlineSmall, color = FvColors.Expense)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Earned", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(summary.incomeMinor), style = MaterialTheme.typography.headlineSmall, color = FvColors.Income)
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressTrack(
            fraction = spentFraction,
            color = if (spentFraction >= 1f) FvColors.Expense else FvColors.Amber
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                summary.incomeMinor == 0L && summary.expenseMinor == 0L -> "Nothing recorded yet this month."
                summary.incomeMinor == 0L -> "No income recorded this month."
                spentFraction >= 1f -> "You've spent more than you earned this month."
                else -> "You've spent ${(spentFraction * 100).toInt()}% of this month's income."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccountsRow(balances: List<AccountBalance>) {
    val visible = balances.filter { it.accountType == "ASSET" || it.accountType == "LIABILITY" }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(end = 4.dp)
    ) {
        items(visible, key = { it.accountId }) { account ->
            val visual = accountVisual(account.accountId)
            Card(
                modifier = Modifier.width(156.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    IconBadge(visual, size = 38.dp, iconSize = 20.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        account.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        money(account.balanceMinor),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (account.accountType == "LIABILITY" && account.balanceMinor > 0) FvColors.Coral
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
