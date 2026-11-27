package com.finvault.app.email

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.local.SmsObservationEntity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

class EmailViewModel(application: Application) : AndroidViewModel(application) {

    val emails: StateFlow<List<SmsObservationEntity>> =
        FinVaultDatabase.getInstance(application).smsObservationDao().observeEmails()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live sync status (updated by the background worker through SharedPreferences). */
    val status: StateFlow<GmailSync.Status> = callbackFlow {
        val prefs = application.getSharedPreferences(GmailSync.prefsName(), Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(GmailSync.status(application)) }
        trySend(GmailSync.status(application))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GmailSync.status(application))

    fun onAuthorized() {
        val app = getApplication<Application>()
        GmailSync.markConnected(app)
        GmailSyncWorker.schedule(app)
        GmailSyncWorker.syncNow(app)
    }

    fun syncNow() = GmailSyncWorker.syncNow(getApplication())

    fun disconnect() {
        val app = getApplication<Application>()
        GmailSyncWorker.cancel(app)
        GmailSync.disconnect(app)
    }
}
