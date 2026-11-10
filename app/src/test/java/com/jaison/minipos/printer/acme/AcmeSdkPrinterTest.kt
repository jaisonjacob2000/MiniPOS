package com.jaison.minipos.printer.acme

import com.jaison.minipos.domain.SampleMenu
import com.jaison.minipos.pos.PosController
import com.jaison.minipos.printer.PrintResult
import com.jaison.minipos.printer.PrinterContractTest
import com.jaison.minipos.printer.PrinterError
import com.jaison.minipos.printer.PrinterStatus
import com.jaison.minipos.printer.ReceiptPrinter
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vendor-SDK printer has to obey exactly the same rules as every other printer. */
class AcmeSdkPrinterContractTest : PrinterContractTest() {
    override fun createPrinter(): ReceiptPrinter = AcmeSdkPrinter(SimulatedAcmeSdk(latencyMs = 1))
}

/** Everything specific to wrapping a callback-style, closed-source SDK. */
class AcmeSdkPrinterTest {
    private val sdk = SimulatedAcmeSdk(latencyMs = 5)
    private val printer = AcmeSdkPrinter(sdk, timeoutMs = 300)
    private val sample = byteArrayOf(0x1B, 0x40, 0x48, 0x49, 0x0A)

    private fun failureOf(result: PrintResult) = result as PrintResult.Failure

    @Test
    fun bytesReachTheSdkUnchanged() = runBlocking {
        printer.connect()
        assertEquals(PrintResult.Success, printer.print(sample))
        assertEquals(1, sdk.sentJobs.size)
        assertArrayEquals(sample, sdk.sentJobs.single())
    }

    @Test
    fun theSdkAnswersOnItsOwnThreadAndTheResultStillComesBack() = runBlocking {
        printer.connect()
        printer.print(sample)
        val callbackThread = sdk.lastCallbackThread
        assertNotNull(callbackThread)
        assertEquals("acme-sdk", callbackThread!!.name)
        assertNotEquals(Thread.currentThread(), callbackThread)
    }

    @Test
    fun vendorCodesAreTranslatedToAppErrors() {
        assertEquals(PrinterError.PAPER_OUT, mapAcmeCode(AcmeCode.PAPER_EMPTY))
        assertEquals(PrinterError.COVER_OPEN, mapAcmeCode(AcmeCode.COVER_OPEN))
        assertEquals(PrinterError.NOT_CONNECTED, mapAcmeCode(AcmeCode.OFFLINE))
        assertEquals(PrinterError.IO_ERROR, mapAcmeCode(AcmeCode.TIMEOUT))
        assertEquals(PrinterError.UNKNOWN, mapAcmeCode(AcmeCode.BUSY))
    }

    @Test
    fun aCodeFromAFutureSdkVersionBecomesUnknownAndKeepsTheCode() = runBlocking {
        printer.connect()
        sdk.sendCode = 99
        val failure = failureOf(printer.print(sample))
        assertEquals(PrinterError.UNKNOWN, failure.error)
        assertTrue("code must survive for the logs", failure.detail!!.contains("99"))
    }

    @Test
    fun paperEmptyDuringPrintBecomesPaperOutAndErrorStatus() = runBlocking {
        printer.connect()
        sdk.sendCode = AcmeCode.PAPER_EMPTY
        assertEquals(PrinterError.PAPER_OUT, failureOf(printer.print(sample)).error)
        assertEquals(PrinterStatus.ERROR, printer.status.value)
        assertEquals(0, sdk.sentJobs.size)
    }

    @Test
    fun offlinePrinterOnConnectBecomesNotConnected() = runBlocking {
        sdk.openCode = AcmeCode.OFFLINE
        assertEquals(PrinterError.NOT_CONNECTED, failureOf(printer.connect()).error)
        assertEquals(PrinterStatus.ERROR, printer.status.value)
    }

    @Test(timeout = 5_000)
    fun silentSdkTimesOutInsteadOfHangingForever() = runBlocking {
        printer.connect()
        sdk.silent = true
        val started = System.nanoTime()

        val failure = failureOf(printer.print(sample))

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(PrinterError.IO_ERROR, failure.error)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(PrinterStatus.ERROR, printer.status.value)
        assertEquals("timeout must tell the SDK to stop", 1, sdk.abortCount.get())
    }

    @Test
    fun duplicateCallbacksDoNotCrashTheSdkThread() = runBlocking {
        sdk.duplicateReplies = true
        printer.connect()
        assertEquals(PrintResult.Success, printer.print(sample))
        Thread.sleep(50) // give the SDK thread time to deliver the second, unwanted reply
        assertTrue("callback errors: ${sdk.callbackErrors}", sdk.callbackErrors.isEmpty())
    }

    @Test
    fun replyThatArrivesBeforeTheCallReturnsStillWorks() = runBlocking {
        sdk.synchronous = true
        assertEquals(PrintResult.Success, printer.connect())
        assertEquals(PrintResult.Success, printer.print(sample))
        assertEquals(1, sdk.sentJobs.size)
    }

    @Test
    fun anExceptionFromTheSdkIsReportedNotThrown() = runBlocking {
        printer.connect()
        sdk.throwOnSend = true
        val failure = failureOf(printer.print(sample))
        assertEquals(PrinterError.UNKNOWN, failure.error)
        assertTrue(failure.detail!!.contains("IllegalStateException"))
        assertEquals(PrinterStatus.ERROR, printer.status.value)
    }

    @Test
    fun cancellingAPrintAbortsTheSdkAndLeavesThePrinterRecoverable() = runBlocking {
        val slowSdk = SimulatedAcmeSdk(latencyMs = 300)
        val slowPrinter = AcmeSdkPrinter(slowSdk, timeoutMs = 5_000)
        slowPrinter.connect()

        val job = launch { slowPrinter.print(sample) }
        yield() // let the print start and wait for the SDK
        assertEquals(PrinterStatus.PRINTING, slowPrinter.status.value)
        job.cancelAndJoin()

        assertEquals(1, slowSdk.abortCount.get())
        assertEquals(PrinterStatus.ERROR, slowPrinter.status.value)
        Thread.sleep(400) // the aborted job must never reach the paper
        assertEquals(0, slowSdk.sentJobs.size)

        // A fresh attempt works: connect() sees the SDK is still open and print() succeeds.
        assertEquals(PrintResult.Success, slowPrinter.connect())
        assertEquals(PrintResult.Success, slowPrinter.print(sample))
        assertEquals(1, slowSdk.sentJobs.size)
    }

    @Test
    fun closeIsSafeEvenWhenTheSdkObjects() {
        val grumpy = object : AcmeSdk by sdk {
            override fun shutdown() = throw IllegalStateException("already closed")
        }
        val p = AcmeSdkPrinter(grumpy)
        p.close()
        p.close()
        assertEquals(PrinterStatus.DISCONNECTED, p.status.value)
    }

    @Test
    fun theWholeCheckoutWorksOnTheVendorPrinterAndRecoversFromPaperOut() = runBlocking {
        val controller = PosController(printer, timestamp = { "2026-11-05 12:00" })
        controller.addItem(SampleMenu.items.first())
        sdk.sendCode = AcmeCode.PAPER_EMPTY

        assertFalse(controller.checkout())
        assertEquals("order is kept", 1, controller.state.value.order.itemCount)
        assertTrue(controller.state.value.message!!.contains("out of paper"))

        sdk.sendCode = AcmeCode.OK // cashier loads paper
        assertTrue(controller.checkout())
        assertEquals(1, sdk.sentJobs.size)
        assertEquals(2, controller.state.value.orderNumber)
    }
}
