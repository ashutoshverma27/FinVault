package com.finvault.app.invest

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finvault.app.data.local.HoldingEntity
import com.finvault.app.email.GmailSync
import com.finvault.app.email.GmailSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PortfolioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PortfolioRepository(application)

    val holdings: StateFlow<List<HoldingEntity>> =
        repository.observeHoldings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val gmailConnectedFlow = MutableStateFlow(GmailSync.isConnected(application))
    val gmailConnected = gmailConnectedFlow.asStateFlow()

    private val busyFlow = MutableStateFlow(false)
    val busy = busyFlow.asStateFlow()

    fun importCas(uri: Uri, password: String, onResult: (Result<CasImportResult>) -> Unit) = run {
        onResult(runCatching { repository.importCas(uri, password) })
    }

    fun refreshPrices(onResult: (Result<Int>) -> Unit) = run { onResult(runCatching { repository.refreshPrices() }) }

    fun onGmailAuthorized() {
        val app = getApplication<Application>()
        GmailSync.markConnected(app)
        gmailConnectedFlow.value = true
        GmailSyncWorker.schedule(app)
        GmailSyncWorker.syncNow(app)
    }

    fun syncGmailNow() = GmailSyncWorker.syncNow(getApplication())

    fun disconnectGmail() {
        val app = getApplication<Application>()
        GmailSyncWorker.cancel(app)
        GmailSync.disconnect(app)
        gmailConnectedFlow.value = false
    }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busyFlow.value = true
            try { block() } finally { busyFlow.value = false }
        }
    }
}
