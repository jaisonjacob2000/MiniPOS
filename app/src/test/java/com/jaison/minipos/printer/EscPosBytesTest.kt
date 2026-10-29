package com.jaison.minipos.printer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the exact bytes sent to the printer, so a protocol change can't slip through. */
class EscPosBytesTest {
    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

    @Test
    fun oneLineReceiptMatchesExpectedBytes() {
        val receipt = Receipt(listOf(ReceiptLine("HI")), width = 32)
        val bytes = EscPosEncoder(cutPaper = false).encode(receipt)

        val expected = listOf(
            "1B 40",            // ESC @   initialize printer
            "1B 61 00",         // ESC a 0 align left
            "1B 45 00",         // ESC E 0 bold off
            "1D 21 00",         // GS ! 0  normal size
            "48 49",            // "HI"
            "0A",               // line feed
            "1B 45 00",         // reset bold
            "1D 21 00",         // reset size
            "1B 61 00",         // reset alignment
            "1B 64 03",         // ESC d 3 feed three lines
        ).joinToString(" ")

        assertEquals(expected, bytes.toHex())
    }
}