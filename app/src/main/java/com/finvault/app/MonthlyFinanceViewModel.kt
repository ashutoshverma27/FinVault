
package com.finvault.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.local.MonthlyCategoryTotal
import com.finvault.app.data.local.MonthlyPendingSmsSummary
import com.finvault.app.data.local.MonthlySummary
import com.finvault.app.data.local.MonthlyTrendRow
import com.finvault.app.data.local.TransactionListItem
import com.finvault.app.data.repository.FinVaultRepository
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyFinanceViewModel(application: Application) :
    AndroidViewModel(application) {

    private val database = FinVaultDatabase.getInstance(application)

    private val dao = database.monthlyOverviewDao()

    private val repository = FinVaultRepository(database)

    private val monthFlow = MutableStateFlow(YearMonth.now())

    val selectedMonth = monthFlow.asStateFlow()

    private fun startMillis(month: YearMonth): Long =
        month.atDay(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun endMillis(month: YearMonth): Long =
        month.plusMonths(1)
            .atDay(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    val summary = monthFlow
        .flatMapLatest { month ->
            dao.observeMonthlySummary(
                startMillis = startMillis(month),
                endMillis = endMillis(month),
                investmentAccountId = FinVaultRepository.INVESTMENTS
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MonthlySummary()
        )

    val categories = monthFlow
        .flatMapLatest { month ->
            dao.observeMonthlyCategories(
                startMillis(month),
                endMillis(month)
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList<MonthlyCategoryTotal>()
        )

    val spendingTrend = monthFlow
        .flatMapLatest { month ->
            val firstMonth = month.minusMonths(5)

            dao.observeSpendingTrend(
                startMillis(firstMonth),
                endMillis(month)
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList<MonthlyTrendRow>()
        )

    val pendingSmsSummary = monthFlow
        .flatMapLatest { month ->
            dao.observePendingSmsSummary(
                startMillis(month),
                endMillis(month)
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MonthlyPendingSmsSummary()
        )

    val monthTransactions = monthFlow
        .flatMapLatest { month ->
            repository.observeTransactionItems(startMillis(month), endMillis(month))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList<TransactionListItem>()
        )

    fun previousMonth() {
        monthFlow.value = monthFlow.value.minusMonths(1)
    }

    fun selectMonth(month: YearMonth) {
        if (month <= YearMonth.now()) {
            monthFlow.value = month
        }
    }

    fun nextMonth() {
        val next = monthFlow.value.plusMonths(1)

        if (next <= YearMonth.now()) {
            monthFlow.value = next
        }
    }
}
