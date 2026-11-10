package com.jaison.minipos.pos

import com.jaison.minipos.domain.MenuItem
import com.jaison.minipos.domain.Money
import com.jaison.minipos.domain.Order
import com.jaison.minipos.domain.SampleMenu
import com.jaison.minipos.printer.FakeReceiptPrinter
import com.jaison.minipos.printer.PrintResult
import com.jaison.minipos.printer.PrinterError
import com.jaison.minipos.printer.PrinterStatus
import com.jaison.minipos.printer.ReceiptFormatter
import com.jaison.minipos.printer.ReceiptPrinter
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the kitchen slip looks like, and what happens when it fails after the receipt printed. */
class KitchenTicketTest {
    private val burger = SampleMenu.items.first()
    private val fries = SampleMenu.items.first { it.id == "fries" }

    private fun ByteArray.asText() = String(this, Charsets.ISO_8859_1)

    /** Fails the Nth print (1-based) with [error], and behaves like the fake otherwise. */
    private class FailsOnNthPrint(
        private val inner: FakeReceiptPrinter,
        private val failOn: Int,
        private val error: PrinterError = PrinterError.IO_ERROR,
    ) : ReceiptPrinter by inner {
        private var prints = 0
        override val status: StateFlow<PrinterStatus> get() = inner.status
        override suspend fun print(data: ByteArray): PrintResult {
            prints++
            if (prints == failOn) inner.failNextPrint = error
            return inner.print(data)
        }
    }

    private fun controller(printer: ReceiptPrinter, tickets: Boolean = true) =
        PosController(printer, printKitchenTickets = tickets, timestamp = { "2026-11-05 12:00" })

    // ---- The slip itself ----

    @Test
    fun ticketShowsEveryItemAndNoPrices() {
        val order = Order().add(burger).add(burger).add(fries)
        val text = ReceiptFormatter().kitchenTicket(order, 7, "2026-11-05 12:00").toPlainText()

        assertTrue(text.contains("2 x Classic Burger"))
        assertTrue(text.contains("1 x Fries"))
        assertFalse("kitchen slip must not show prices", text.contains("$"))
        assertFalse(text.contains("TOTAL"))
    }

    @Test
    fun ticketHasALargeOrderNumberSoItCanBeReadAcrossTheKitchen() {
        val ticket = ReceiptFormatter().kitchenTicket(Order().add(burger), 7, "2026-11-05 12:00")
        val numberLine = ticket.lines.first { it.text == "Order #0007" }
        assertTrue(numberLine.large)
        assertTrue(numberLine.bold)
    }

    @Test
    fun ticketLinesFitBothPaperWidths() {
        val longName = MenuItem("long", "Extra Large Supreme Loaded Nachos Platter With Cheese", Money(1299))
        val order = Order().add(longName).add(burger)
        listOf(32, 48).forEach { width ->
            val ticket = ReceiptFormatter(width = width).kitchenTicket(order, 12, "2026-11-05 12:00")
            ticket.lines.filter { !it.large }.forEach { line ->
                assertTrue("Too wide at $width: '${line.text}'", line.text.length <= width)
            }
        }
    }

    // ---- Printing the slip ----

    @Test
    fun ticketPrintsAfterTheReceiptWhenEnabled() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(printer)
        controller.addItem(burger)

        assertTrue(controller.checkout())

