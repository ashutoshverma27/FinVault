package com.finvault.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.finvault.app.data.local.HoldingEntity
import com.finvault.app.email.GmailSync
import com.google.android.gms.auth.api.identity.Identity
import android.net.Uri
import java.util.Locale

@Composable
fun PortfolioScreen(
    holdings: List<HoldingEntity>,
    busy: Boolean,
    contentPadding: PaddingValues,
    onImportCas: (Uri, String) -> Unit,
    onRefreshPrices: () -> Unit,
    notify: (String) -> Unit,
    onOpened: () -> Unit = {}
) {
    androidx.compose.runtime.LaunchedEffect(holdings.isNotEmpty()) { onOpened() }
    val context = LocalContext.current
    var pickedPdf by remember { mutableStateOf<Uri?>(null) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedPdf = uri
    }

    val total = holdings.sumOf { it.valueMinor }
    val mfTotal = holdings.filter { it.assetType == "MF" }.sumOf { it.valueMinor }
    val stockTotal = total - mfTotal

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            FvCard {
                Text("INVESTMENTS", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(money(total), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Mutual funds ${money(mfTotal)} · Stocks ${money(stockTotal)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val asOf = holdings.map { it.priceDate }.filter { it.isNotBlank() }.distinct().take(2).joinToString(" / ")
                Text(
                    "Latest NAV (AMFI) and end-of-day price (NSE)${if (asOf.isNotEmpty()) " · as of $asOf" else ""}. Updates daily.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, enabled = !busy, shape = RoundedCornerShape(14.dp)) {
                        Text("Import CAS PDF")
                    }
                    OutlinedButton(onClick = onRefreshPrices, enabled = !busy && holdings.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                        Text("Update prices")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Mutual funds: detailed CAS from MF Central / CAMS / KFintech. Stocks: CDSL / NSDL CAS.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (holdings.isNotEmpty()) {
            item { SectionHeader("Holdings", trailing = holdings.size.toString()) }
            item {
                FvCard(contentPadding = 8.dp) {
                    holdings.sortedByDescending { it.valueMinor }.forEach { HoldingRow(it) }
                }
            }
        }
    }

    pickedPdf?.let { uri ->
        var password by remember(uri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pickedPdf = null },
            title = { Text("CAS password") },
            text = {
                Column {
                    Text(
                        "CAS PDFs are password protected — usually your PAN in capitals (CAMS / KFintech / MF Central), " +
                                "or as set when you requested it from CDSL / NSDL.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text("Password") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onImportCas(uri, password); pickedPdf = null }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pickedPdf = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HoldingRow(holding: HoldingEntity) {
    val visual = if (holding.assetType == "MF") Visual(Icons.AutoMirrored.Rounded.TrendingUp, FvColors.Purple)
    else Visual(Icons.Rounded.ShowChart, FvColors.Sky)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(visual)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(holding.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2)
            Text(
                "${"%,.3f".format(Locale.US, holding.units)} × ₹${"%,.2f".format(Locale.US, holding.price)} · ${holding.priceDate}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(money(holding.valueMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
