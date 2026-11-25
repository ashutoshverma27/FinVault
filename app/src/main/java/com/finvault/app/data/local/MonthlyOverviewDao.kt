
package com.finvault.app.data.local

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class MonthlySummary(
    val expenseMinor: Long = 0L,
    val incomeMinor: Long = 0L,
    val investmentMinor: Long = 0L
)

data class MonthlyCategoryTotal(
    val categoryId: String?,
    val categoryName: String,
    val amountMinor: Long
)

data class MonthlyTrendRow(
    val monthKey: String,
    val expenseMinor: Long
)

data class MonthlyPendingSmsSummary(
    val pendingCount: Long = 0L,
    val candidateAmountMinor: Long = 0L
)

@Dao
interface MonthlyOverviewDao {

    @Query(
        """
        SELECT
            COALESCE(
                SUM(CASE WHEN type = 'EXPENSE' THEN amountMinor
                         WHEN type = 'REFUND' THEN -amountMinor
                         ELSE 0 END), 0
            ) AS expenseMinor,

            COALESCE(
                SUM(CASE WHEN type = 'INCOME'
                    THEN amountMinor ELSE 0 END), 0
            ) AS incomeMinor,

            COALESCE(
                SUM(
                    CASE
                        WHEN type = 'TRANSFER'
                             AND toAccountId = :investmentAccountId
                            THEN amountMinor
                        WHEN type = 'TRANSFER'
                             AND fromAccountId = :investmentAccountId
                            THEN -amountMinor
                        ELSE 0
                    END
                ), 0
            ) AS investmentMinor

        FROM financial_transactions
        WHERE occurredAt >= :startMillis
          AND occurredAt < :endMillis
        """
    )
    fun observeMonthlySummary(
        startMillis: Long,
        endMillis: Long,
        investmentAccountId: String
    ): Flow<MonthlySummary>

    @Query(
        """
        SELECT
            t.categoryAccountId AS categoryId,
            COALESCE(a.name, 'Uncategorised') AS categoryName,
            COALESCE(SUM(CASE WHEN t.type = 'REFUND' THEN -t.amountMinor ELSE t.amountMinor END), 0) AS amountMinor
        FROM financial_transactions t
        LEFT JOIN accounts a ON a.id = t.categoryAccountId
        WHERE t.type IN ('EXPENSE', 'REFUND')
          AND t.occurredAt >= :startMillis
          AND t.occurredAt < :endMillis
        GROUP BY t.categoryAccountId, a.name
        HAVING amountMinor > 0
        ORDER BY amountMinor DESC
        LIMIT 12
        """
    )
    fun observeMonthlyCategories(
        startMillis: Long,
        endMillis: Long
    ): Flow<List<MonthlyCategoryTotal>>

    @Query(
        """
        SELECT
            strftime(
                '%Y-%m',
                occurredAt / 1000,
                'unixepoch',
                'localtime'
            ) AS monthKey,
            COALESCE(SUM(CASE WHEN type = 'REFUND' THEN -amountMinor ELSE amountMinor END), 0) AS expenseMinor
        FROM financial_transactions
        WHERE type IN ('EXPENSE', 'REFUND')
          AND occurredAt >= :startMillis
          AND occurredAt < :endMillis
        GROUP BY monthKey
        ORDER BY monthKey ASC
        """
    )
    fun observeSpendingTrend(
        startMillis: Long,
        endMillis: Long
    ): Flow<List<MonthlyTrendRow>>

    @Query(
        """
        SELECT
            COUNT(*) AS pendingCount,
            COALESCE(
                SUM(
                    CASE
                        WHEN manualOnly = 0 THEN suggestedAmountMinor
                        ELSE 0
                    END
                ), 0
            ) AS candidateAmountMinor
        FROM sms_observations
        WHERE status = 'NEEDS_REVIEW'
          AND receivedAt >= :startMillis
          AND receivedAt < :endMillis
        """
    )
    fun observePendingSmsSummary(
        startMillis: Long,
        endMillis: Long
    ): Flow<MonthlyPendingSmsSummary>
}
