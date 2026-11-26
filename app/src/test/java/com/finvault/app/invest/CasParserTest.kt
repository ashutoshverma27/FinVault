package com.finvault.app.invest

import org.junit.Assert.assertEquals
import org.junit.Test

class CasParserTest {

    @Test
    fun camsDetailedCas_readsUnitsNavAndMergesFolios() {
        val text = """
            Folio No: 12345678 / 90 PAN: ABCDE1234F
            B205RG-HDFC Balanced Advantage Fund - Direct Plan - Growth - ISIN: INF179K01VQ8(Advisor: DIRECT) Registrar : CAMS
            Opening Unit Balance: 100.000
            Closing Unit Balance: 1,234.567 NAV on 24-Nov-2026: INR 456.789 Total Cost Value: 4,00,000.00 Market Value on 24-Nov-2026: INR 5,63,940.77
            Folio No: 99999 / 11
            HDFC Balanced Advantage Fund - Direct Plan - Growth - ISIN: INF179K01VQ8 Registrar : CAMS
            Closing Unit Balance: 10.000 NAV on 24-Nov-2026: INR 456.789
            P8-Parag Parikh Flexi Cap Fund - Direct Plan Growth - ISIN: INF879O01027 Registrar : KFINTECH
            Closing Unit Balance: 500.125
            NAV on 24-Nov-2026: INR 85.12
        """.trimIndent()
        val holdings = CasParser.parse(text).associateBy { it.isin }
        assertEquals(2, holdings.size)
        val hdfc = holdings.getValue("INF179K01VQ8")
        assertEquals(1244.567, hdfc.units, 0.0001)
        assertEquals(456.789, hdfc.price, 0.0001)
        assertEquals("24-Nov-2026", hdfc.priceDate)
        assertEquals("MF", hdfc.assetType)
        assertEquals("HDFC Balanced Advantage Fund - Direct Plan - Growth", hdfc.name)
        assertEquals(500.125, holdings.getValue("INF879O01027").units, 0.0001)
    }

    @Test
    fun cdslCas_readsStockRows() {
        val text = """
            Statement for the period from 01-10-2026 to 31-10-2026
            ISIN Security Current Bal Free Bal Market Price Value
            INE002A01018 RELIANCE INDUSTRIES LIMITED 10 10 1,234.50 12,345.00
            INE009A01021 INFOSYS LIMITED 25.000 25.000 1,500.00 37,500.00
        """.trimIndent()
        val holdings = CasParser.parse(text).associateBy { it.isin }
        val reliance = holdings.getValue("INE002A01018")
        assertEquals("STOCK", reliance.assetType)
        assertEquals(10.0, reliance.units, 0.0001)
        assertEquals(1234.5, reliance.price, 0.0001)
        assertEquals("RELIANCE INDUSTRIES LIMITED", reliance.name)
        assertEquals(25.0, holdings.getValue("INE009A01021").units, 0.0001)
    }

    @Test
    fun amfiNavAll_mapsBothIsins() {
        val text = """
            Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date

            Open Ended Schemes(Equity Scheme - Flexi Cap Fund)
            122639;INF879O01027;-;Parag Parikh Flexi Cap Fund - Direct Plan - Growth;86.4321;25-Nov-2026
            118989;INF179K01VQ8;INF179K01VR6;HDFC Balanced Advantage Fund;460.10;25-Nov-2026
        """.trimIndent()
        val nav = AmfiNav.parse(
            text + "\n148567;INF846K01EW2;-;Axis ELSS Tax Saver Fund;Direct;Growth;98.7654;25-Nov-2026"
        )
        assertEquals(98.7654, nav.getValue("INF846K01EW2").price, 0.00001)
        assertEquals(86.4321, nav.getValue("INF879O01027").price, 0.00001)
        assertEquals("25-Nov-2026", nav.getValue("INF179K01VR6").date)
    }

    @Test
    fun nseBhavcopy_readsClosePrice() {
        val csv = """
            TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric
            2026-11-25,2026-11-25,CM,NSE,STK,2885,INE002A01018,RELIANCE,EQ,,,,,RELIANCE INDUSTRIES LTD,1250,1260,1240,1255.65
        """.trimIndent()
        val prices = NseBhavcopy.parseCsv(csv)
        assertEquals(1255.65, prices.getValue("INE002A01018").price, 0.0001)
    }
}
