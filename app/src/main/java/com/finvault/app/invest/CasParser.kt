package com.finvault.app.invest

import java.util.Locale

/** A holding read from a CAS statement's text. */
data class CasHolding(
    val isin: String,
    val name: String,
    /** MF or STOCK */
    val assetType: String,
    val units: Double,
    val price: Double,
    val priceDate: String
)

/**
 * Reads holdings out of the text of a Consolidated Account Statement.
 *
 *  - CAMS / KFintech / MF Central detailed CAS: each scheme block has
 *    "... - ISIN: INF179K01VQ8 ..." followed later by
 *    "Closing Unit Balance: 1,234.567  NAV on 24-Nov-2026: INR 456.789".
 *  - CDSL / NSDL CAS and summary statements: one row per holding with the
 *    ISIN and numbers where quantity x price ~= value.
 *
 * Mutual fund ISINs start with "INF"; equity ISINs with "INE".
 */
object CasParser {

    private val isinPattern = Regex("""\b(IN[EF][A-Z0-9]{9})\b""")
    private val closingUnitsPattern = Regex("""(?i)closing\s+unit\s+balance\s*:?\s*([0-9][0-9,]*\.?[0-9]*)""")
    private val navPattern = Regex("""(?i)\bNAV\s+on\s+([0-9]{1,2}[-/ ][A-Za-z0-9]{2,3}[-/ ][0-9]{2,4})\s*:?\s*(?:INR|Rs\.?|₹)?\s*([0-9][0-9,]*\.?[0-9]*)""")
    private val numberPattern = Regex("""(?<![A-Za-z0-9])([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]+)?|[0-9]+(?:\.[0-9]+)?)(?![A-Za-z0-9])""")
    private val datePattern = Regex("""\b([0-9]{1,2}[-/ ](?:[A-Za-z]{3}|[0-9]{2})[-/ ][0-9]{4})\b""")

    fun parse(text: String): List<CasHolding> {
        val detailed = parseDetailedMf(text)
        val rows = parseRows(text).filter { row -> detailed.none { it.isin == row.isin } }
        return merge(detailed + rows)
    }

    /** Same ISIN in several folios / demat accounts: add the units up. */
    private fun merge(items: List<CasHolding>): List<CasHolding> =
        items.groupBy { it.isin }.map { (_, list) ->
            list.first().copy(units = list.sumOf { it.units })
        }.filter { it.units > 0.0005 }

    private fun parseDetailedMf(text: String): List<CasHolding> {
        val result = mutableListOf<CasHolding>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var isin: String? = null
        var name = ""
        var units: Double? = null

        for ((index, line) in lines.withIndex()) {
            val isinMatch = isinPattern.find(line)
            if (isinMatch != null && isinMatch.value.startsWith("INF") && line.contains("ISIN", ignoreCase = true)) {
                isin = isinMatch.value
                name = schemeName(line, lines.getOrNull(index - 1))
                units = null
            }
            closingUnitsPattern.find(line)?.let { units = toDouble(it.groupValues[1]) }
            val nav = navPattern.find(line)
            val currentIsin = isin
            val currentUnits = units
            if (nav != null && currentIsin != null && currentUnits != null) {
                val price = toDouble(nav.groupValues[2])
                if (price != null) {
                    result += CasHolding(currentIsin, name, "MF", currentUnits, price, nav.groupValues[1])
                }
                isin = null
                units = null
            }
        }
        return result
    }

    private fun schemeName(line: String, previous: String?): String {
        var name = line.substringBefore("ISIN", line).trim().trimEnd('-', ' ', '(')
        // "B205RG-HDFC Balanced Advantage Fund - Direct Growth" -> drop the scheme code.
        name = name.replace(Regex("""^[A-Z0-9]{2,10}-"""), "").trim()
        if (name.length < 4 && previous != null) name = previous
        return name.take(80)
    }

    /** Rows like "INE002A01018 RELIANCE INDUSTRIES LTD 10 1,234.50 12,345.00". */
    private fun parseRows(text: String): List<CasHolding> {
        val result = mutableListOf<CasHolding>()
        val statementDate = datePattern.find(text)?.groupValues?.get(1).orEmpty()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            val isinMatch = isinPattern.find(line) ?: continue
            val isin = isinMatch.value
            val afterIsin = line.substring(isinMatch.range.last + 1)
            val numbers = numberPattern.findAll(afterIsin).mapNotNull { toDouble(it.value) }.toList()
            if (numbers.size < 3) continue
            val match = quantityTimesPrice(numbers) ?: continue
            val name = (line.substring(0, isinMatch.range.first) + " " + afterIsin)
                .replace(numberPattern, " ")
                .replace(Regex("""[#*|]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(80)
                .ifBlank { isin }
            result += CasHolding(
                isin = isin,
                name = name,
                assetType = if (isin.startsWith("INF")) "MF" else "STOCK",
                units = match.first,
                price = match.second,
                priceDate = statementDate
            )
        }
        return result
    }

    /** Finds quantity and price among the numbers such that quantity x price ~= one of the later values. */
    private fun quantityTimesPrice(numbers: List<Double>): Pair<Double, Double>? {
        for (v in numbers.indices.reversed()) {
            val value = numbers[v]
            if (value <= 0) continue
            for (q in 0 until v) for (p in 0 until v) {
                if (q == p) continue
                val qty = numbers[q]
                val price = numbers[p]
                if (qty <= 0 || price <= 0) continue
                if (kotlin.math.abs(qty * price - value) <= maxOf(1.0, value * 0.005)) return qty to price
            }
        }
        return null
    }

    private fun toDouble(text: String): Double? = text.replace(",", "").trimEnd('.').toDoubleOrNull()

    fun normaliseIsin(isin: String) = isin.trim().uppercase(Locale.ROOT)
}
