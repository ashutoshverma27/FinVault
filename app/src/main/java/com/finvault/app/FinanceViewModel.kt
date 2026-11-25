
package com.finvault.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finvault.app.data.local.AccountBalance
import com.finvault.app.data.local.AccountEntity
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.local.FinancialTransactionEntity
import com.finvault.app.data.local.SmsObservationEntity
import com.finvault.app.data.local.TransactionListItem
import com.finvault.app.data.repository.FinVaultRepository
import com.finvault.app.domain.ledger.TransactionCommand
import com.finvault.app.sms.SmsInboxImporter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FinanceViewModel(application: Application) :
    AndroidViewModel(application) {

    private val repository =
        FinVaultRepository(FinVaultDatabase.getInstance(application))

    val balances: StateFlow<List<AccountBalance>> =
        repository.observeBalances().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val transactions: StateFlow<List<FinancialTransactionEntity>> =
        repository.observeTransactions().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val accounts: StateFlow<List<AccountEntity>> =
        repository.observeAccounts().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val transactionItems: StateFlow<List<TransactionListItem>> =
        repository.observeTransactionItems().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val pendingSmsCount: StateFlow<Int> =
        repository.observePendingSmsCount().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            0
        )

    val pendingSms: StateFlow<List<SmsObservationEntity>> =
        repository.observePendingSms().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    init {
        viewModelScope.launch {
            repository.seedDefaults()
        }
    }

    fun addTransaction(
        command: TransactionCommand,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(
                runCatching {
                    repository.record(command)
                }
            )
        }
    }

    fun importExistingSms(
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                SmsInboxImporter.enqueueFullImport(
                    getApplication<Application>()
                )
            }

            onResult(result)
        }
    }

    fun approveSms(
        smsId: String,
        command: TransactionCommand,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(
                runCatching {
                    repository.recordFromSms(command, smsId)
                }
            )
        }
    }

    fun deleteTransaction(
        transactionId: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(runCatching { repository.deleteTransaction(transactionId) })
        }
    }

    fun restoreSms(smsId: String) {
        viewModelScope.launch {
            runCatching { repository.restoreSms(smsId) }
        }
    }

    fun ignoreSms(
        smsId: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(
                runCatching {
                    repository.markSmsIgnored(smsId)
                }
            )
        }
    }
}
