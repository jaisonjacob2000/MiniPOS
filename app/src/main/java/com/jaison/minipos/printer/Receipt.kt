package com.jaison.minipos.printer

import com.jaison.minipos.domain.Order

enum class Align { LEFT, CENTER, RIGHT }

/** One printed line. [large] means double width and double height on the printer. */
data class ReceiptLine(
    val text: String,
    val align: Align = Align.LEFT,
    val bold: Boolean = false,
    val large: Boolean = false,
)

/**
 * A receipt independent of any printer. The same Receipt can be encoded to ESC/POS bytes
 * or shown on screen as plain text, which is how the simulated printer previews it.
 *
 * [width] is characters per line: 32 for 58 mm paper, 48 for 80 mm paper.
 */
data class Receipt(val lines: List<ReceiptLine>, val width: Int) {
    fun toPlainText(): String = lines.joinToString("\n") { line ->
        val text = line.text.take(width)
        val padding = width - text.length
        when (line.align) {
            Align.LEFT -> text
            Align.CENTER -> " ".repeat(padding / 2) + text
            Align.RIGHT -> " ".repeat(padding) + text
        }
    }
}

class ReceiptFormatter(
    private val storeName: String = "MINI POS",
    private val storeLine: String = "Chicago, IL",
    val width: Int = 32,
) {
    init {
        require(width >= 24) { "Receipt width must be at least 24 characters" }
    }

    fun customerReceipt(order: Order, orderNumber: Int, timestamp: String): Receipt {
        val lines = buildList {
            add(ReceiptLine(storeName, Align.CENTER, bold = true, large = true))
            add(ReceiptLine(storeLine, Align.CENTER))
            add(ReceiptLine(timestamp, Align.CENTER))
            add(ReceiptLine(orderLabel(orderNumber), Align.CENTER, bold = true))
            add(divider())
            order.lines.forEach { line ->
                add(ReceiptLine(twoColumns("${line.quantity} x ${line.item.name}", line.lineTotal.format())))
            }
            add(divider())
            add(ReceiptLine(twoColumns("Subtotal", order.subtotal.format())))
            add(ReceiptLine(twoColumns("Tax", order.tax.format())))
            add(ReceiptLine(twoColumns("TOTAL", order.total.format()), bold = true))
            add(divider())
            add(ReceiptLine("Thank you!", Align.CENTER))
        }
        return Receipt(lines, width)
    }

    fun orderLabel(orderNumber: Int) = "Order #" + orderNumber.toString().padStart(4, '0')

    fun divider() = ReceiptLine("-".repeat(width))

    /** "2 x Bean Burrito          $6.98", truncating the left side if it would collide. */
    fun twoColumns(left: String, right: String): String {
        val maxLeft = width - right.length - 1
        val trimmedLeft = if (left.length > maxLeft) left.take(maxLeft - 1) + "." else left
        return trimmedLeft + " ".repeat(width - trimmedLeft.length - right.length) + right
    }
}
