package com.jaison.minipos.printer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rules EVERY printer must follow, whatever the hardware. When a new device is added
 * (USB, network, a vendor SDK), it gets its own small subclass and must pass all of these.
 */
abstract class PrinterContractTest {
    abstract fun createPrinter(): ReceiptPrinter

    private val sample = byteArrayOf(0x1B, 0x40, 0x48, 0x49, 0x0A)

    @Test
    fun startsDisconnected() {
        assertEquals(PrinterStatus.DISCONNECTED, createPrinter().status.value)
    }

    @Test
    fun printBeforeConnectFailsCleanly() = runBlocking {
        val result = createPrinter().print(sample)
        assertEquals(PrintResult.Failure(PrinterError.NOT_CONNECTED), result)
    }

    @Test
    fun connectThenPrintLeavesPrinterReady() = runBlocking {
        val printer = createPrinter()
        assertEquals(PrintResult.Success, printer.connect())
        assertEquals(PrinterStatus.READY, printer.status.value)
        assertEquals(PrintResult.Success, printer.print(sample))
        assertEquals(PrinterStatus.READY, printer.status.value)
    }

    @Test
    fun closeIsSafeToCallTwice() = runBlocking {
        val printer = createPrinter()
        printer.connect()
        printer.close()
        printer.close()
        assertEquals(PrinterStatus.DISCONNECTED, printer.status.value)
    }
}

/** The simulated printer has to obey the same contract as real hardware. */
class FakeReceiptPrinterContractTest : PrinterContractTest() {
    override fun createPrinter(): ReceiptPrinter = FakeReceiptPrinter()
}