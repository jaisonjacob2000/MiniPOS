package com.jaison.minipos.printer

import com.jaison.minipos.domain.MenuItem
import com.jaison.minipos.domain.Money
import com.jaison.minipos.domain.Order
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTest {
    private val formatter = ReceiptFormatter(width = 32)
    private val longName = MenuItem("long", "Extra Large Supreme Loaded Nachos Platter", Money(1299))
    private val fries = MenuItem("fries", "Fries", Money(299))

    private fun receipt() = formatter.customerReceipt(
        Order().add(fries).add(fries).add(longName), orderNumber = 7, timestamp = "2026-10-28 18:30",
    )

    @Test
    fun noLineIsWiderThanThePaper() {
        receipt().lines.filter { !it.large }.forEach { line ->
            assertTrue("Too wide: '${line.text}'", line.text.length <= 32)
        }
    }

    @Test
    fun itemLinesPutPriceAtTheRightEdge() {
        val friesLine = receipt().lines.first { it.text.startsWith("2 x Fries") }
        assertEquals(32, friesLine.text.length)
        assertTrue(friesLine.text.endsWith("$5.98"))
    }

    @Test
    fun longNamesAreTruncatedNotWrapped() {
        val line = receipt().lines.first { it.text.startsWith("1 x Extra") }.text
        assertEquals(32, line.length)
        assertTrue(line.endsWith("$12.99"))
        assertTrue(line.contains(". "))
    }

    @Test
    fun receiptShowsOrderNumberAndTotal() {
        val text = receipt().toPlainText()
        assertTrue(text.contains("Order #0007"))
        assertTrue(text.contains("TOTAL"))
    }

    @Test
    fun encoderWrapsReceiptInInitAndCut() {
        val bytes = EscPosEncoder().encode(receipt())
        assertArrayEquals(EscPosEncoder.INITIALIZE, bytes.copyOfRange(0, 2))
        assertArrayEquals(EscPosEncoder.PARTIAL_CUT, bytes.copyOfRange(bytes.size - 4, bytes.size))
    }

    @Test
    fun encoderTurnsOnBoldAndLargeForHeader() {
        val header = Receipt(listOf(ReceiptLine("HI", Align.CENTER, bold = true, large = true)), 32)
        val bytes = EscPosEncoder(cutPaper = false).encode(header).toList()
        val expectedStart = (EscPosEncoder.INITIALIZE + EscPosEncoder.align(Align.CENTER) +
            EscPosEncoder.bold(true) + EscPosEncoder.size(true) + "HI".toByteArray()).toList()
        assertEquals(expectedStart, bytes.take(expectedStart.size))
    }

    @Test
    fun nonAsciiCharactersPrintAsQuestionMarks() {
        assertEquals("Jalape?o", String(EscPosEncoder.toPrinterText("Jalapeño")))
    }
}
