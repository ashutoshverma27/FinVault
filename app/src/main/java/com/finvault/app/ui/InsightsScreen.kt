package com.finvault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finvault.app.data.local.MonthlyCategoryTotal
import com.finvault.app.data.local.MonthlyPendingSmsSummary
import com.finvault.app.data.local.MonthlySummary
import com.finvault.app.data.local.MonthlyTrendRow
import com.finvault.app.data.local.TransactionListItem
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun InsightsScreen(
    month: YearMonth,
    summary: MonthlySummary,
    categories: List<MonthlyCategoryTotal>,
    trend: List<MonthlyTrendRow>,
    pendingSms: MonthlyPendingSmsSummary,
    monthTransactions: List<TransactionListItem>,
    contentPadding: PaddingValues,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectMonth: (YearMonth) -> Unit,
    onOpenSms: () -> Unit,
    onTransactionClick: (TransactionListItem) -> Unit
) {
    val trendByMonth = trend.associate { it.monthKey to it.expenseMinor }
    val chartMonths = (5 downTo 0).map { month.minusMonths(it.toLong()) }
    val previousExpense = trendByMonth[month.minusMonths(1).toString()] ?: 0L

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
        item {
            MonthSwitcher(month = month, onPrevious = onPreviousMonth, onNext = onNextMonth)
        }

        item { SummaryCard(summary, previousExpense) }

        if (pendingSms.pendingCount > 0) {
            item { PendingSmsCard(pendingSms, onOpenSms) }
        }

        item { CategoryCard(categories, summary.expenseMinor) }

        item {
            TrendCard(
                months = chartMonths,
                values = chartMonths.map { trendByMonth[it.toString()] ?: 0L },
                selected = month,
                onSelect = onSelectMonth
            )
        }

        item {
            SectionHeader(
                "Transactions this month",
                trailing = if (monthTransactions.isNotEmpty()) monthTransactions.size.toString() else null
            )
        }

        if (monthTransactions.isEmpty()) {
            item {
                FvCard {
                    EmptyState(
                        visual = Visual(Icons.Rounded.Insights, FvColors.Blue),
                        title = "Nothing recorded",
                        body = "Transactions you add or approve from SMS for " +
                                month.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())) +
                                " will appear here."
                    )
                }
            }
        } else {
            item {
                FvCard(contentPadding = 8.dp) {
                    monthTransactions.forEach { item ->
                        TransactionRow(item, onClick = { onTransactionClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: MonthlySummary, previousExpense: Long) {
    val remaining = summary.incomeMinor - summary.expenseMinor - summary.investmentMinor
    val savingsRate = if (summary.incomeMinor > 0) {
        ((summary.incomeMinor - summary.expenseMinor).toFloat() / summary.incomeMinor).coerceIn(-1f, 1f)
    } else null

    FvCard {
        Text("SPENT", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                money(summary.expenseMinor),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(10.dp))
            ChangePill(summary.expenseMinor, previousExpense)
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(Modifier.weight(1f), "Income", money(summary.incomeMinor), FvColors.Income)
            Metric(Modifier.weight(1f), "Invested", money(summary.investmentMinor), FvColors.Invest)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Left after spending & investing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    money(remaining),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (remaining >= 0) FvColors.Income else FvColors.Expense
                )
            }
            if (savingsRate != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Savings rate",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${(savingsRate * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (savingsRate >= 0) FvColors.Income else FvColors.Expense
                    )
                }
            }
        }
        if (savingsRate != null) {
            Spacer(Modifier.height(10.dp))
            ProgressTrack(
                fraction = savingsRate.coerceAtLeast(0f),
                color = FvColors.Income
            )
        }
    }
}

@Composable
private fun ChangePill(current: Long, previous: Long) {
    if (previous <= 0L || current <= 0L) return
    val change = (current - previous).toFloat() / previous
    val percent = abs(change * 100).roundToInt()
    if (percent == 0) return
    val up = change > 0
    Pill(
        text = "$percent% vs last month",
        color = if (up) FvColors.Expense else FvColors.Income,
        icon = if (up) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown
    )
}

@Composable
private fun Metric(modifier: Modifier, label: String, value: String, color: Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PendingSmsCard(pending: MonthlyPendingSmsSummary, onOpenSms: () -> Unit) {
    FvCard(containerColor = FvColors.AmberContainer, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Visual(Icons.Rounded.MarkEmailUnread, FvColors.Amber))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${pending.pendingCount} SMS waiting for review",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Up to ${money(pending.candidateAmountMinor)} isn't counted above yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onOpenSms) { Text("Review") }
        }
    }
}

@Composable
private fun CategoryCard(categories: List<MonthlyCategoryTotal>, totalExpense: Long) {
    FvCard {
        Text("WHERE IT WENT", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))

        if (categories.isEmpty() || totalExpense <= 0L) {
            Text(
                "No expenses recorded for this month.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@FvCard
        }

        // Stacked share bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            categories.forEach { category ->
                val weight = (category.amountMinor.toFloat() / totalExpense).coerceAtLeast(0.01f)
                Box(
                    Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(categoryVisual(category.categoryId).color)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        categories.forEach { category ->
            val share = category.amountMinor.toFloat() / totalExpense
            val visual = categoryVisual(category.categoryId)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(visual, size = 36.dp, iconSize = 18.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            category.categoryName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            money(category.amountMinor),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressTrack(
                            fraction = share,
                            color = visual.color,
                            height = 6.dp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${(share * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendCard(
    months: List<YearMonth>,
    values: List<Long>,
    selected: YearMonth,
    onSelect: (YearMonth) -> Unit
) {
    val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val nonZero = values.filter { it > 0 }
    val average = if (nonZero.isEmpty()) 0L else nonZero.sum() / nonZero.size

    FvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "6-MONTH SPENDING",
                style = EyebrowStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (average > 0) {
                Text(
                    "Avg ${money(average, compact = true)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            months.forEachIndexed { index, month ->
                val value = values[index]
                val isSelected = month == selected
                val fraction = (value.toFloat() / max).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(month) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        if (value > 0) money(value, compact = true) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .fillMaxHeight(fraction.coerceAtLeast(0.03f))
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                            .background(
                                if (isSelected) FvColors.Mint
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap a month to view it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
