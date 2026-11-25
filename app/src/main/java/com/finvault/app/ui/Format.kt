package com.finvault.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val inrLocale = Locale("en", "IN")

/** "₹1,250" for whole rupees, "₹1,250.50" otherwise. */
fun formatInr(amountMinor: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(inrLocale)
    val whole = amountMinor % 100 == 0L
    formatter.minimumFractionDigits = if (whole) 0 else 2
    formatter.maximumFractionDigits = if (whole) 0 else 2
    return formatter.format(BigDecimal.valueOf(amountMinor, 2))
}

/** Compact label for charts: ₹950, ₹12.5K, ₹1.2L, ₹3.4Cr. */
fun formatInrCompact(amountMinor: Long): String {
    val rupees = abs(amountMinor) / 100.0
    val sign = if (amountMinor < 0) "-" else ""
    val text = when {
        rupees >= 1_00_00_000 -> trim(rupees / 1_00_00_000) + "Cr"
        rupees >= 1_00_000 -> trim(rupees / 1_00_000) + "L"
        rupees >= 1_000 -> trim(rupees / 1_000) + "K"
        else -> trim(rupees)
    }
    return "$sign₹$text"
}

private fun trim(value: Double): String {
    val rounded = BigDecimal(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
    return rounded.toPlainString()
}

/** Formats an amount, honouring privacy mode. */
@Composable
@ReadOnlyComposable
fun money(amountMinor: Long, compact: Boolean = false): String =
    if (LocalHideAmounts.current) "₹ ••••"
    else if (compact) formatInrCompact(amountMinor)
    else formatInr(amountMinor)

fun parseAmountInput(value: String): Long? = try {
    BigDecimal(value.trim().replace(",", "").replace("₹", ""))
        .setScale(2, RoundingMode.UNNECESSARY)
        .movePointRight(2)
        .longValueExact()
        .takeIf { it > 0 }
} catch (_: Exception) {
    null
}

fun amountForInput(amountMinor: Long): String =
    BigDecimal.valueOf(amountMinor, 2).stripTrailingZeros().toPlainString()

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
private val dayYearFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) date.format(dayFormatter) else date.format(dayYearFormatter)
    }
}

fun shortDate(timestamp: Long): String {
    val date = timestamp.toLocalDate()
    return when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> date.format(shortDateFormatter)
    }
}

fun timeOfDay(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(timeFormatter)

fun dateTimeLabel(timestamp: Long): String =
    "${dayLabel(timestamp.toLocalDate())}, ${timeOfDay(timestamp)}"

/** "VM-HDFCBK-S" -> "HDFCBK". Leaves phone numbers and short codes alone. */
fun prettySender(sender: String): String {
    val parts = sender.trim().split('-')
    val core = when {
        parts.size >= 2 && parts[0].length == 2 && parts[1].any { it.isLetter() } -> parts[1]
        else -> sender.trim()
    }
    return core.ifBlank { "Unknown sender" }
}
