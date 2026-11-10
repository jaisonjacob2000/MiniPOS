package com.jaison.minipos.pos

import com.jaison.minipos.domain.SampleMenu
import com.jaison.minipos.printer.FakeReceiptPrinter
import com.jaison.minipos.printer.PrintResult
import com.jaison.minipos.printer.PrinterStatus
import com.jaison.minipos.printer.ReceiptPrinter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failure modes that only show up in production: the screen goes away mid-print, a vendor
 * SDK throws something unexpected, or several taps arrive at the same instant.
 */
class CheckoutRobustnessTest {
    private val burger = SampleMenu.items.first()

    private fun controllerFor(printer: ReceiptPrinter) =
        PosController(printer, timestamp = { "2026-11-04 12:00" })

    /** A printer whose SDK throws instead of returning an error, as real SDKs sometimes do. */
    private class ThrowingPrinter : ReceiptPrinter {
        override val name = "Throwing printer"
        override val status: StateFlow<PrinterStatus> = MutableStateFlow(PrinterStatus.READY)
        override suspend fun connect(): PrintResult = PrintResult.Success
        override suspend fun print(data: ByteArray): PrintResult = throw IllegalStateException("SDK blew up")
        override fun close() = Unit
    }

    @Test
    fun cancelledCheckoutDoesNotLockTheCashierOut() = runBlocking {
        val printer = FakeReceiptPrinter(delayMs = 300)
        val controller = controllerFor(printer)
        controller.addItem(burger)

        val job = launch { controller.checkout() }
        yield() // let the checkout start and suspend inside the printer
        assertTrue("checkout should be in progress", controller.state.value.isPrinting)
        job.cancelAndJoin() // the screen went away mid-print

        val state = controller.state.value
        assertFalse("a cancelled checkout must release the lock", state.isPrinting)
        assertEquals("order must be kept", 1, state.order.itemCount)
        assertEquals("order number must not advance", 1, state.orderNumber)
        assertEquals(0, printer.printedJobs.size)
    }

    @Test
    fun cashierCanPrintAgainAfterACancelledCheckout() = runBlocking {
        val printer = FakeReceiptPrinter(delayMs = 300)
        val controller = controllerFor(printer)
        controller.addItem(burger)

        val job = launch { controller.checkout() }
        yield()
        job.cancelAndJoin()

        assertTrue("retry should work", controller.checkout())
        assertEquals(1, printer.printedJobs.size)
        assertEquals(2, controller.state.value.orderNumber)
    }

    @Test
    fun unexpectedExceptionFromAPrinterKeepsTheOrder() = runBlocking {
        val controller = controllerFor(ThrowingPrinter())
        controller.addItem(burger)

        assertFalse(controller.checkout())

        val state = controller.state.value
        assertFalse("lock must be released", state.isPrinting)
        assertEquals("order must be kept", 1, state.order.itemCount)
        assertEquals(1, state.orderNumber)
        assertTrue(state.message!!.contains("Order kept"))
    }

    @Test
    fun manyTapsAtTheSameInstantPrintExactlyOnce() = runBlocking {
        val printer = FakeReceiptPrinter(delayMs = 20)
        val controller = controllerFor(printer)
        controller.addItem(burger)

        val startGun = CompletableDeferred<Unit>()
        val taps = (1..64).map {
            async(Dispatchers.Default) {
                startGun.await()
                controller.checkout()
            }
        }
        startGun.complete(Unit)
        val results = taps.awaitAll()

        assertEquals("exactly one tap may win", 1, results.count { it })
        assertEquals(1, printer.printedJobs.size)
    }
}
