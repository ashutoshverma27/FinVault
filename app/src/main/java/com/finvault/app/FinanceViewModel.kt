
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

    val autoPostedSms: StateFlow<List<SmsObservationEntity>> =
        repository.observeAutoPostedSms().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val autoPostedSmsCount: StateFlow<Int> =
        repository.observeAutoPostedSmsCount().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            0
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
            val prefs = application.getSharedPreferences("finvault_ledger", android.content.Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_SMS_MAPPING_VERSION, 1) < SMS_MAPPING_VERSION) {
                // Re-post auto-added SMS with the current rules: per-bank accounts,
                // self-transfer pairing (incl. UPI / IMPS reference matching),
                // duplicate alerts and refunds. Then move "General" SMS expenses
                // that now have a recognisable merchant.
                repository.remapAutoPostedSms()
                repository.recategorizeGeneralSmsExpenses()
                prefs.edit().putInt(KEY_SMS_MAPPING_VERSION, SMS_MAPPING_VERSION).apply()
            } else {
                // Anything detected while the app was closed goes into the ledger now.
                repository.autoPostPendingSms()
            }
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
            onResult(runCatching { repository.deleteTransactionByUser(transactionId) })
        }
    }

    /** Result is the number of transactions that moved. */
    fun changeCategory(
        transactionId: String,
        categoryId: String,
        applyToMerchant: Boolean,
        onResult: (Result<Int>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(runCatching { repository.changeCategory(transactionId, categoryId, applyToMerchant) })
        }
    }

    fun convertToOwnTransfer(
        transactionId: String,
        otherAccountId: String,
        remember: Boolean,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(runCatching { repository.convertToOwnTransfer(transactionId, otherAccountId, remember) })
        }
    }

    fun setAccountBalance(accountId: String, balanceMinor: Long, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repository.setAccountBalance(accountId, balanceMinor) })
        }
    }

    fun confirmSms(smsId: String) {
        viewModelScope.launch { runCatching { repository.confirmSms(smsId) } }
    }

    fun confirmAllAutoPosted() {
        viewModelScope.launch { runCatching { repository.confirmAllAutoPosted() } }
    }

    fun removeSmsTransaction(smsId: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repository.removeSmsTransaction(smsId) })
        }
    }

    /** Undo for [removeSmsTransaction]: put the SMS back into the ledger. */
    fun restoreRemovedSms(smsId: String) {
        viewModelScope.launch {
            runCatching {
                repository.restoreSms(smsId)
                repository.autoPostSmsById(smsId)
            }
        }
    }

    fun replaceSmsTransaction(
        smsId: String,
        command: TransactionCommand,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(runCatching { repository.replaceSmsTransaction(smsId, command) })
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

    private companion object {
        const val KEY_SMS_MAPPING_VERSION = "sms_mapping_version"
        const val SMS_MAPPING_VERSION = 3
    }
}
