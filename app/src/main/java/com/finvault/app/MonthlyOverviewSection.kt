
package com.finvault.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finvault.app.data.local.MonthlySummary
import com.finvault.app.data.local.MonthlyTrendRow
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MonthlyOverviewSection(viewModel: MonthlyFinanceViewModel) {
    val month by viewModel.selectedMonth.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val trend by viewModel.spendingTrend.collectAsState()
    val pendingSms by viewModel.pendingSmsSummary.collectAsState()

    val monthLabel = month.format(
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    )

    val netRemaining =
        summary.incomeMinor -
                summary.expenseMinor -
                summary.investmentMinor

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "MONTHLY OVERVIEW",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { viewModel.previousMonth() }) {
                        Text("‹ Previous")
                    }

                    Text(
                        monthLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    TextButton(
                        onClick = { viewModel.nextMonth() },
                        enabled = month < YearMonth.now()
                    ) {
                        Text("Next ›")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "TOTAL SPENDING",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatMonthlyInr(summary.expenseMinor),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MonthlyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Income",
                        amount = summary.incomeMinor,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MonthlyMetric(
                        modifier = Modifier.weight(1f),
                        label = "Invested",
                        amount = summary.investmentMinor,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "REMAINING AFTER SPENDING & INVESTING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatMonthlyInr(netRemaining),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (netRemaining >= 0)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }


        if (pendingSms.pendingCount > 0L) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "SMS AWAITING REVIEW",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "${pendingSms.pendingCount} financial messages detected",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "Candidate amount: ${
                            formatMonthlyInr(pendingSms.candidateAmountMinor)
                        }",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Not included in actual spending or income until you review and approve them.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }


        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "SPENDING BY CATEGORY",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (categories.isEmpty()) {
                    Text(
                        "No expenses recorded for this month.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    val maxAmount = categories.maxOfOrNull {
                        it.amountMinor
                    }?.coerceAtLeast(1L) ?: 1L

                    categories.forEach { category ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    category.categoryName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    formatMonthlyInr(category.amountMinor),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            val fraction =
                                (category.amountMinor.toFloat() /
                                        maxAmount.toFloat()).coerceIn(0f, 1f)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant
                                    )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(
                                            MaterialTheme.colorScheme.primary
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "SIX-MONTH SPENDING TREND",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                val trendByMonth = trend.associateBy {
                    it.monthKey
                }

                val months = (5 downTo 0).map {
                    month.minusMonths(it.toLong())
                }

                val values = months.map { trendByMonth[it.toString()]?.expenseMinor ?: 0L }
                val maxAmount = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

                months.forEach { chartMonth ->
                    val amount =
                        trendByMonth[chartMonth.toString()]?.expenseMinor ?: 0L

                    SpendingTrendRow(
                        month = chartMonth,
                        amountMinor = amount,
                        maxAmountMinor = maxAmount
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthlyMetric(
    modifier: Modifier,
    label: String,
    amount: Long,
    color: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            formatMonthlyInr(amount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun SpendingTrendRow(
    month: YearMonth,
    amountMinor: Long,
    maxAmountMinor: Long
) {
    val fraction =
        (amountMinor.toFloat() / maxAmountMinor.toFloat()).coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            month.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault())),
            modifier = Modifier.width(38.dp),
            style = MaterialTheme.typography.labelMedium
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Text(
            formatMonthlyInr(amountMinor),
            modifier = Modifier.width(92.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatMonthlyInr(amountMinor: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(
        Locale("en", "IN")
    )
    return formatter.format(BigDecimal.valueOf(amountMinor, 2))
}
