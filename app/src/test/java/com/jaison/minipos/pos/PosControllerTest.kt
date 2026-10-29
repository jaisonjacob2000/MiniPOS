package com.jaison.minipos.pos

import com.jaison.minipos.domain.SampleMenu
import com.jaison.minipos.printer.FakeReceiptPrinter
import com.jaison.minipos.printer.PrinterError
import com.jaison.minipos.printer.PrinterStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosControllerTest {
    private val printer = FakeReceiptPrinter()
    private val controller = PosController(printer, timestamp = { "2026-10-28 18:30" })
    private val burger = SampleMenu.items.first()

    @Test
    fun successfulCheckoutPrintsClearsOrderAndAdvancesNumber() = runBlocking {
        controller.addItem(burger)
        assertTrue(controller.checkout())

        assertEquals(1, printer.printedJobs.size)
        val state = controller.state.value
        assertTrue(state.order.isEmpty)
        assertEquals(2, state.orderNumber)
        assertTrue(state.lastReceipt!!.contains("Order #0001"))
    }

    @Test
    fun paperOutKeepsTheOrderSoNothingIsLost() = runBlocking {
        controller.addItem(burger)
        printer.failNextPrint = PrinterError.PAPER_OUT

        assertFalse(controller.checkout())

        val state = controller.state.value
        assertEquals(1, state.order.itemCount)
        assertEquals(1, state.orderNumber)
        assertFalse(state.isPrinting)
        assertTrue(state.message!!.contains("out of paper"))
        assertEquals(0, printer.printedJobs.size)
    }

    @Test
    fun retryAfterFailureReconnectsAndPrints() = runBlocking {
        controller.addItem(burger)
        printer.failNextPrint = PrinterError.PAPER_OUT
        controller.checkout()
        assertEquals(PrinterStatus.ERROR, printer.status.value)

        assertTrue(controller.checkout())
        assertEquals(1, printer.printedJobs.size)
        assertEquals(PrinterStatus.READY, printer.status.value)
    }

    @Test
    fun printerThatWontConnectReportsNotConnected() = runBlocking {
        printer.refuseConnection = true
        controller.addItem(burger)

        assertFalse(controller.checkout())
        assertTrue(controller.state.value.message!!.contains(PrinterError.NOT_CONNECTED.userMessage))
    }

    @Test
    fun emptyOrderDoesNotPrint() = runBlocking {
        assertFalse(controller.checkout())
        assertEquals(0, printer.printedJobs.size)
        assertNotNull(controller.state.value.message)
    }

    @Test
    fun switchingPrintersClosesTheOldOneAndFollowsNewStatus() = runBlocking {
        val second = FakeReceiptPrinter(name = "Kitchen printer")
        second.connect()

        controller.usePrinter(second)

        assertEquals(1, printer.closeCount)
        assertEquals(PrinterStatus.READY, controller.printerStatus.first())
        controller.addItem(burger)
        assertTrue(controller.checkout())
        assertEquals(1, second.printedJobs.size)
        assertEquals(0, printer.printedJobs.size)
    }
}
