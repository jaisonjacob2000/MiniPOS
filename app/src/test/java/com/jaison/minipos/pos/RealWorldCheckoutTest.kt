package com.jaison.minipos.pos

import com.jaison.minipos.domain.Order
import com.jaison.minipos.domain.SampleMenu
import com.jaison.minipos.printer.FakeReceiptPrinter
import com.jaison.minipos.printer.PrinterError
import com.jaison.minipos.printer.ReceiptFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Situations a real cashier and a real printer will create. */
class RealWorldCheckoutTest {
    private val burger = SampleMenu.items.first()

    @Test
    fun doubleTapOnPayAndPrintPrintsOnlyOnce() = runBlocking {
        val slowPrinter = FakeReceiptPrinter(delayMs = 200)
        val controller = PosController(slowPrinter, timestamp = { "2026-10-29 12:00" })
        controller.addItem(burger)

        val firstTap = launch { controller.checkout() }
        yield() // let the first tap start printing
        val secondTap = controller.checkout()
        firstTap.join()

        assertFalse("second tap should be ignored", secondTap)
        assertEquals(1, slowPrinter.printedJobs.size)
    }

    @Test
    fun everyPrinterErrorKeepsTheCustomersOrder() = runBlocking {
        PrinterError.values().forEach { error ->
            val printer = FakeReceiptPrinter()
            val controller = PosController(printer, timestamp = { "2026-10-29 12:00" })
            controller.addItem(burger)
            printer.failNextPrint = error

            controller.checkout()

            val state = controller.state.value
            assertEquals("$error lost the order", 1, state.order.itemCount)
            assertEquals("$error advanced the order number", 1, state.orderNumber)
            assertTrue("$error message", state.message!!.contains(error.userMessage))
        }
    }

    @Test
    fun widerEightyMillimeterPaperFitsEveryLine() {
        val formatter = ReceiptFormatter(width = 48)
        var order = Order()
        SampleMenu.items.forEach { order = order.add(it) }

        val receipt = formatter.customerReceipt(order, 1, "2026-10-29 12:00")

        receipt.lines.filter { !it.large }.forEach { line ->
            assertTrue("Too wide: '${line.text}'", line.text.length <= 48)
        }
        assertTrue(receipt.lines.any { it.text.length == 48 })
    }
}