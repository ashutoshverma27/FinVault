package com.finvault.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finvault.app.data.local.SmsObservationEntity
import com.finvault.app.email.GmailSync
import com.finvault.app.sms.SmsTransactionMapper
import com.google.android.gms.auth.api.identity.Identity

private enum class EmailFilter(val label: String, val statuses: Set<String>?) {
    ALL("All", null),
    ADDED("Added", setOf("AUTO_POSTED", "POSTED")),
    DUPLICATE("Also in SMS", setOf("MERGED")),
    REVIEW("Needs review", setOf("NEEDS_REVIEW")),
    IGNORED("Ignored", setOf("IGNORED"))
}

private fun statusLabel(status: String): Pair<String, Color> = when (status) {
    "AUTO_POSTED" -> "Added" to FvColors.Income
    "POSTED" -> "Added · checked" to FvColors.Income
    "MERGED" -> "Already recorded (SMS / other alert)" to FvColors.Transfer
    "NEEDS_REVIEW" -> "Needs review" to FvColors.Amber
    "IGNORED" -> "Ignored" to FvColors.Slate
    else -> status to FvColors.Slate
}

@Composable
fun EmailScreen(
    status: GmailSync.Status,
    emails: List<SmsObservationEntity>,
    contentPadding: PaddingValues,
    onAuthorized: () -> Unit,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit,
    onOpen: (SmsObservationEntity) -> Unit,
    notify: (String) -> Unit
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(EmailFilter.ALL) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) onAuthorized() else notify("Gmail access was not granted")
    }

    fun connect() {
        Identity.getAuthorizationClient(context).authorize(GmailSync.request)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else onAuthorized()
            }
            .addOnFailureListener { notify("Gmail sign-in failed: ${it.message}") }
    }

    val counts = remember(emails) { emails.groupingBy { it.status }.eachCount() }
    val added = (counts["AUTO_POSTED"] ?: 0) + (counts["POSTED"] ?: 0)
    val shown = remember(emails, filter) {
        val statuses = filter.statuses
        emails.filter { statuses == null || it.status in statuses }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            FvCard {
                Text("GMAIL (READ-ONLY)", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !status.connected -> "Not connected"
                        status.lastRunMillis == 0L -> "Connected · first sync pending"
                        else -> "Connected · last sync ${dateTimeLabel(status.lastRunMillis)}"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (status.connected && status.lastRunMillis > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Last sync: ${status.lastScanned} emails checked, ${status.lastDetected} transactions found.\n" +
                                "Total: ${status.totalScanned} checked · ${emails.size} transactions · $added added · " +
                                "${counts["MERGED"] ?: 0} already in SMS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                status.lastError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!status.connected || status.needsReconnect) {
                        Button(onClick = { connect() }, shape = RoundedCornerShape(14.dp)) { Text("Connect Gmail") }
                    }
                    if (status.connected) {
                        OutlinedButton(onClick = onSyncNow, shape = RoundedCornerShape(14.dp)) { Text("Sync now") }
                        TextButton(onClick = onDisconnect) { Text("Disconnect") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Payments that arrive by both SMS and email are recorded once.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(EmailFilter.entries) { option ->
                    val n = if (option.statuses == null) emails.size else option.statuses.sumOf { counts[it] ?: 0 }
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text("${option.label} · $n") },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                EmptyState(
                    visual = Visual(Icons.Rounded.Email, FvColors.Slate),
                    title = if (status.connected) "No transaction emails yet" else "Connect Gmail",
                    body = if (status.connected) "Tap Sync now. Bank and card alert emails from the last 180 days are checked."
                    else "Online card and UPI payments from bank alert emails will show here."
                )
            }
        } else {
            item {
                FvCard(contentPadding = 6.dp) {
                    shown.forEach { EmailRow(it, onClick = { onOpen(it) }) }
                }
            }
        }
    }
}

@Composable
private fun EmailRow(item: SmsObservationEntity, onClick: () -> Unit) {
    val (label, color) = statusLabel(item.status)
    val sign = when (item.suggestedType) {
        "EXPENSE" -> "−"
        "INCOME", "REFUND" -> "+"
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Visual(Icons.Rounded.Email, color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.suggestedTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${SmsTransactionMapper.bankName(item.sender)} · ${shortDate(item.receivedAt)} · $label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Text("$sign${money(item.suggestedAmountMinor)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
