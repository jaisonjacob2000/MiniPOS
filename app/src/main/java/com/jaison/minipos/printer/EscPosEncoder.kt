package com.jaison.minipos.printer

import java.io.ByteArrayOutputStream

/**
 * Turns a [Receipt] into ESC/POS bytes, the command language almost every thermal receipt
 * printer understands. Written by hand rather than pulled from a library so the protocol
 * is visible: each command is a short escape sequence followed by its arguments.
 */
class EscPosEncoder(private val cutPaper: Boolean = true) {

    fun encode(receipt: Receipt): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INITIALIZE)
        receipt.lines.forEach { line ->
            out.write(align(line.align))
            out.write(bold(line.bold))
            out.write(size(line.large))
            out.write(toPrinterText(line.text))
            out.write(LINE_FEED)
        }
        // Leave the printer in a clean state for whoever prints next.
        out.write(bold(false))
        out.write(size(false))
        out.write(align(Align.LEFT))
        out.write(feedLines(3))
        if (cutPaper) out.write(PARTIAL_CUT)
        return out.toByteArray()
    }

    companion object {
        private const val ESC: Byte = 0x1B
        private const val GS: Byte = 0x1D

        /** ESC @ : reset the printer to its defaults. */
        val INITIALIZE = byteArrayOf(ESC, 0x40)

        /** GS V 66 0 : feed to the cutter and partially cut. Printers without a cutter ignore it. */
        val PARTIAL_CUT = byteArrayOf(GS, 0x56, 0x42, 0x00)

        val LINE_FEED = byteArrayOf(0x0A)

        /** ESC a n : 0 = left, 1 = center, 2 = right. */
        fun align(align: Align): ByteArray = byteArrayOf(ESC, 0x61, align.ordinal.toByte())

        /** ESC E n : 1 = bold on, 0 = bold off. */
        fun bold(on: Boolean): ByteArray = byteArrayOf(ESC, 0x45, if (on) 1 else 0)

        /** GS ! n : 0x11 = double width and height, 0x00 = normal. */
        fun size(large: Boolean): ByteArray = byteArrayOf(GS, 0x21, if (large) 0x11 else 0x00)

        /** ESC d n : feed n blank lines. */
        fun feedLines(n: Int): ByteArray = byteArrayOf(ESC, 0x64, n.coerceIn(0, 255).toByte())

        /** Cheap printers only reliably support plain ASCII, so anything else prints as '?'. */
        fun toPrinterText(text: String): ByteArray =
            text.map { c -> if (c.code in 0x20..0x7E) c.code.toByte() else '?'.code.toByte() }.toByteArray()
    }
}