        assertEquals(2, printer.printedJobs.size)
        assertTrue("first job is the customer receipt", printer.printedJobs[0].asText().contains("TOTAL"))
        assertTrue("second job is the kitchen ticket", printer.printedJobs[1].asText().contains("KITCHEN"))
        assertFalse(printer.printedJobs[1].asText().contains("TOTAL"))
        val state = controller.state.value
        assertFalse(state.isPrinting)
        assertTrue(state.pendingTickets.isEmpty())
    }

    @Test
    fun noTicketWhenTheFeatureIsOff() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(printer, tickets = false)
        controller.addItem(burger)

        assertTrue(controller.checkout())
        assertEquals(1, printer.printedJobs.size)
    }

    @Test
    fun ifTheReceiptFailsNoTicketPrintsAndTheOrderIsKept() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(printer)
        controller.addItem(burger)
        printer.failNextPrint = PrinterError.PAPER_OUT

        assertFalse(controller.checkout())

        val state = controller.state.value
        assertEquals(1, state.order.itemCount)
        assertEquals(1, state.orderNumber)
        assertTrue(state.pendingTickets.isEmpty())
        assertEquals(0, printer.printedJobs.size)
    }

    // ---- The receipt printed but the ticket did not ----

    @Test
    fun ticketFailureAfterTheReceiptKeepsTheSaleCompleteAndQueuesTheTicket() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(FailsOnNthPrint(printer, failOn = 2))
        controller.addItem(burger)

        assertTrue("the sale is complete once the customer has a receipt", controller.checkout())

        val state = controller.state.value
        assertTrue("order cleared, receipt must not print twice", state.order.isEmpty)
        assertEquals(2, state.orderNumber)
        assertEquals(1, state.pendingTickets.size)
        assertEquals(1, state.pendingTickets.single().orderNumber)
        assertFalse("lock released", state.isPrinting)
        assertTrue(state.message!!.contains("Reprint kitchen ticket"))
        assertEquals("only the receipt reached paper", 1, printer.printedJobs.size)
    }

    @Test
    fun theSimulatedPrinterCanFailJustTheTicketForDemos() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(printer)
        controller.addItem(burger)
        printer.printsBeforeFailure = 1 // let the receipt through, fail the ticket behind it
        printer.failNextPrint = PrinterError.PAPER_OUT

        assertTrue(controller.checkout())

        assertEquals(1, printer.printedJobs.size)
        assertEquals(1, controller.state.value.pendingTickets.size)
        assertTrue(controller.state.value.message!!.contains("out of paper"))
    }

    @Test
    fun reprintAfterFixingThePrinterPrintsTheWaitingTicket() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(FailsOnNthPrint(printer, failOn = 2))
        controller.addItem(burger)
        controller.checkout()

        assertTrue(controller.reprintKitchenTickets())

        assertEquals(2, printer.printedJobs.size)
        assertTrue(printer.printedJobs[1].asText().contains("Order #0001"))
        assertTrue(controller.state.value.pendingTickets.isEmpty())
        assertFalse(controller.state.value.isPrinting)
    }

    @Test
    fun aFailedReprintKeepsTheTicketWaiting() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(FailsOnNthPrint(printer, failOn = 2))
        controller.addItem(burger)
        controller.checkout()
        printer.failNextPrint = PrinterError.PAPER_OUT

        assertFalse(controller.reprintKitchenTickets())

        assertEquals(1, controller.state.value.pendingTickets.size)
        assertFalse(controller.state.value.isPrinting)
    }

    @Test
    fun nextCheckoutCatchesUpTheBacklogInOrder() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(FailsOnNthPrint(printer, failOn = 2))
        controller.addItem(burger)
        controller.checkout() // order 1: receipt ok, ticket fails
        controller.addItem(fries)

        assertTrue(controller.checkout()) // order 2

        // receipt 1, receipt 2, then ticket 1 before ticket 2
        assertEquals(4, printer.printedJobs.size)
        assertTrue(printer.printedJobs[2].asText().contains("Order #0001"))
        assertTrue(printer.printedJobs[3].asText().contains("Order #0002"))
        assertTrue(controller.state.value.pendingTickets.isEmpty())
    }

    @Test
    fun reprintWithNothingWaitingDoesNothing() = runBlocking {
        val printer = FakeReceiptPrinter()
        val controller = controller(printer)

        assertFalse(controller.reprintKitchenTickets())
        assertEquals(0, printer.printedJobs.size)
    }

    @Test
    fun closingTheScreenWhileTheTicketPrintsDoesNotUndoTheSale() = runBlocking {
        val printer = FakeReceiptPrinter(delayMs = 100)
        val controller = controller(printer)
        controller.addItem(burger)

        val job = launch { controller.checkout() }
        // Wait until the receipt is done and the ticket is the thing being printed.
        var waited = 0
        while (controller.state.value.pendingTickets.isEmpty() && waited < 2_000) {
            delay(5)
            waited += 5
        }
        assertTrue("ticket should be in progress", controller.state.value.isPrinting)
        job.cancelAndJoin()

        val state = controller.state.value
        assertFalse("lock must be released", state.isPrinting)
        assertTrue("sale stays complete", state.order.isEmpty)
        assertEquals(2, state.orderNumber)
        assertEquals("ticket still waiting", 1, state.pendingTickets.size)
        assertEquals("receipt printed once", 1, printer.printedJobs.size)

        assertTrue("and it can be reprinted", controller.reprintKitchenTickets())
        assertEquals(2, printer.printedJobs.size)
    }
}
