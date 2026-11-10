package com.jaison.minipos.printer.acme

import com.jaison.minipos.printer.PrintResult
import com.jaison.minipos.printer.PrinterError
import com.jaison.minipos.printer.PrinterStatus
import com.jaison.minipos.printer.ReceiptPrinter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Adapts the vendor's callback-style [AcmeSdk] to the app's [ReceiptPrinter] interface.
 *
 * This is the pattern for integrating any third-party SDK (printer, scanner, payment
 * terminal). The rest of the app never sees the vendor's types. This class is responsible for:
 *
 *  1. **Bridging callbacks to suspend functions** with [suspendCancellableCoroutine].
 *  2. **Threads.** The SDK answers on its own thread; resuming a coroutine from any thread is safe.
 *  3. **Never hanging.** A printer that never answers becomes a timeout failure, not a frozen
 *     checkout.
 *  4. **Surviving a misbehaving SDK:** answering twice, answering before the call returns,
 *     throwing exceptions.
 *  5. **Cancellation.** If the caller gives up, tell the SDK to stop and leave the printer in a
 *     state the next attempt can recover from.
 *  6. **Translating errors:** vendor numeric codes become [PrinterError]s the cashier can
 *     understand. Unknown codes, including ones a future SDK version adds, are never lost: they
 *     become [PrinterError.UNKNOWN] with the vendor code kept in the detail for logs.
 *
 * It must pass the same [com.jaison.minipos.printer.PrinterContractTest] as every other printer.
 */
class AcmeSdkPrinter(
    private val sdk: AcmeSdk,
    override val name: String = "Acme printer (vendor SDK)",
    private val timeoutMs: Long = 5_000,
) : ReceiptPrinter {

    private val _status = MutableStateFlow(PrinterStatus.DISCONNECTED)
    override val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    override suspend fun connect(): PrintResult {
        if (isSdkOpen()) {
            _status.value = PrinterStatus.READY
            return PrintResult.Success
        }
        _status.value = PrinterStatus.CONNECTING
        return settle { callback -> sdk.open(callback) }
    }

    override suspend fun print(data: ByteArray): PrintResult {
        if (_status.value != PrinterStatus.READY) return PrintResult.Failure(PrinterError.NOT_CONNECTED)
        _status.value = PrinterStatus.PRINTING
        return settle { callback -> sdk.send(data, callback) }
    }

    override fun close() {
        try {
            sdk.shutdown()
        } catch (ignored: Exception) {
            // Closing must always be safe, even if the SDK objects to being closed twice.
        }
        _status.value = PrinterStatus.DISCONNECTED
    }

    /** Runs one SDK operation and turns its reply into a [PrintResult] and a status. */
    private suspend fun settle(start: (AcmeCallback) -> Unit): PrintResult {
        val reply = try {
            awaitReply(start)
        } catch (e: CancellationException) {
            _status.value = PrinterStatus.ERROR // we do not know what state the device is in
            throw e
        }
        return if (reply.isOk) {
            _status.value = PrinterStatus.READY
            PrintResult.Success
        } else {
            _status.value = PrinterStatus.ERROR
            PrintResult.Failure(mapAcmeCode(reply.code), "Acme code ${reply.code}: ${reply.detail ?: "no detail"}")
        }
    }

    /** The callback-to-coroutine bridge. Always returns a reply, except when cancelled. */
    private suspend fun awaitReply(start: (AcmeCallback) -> Unit): AcmeReply =
        try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    // A coroutine may only be resumed once, but SDKs sometimes answer twice.
                    val answered = AtomicBoolean(false)
                    // Runs if we are cancelled or time out before the SDK answers.
                    continuation.invokeOnCancellation { stopSdk() }
                    start(
                        AcmeCallback { reply ->
                            if (answered.compareAndSet(false, true)) continuation.resume(reply)
                        },
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Must be caught before CancellationException, which it extends.
            AcmeReply(AcmeCode.TIMEOUT, "No reply from the SDK within $timeoutMs ms")
        } catch (e: CancellationException) {
            throw e // the caller cancelled us; that is not a printer failure
        } catch (e: Exception) {
            AcmeReply(SDK_THREW, "SDK threw ${e.javaClass.simpleName}: ${e.message}")
        }

    private fun isSdkOpen(): Boolean =
        try {
            sdk.isOpen()
        } catch (e: Exception) {
            false
        }

    private fun stopSdk() {
        try {
            sdk.abort()
        } catch (ignored: Exception) {
            // Best effort.
        }
    }

    private companion object {
        /** Our own marker for "the SDK threw", so it can never collide with a vendor code. */
        const val SDK_THREW = -1
    }
}

/** Vendor code to app error. The `else` branch is what keeps SDK upgrades from breaking us. */
internal fun mapAcmeCode(code: Int): PrinterError = when (code) {
    AcmeCode.PAPER_EMPTY -> PrinterError.PAPER_OUT
    AcmeCode.COVER_OPEN -> PrinterError.COVER_OPEN
    AcmeCode.OFFLINE -> PrinterError.NOT_CONNECTED
    AcmeCode.TIMEOUT -> PrinterError.IO_ERROR
    else -> PrinterError.UNKNOWN // BUSY, SDK_THREW, and anything the vendor adds later
}
