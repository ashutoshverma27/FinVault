package com.finvault.app.invest

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

data class Quote(val price: Double, val date: String, val name: String? = null)

/** AMFI publishes every mutual fund's latest NAV once a day in NAVAll.txt. */
object AmfiNav {

    const val URL_NAV_ALL = "https://portal.amfiindia.com/spages/NAVAll.txt"

    /**
     * "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;[Plan;Option;]Net Asset Value;Date"
     * NAV and date are always the last two columns (AMFI added Plan / Option in 2026).
     */
    fun parse(text: String): Map<String, Quote> {
        val result = HashMap<String, Quote>()
        text.lineSequence().forEach { line ->
            val parts = line.split(';')
            if (parts.size < 6) return@forEach
            val nav = parts[parts.size - 2].trim().toDoubleOrNull() ?: return@forEach
            // Scheme name plus plan / option when AMFI lists them separately.
            val name = parts.subList(3, parts.size - 2).map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
                .distinct().joinToString(" - ")
            val quote = Quote(nav, parts.last().trim(), name.ifBlank { null })
            listOf(parts[1], parts[2]).map { it.trim() }
                .filter { it.length == 12 && it.startsWith("INF") }
                .forEach { result[it] = quote }
        }
        return result
    }

    fun fetch(): Map<String, Quote> = parse(httpGet(URL_NAV_ALL).decodeToString())
}

/** NSE's daily equity bhavcopy (closing prices, with ISIN). */
object NseBhavcopy {

    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun url(date: LocalDate) =
        "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_${date.format(dateFormat)}_F_0000.csv.zip"

    /** Parses the CSV inside the zip: needs the ISIN and ClsPric columns. */
    fun parseCsv(csv: String): Map<String, Quote> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyMap()
        val header = lines.first().split(',').map { it.trim() }
        val isinCol = header.indexOf("ISIN")
        val closeCol = header.indexOf("ClsPric")
        val dateCol = header.indexOf("TradDt")
        val seriesCol = header.indexOf("SctySrs")
        val nameCol = header.indexOf("FinInstrmNm")
        val symbolCol = header.indexOf("TckrSymb")
        if (isinCol < 0 || closeCol < 0) return emptyMap()
        val result = HashMap<String, Quote>()
        lines.drop(1).forEach { line ->
            val cols = line.split(',')
            val isin = cols.getOrNull(isinCol)?.trim() ?: return@forEach
            val close = cols.getOrNull(closeCol)?.trim()?.toDoubleOrNull() ?: return@forEach
            val series = cols.getOrNull(seriesCol)?.trim()
            // Prefer the regular equity series when an ISIN trades in several.
            if (result.containsKey(isin) && series != "EQ") return@forEach
            val name = cols.getOrNull(nameCol)?.trim()?.takeIf { it.isNotEmpty() }
            val symbol = cols.getOrNull(symbolCol)?.trim()?.takeIf { it.isNotEmpty() }
            result[isin] = Quote(
                close, cols.getOrNull(dateCol)?.trim().orEmpty(),
                listOfNotNull(name, symbol?.let { "($it)" }).joinToString(" ").ifBlank { null }
            )
        }
        return result
    }

    /** Latest available trading day within the last week. */
    fun fetchLatest(): Map<String, Quote> {
        var day = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        repeat(7) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) {
                val prices = runCatching { unzipCsv(httpGet(url(day))) }.getOrNull()?.let(::parseCsv)
                if (!prices.isNullOrEmpty()) return prices
            }
            day = day.minusDays(1)
        }
        return emptyMap()
    }

    private fun unzipCsv(bytes: ByteArray): String =
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name.endsWith(".csv", true) }
                ?: error("No CSV in bhavcopy zip")
            BufferedReader(InputStreamReader(zip)).readText()
        }
}

internal fun httpGet(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 60_000
    // NSE rejects requests without a browser-like user agent.
    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) FinVault")
    connection.setRequestProperty("Accept", "*/*")
    try {
        check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for $url" }
        return connection.inputStream.use { it.readBytes() }
    } finally {
        connection.disconnect()
    }
}
