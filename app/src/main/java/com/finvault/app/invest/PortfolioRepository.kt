package com.finvault.app.invest

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.finvault.app.data.local.FinVaultDatabase
import com.finvault.app.data.local.HoldingEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class CasImportResult(val mutualFunds: Int, val stocks: Int)

class PortfolioRepository(private val context: Context) {

    private val database = FinVaultDatabase.getInstance(context)
    private val dao = database.holdingDao()

    fun observeHoldings(): Flow<List<HoldingEntity>> = dao.observeAll()

    /**
     * Imports a CAS PDF. CAS files are password protected (usually your PAN,
     * or PAN + date of birth for some depositories). A CAS is a full snapshot,
     * so it replaces the holdings of the asset types it contains.
     */
    suspend fun importCas(uri: Uri, password: String): CasImportResult = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input, password).use { document -> PDFTextStripper().getText(document) }
        } ?: error("Could not open the file.")

        val parsed = CasParser.parse(text)
        require(parsed.isNotEmpty()) {
            "No holdings found. Make sure this is a detailed CAS from MF Central / CAMS / KFintech or a CDSL / NSDL CAS."
        }
        val source = when {
            text.contains("CDSL", true) -> "CDSL CAS"
            text.contains("NSDL", true) -> "NSDL CAS"
            text.contains("KFIN", true) || text.contains("KARVY", true) -> "KFintech CAS"
            text.contains("MF Central", true) || text.contains("MFCentral", true) -> "MF Central CAS"
            else -> "CAMS CAS"
        }
        val holdings = parsed.map {
            HoldingEntity(
                id = "${it.assetType}_${it.isin}",
                isin = it.isin,
                name = it.name,
                assetType = it.assetType,
                units = it.units,
                price = it.price,
                priceDate = it.priceDate,
                source = source
            )
        }
        database.withTransaction {
            holdings.map { it.assetType }.distinct().forEach { dao.deleteType(it) }
            dao.upsertAll(holdings)
        }
        // Bring prices up to today straight away; ignore network failures here.
        runCatching { refreshPrices() }
        CasImportResult(holdings.count { it.assetType == "MF" }, holdings.count { it.assetType == "STOCK" })
    }

    /**
     * Updates every holding to the latest AMFI NAV / NSE end-of-day close and
     * the official scheme / company name. Returns how many holdings were priced.
     * Throws if a needed price source couldn't be reached.
     */
    suspend fun refreshPrices(): Int = withContext(Dispatchers.IO) {
        val holdings = dao.getAll()
        if (holdings.isEmpty()) return@withContext 0
        val errors = mutableListOf<String>()
        val nav = if (holdings.any { it.assetType == "MF" }) {
            runCatching { AmfiNav.fetch() }.onFailure { errors += "AMFI NAV: ${it.message}" }.getOrDefault(emptyMap())
        } else emptyMap()
        val stocks = if (holdings.any { it.assetType == "STOCK" }) {
            runCatching { NseBhavcopy.fetchLatest() }.onFailure { errors += "NSE prices: ${it.message}" }.getOrDefault(emptyMap())
                .also { if (it.isEmpty()) errors += "NSE prices: no bhavcopy for the last 7 days" }
        } else emptyMap()

        var priced = 0
        holdings.forEach { holding ->
            val quote = (if (holding.assetType == "MF") nav else stocks)[holding.isin] ?: return@forEach
            dao.updatePrice(holding.id, quote.price, quote.date, quote.name ?: holding.name)
            priced++
        }
        prefs.edit().putLong(KEY_LAST_REFRESH, System.currentTimeMillis()).apply()
        if (priced == 0 && errors.isNotEmpty()) error(errors.joinToString("; "))
        priced
    }

    private val prefs = context.getSharedPreferences("finvault_portfolio", Context.MODE_PRIVATE)

    fun lastRefreshMillis() = prefs.getLong(KEY_LAST_REFRESH, 0L)

    private companion object {
        const val KEY_LAST_REFRESH = "last_price_refresh"
    }
}
