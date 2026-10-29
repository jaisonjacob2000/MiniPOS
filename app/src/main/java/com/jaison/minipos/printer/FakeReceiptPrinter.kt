package com.jaison.minipos.printer

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A printer that lives in memory. It lets the app run on an emulator, and it lets tests
 * force failures (paper out, connection refused) that are hard to reproduce on demand
 * with real hardware.
 */
class FakeReceiptPrinter(
    override val name: String = "Simulated printer",
    private val delayMs: Long = 0,
) : ReceiptPrinter {

    private val _status = MutableStateFlow(PrinterStatus.DISCONNECTED)
    override val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    /** Every job printed successfully, in order. */
    val printedJobs = mutableListOf<ByteArray>()

    /** If set, the next print fails with this error, then the flag clears. */
    var failNextPrint: PrinterError? = null

    /** If true, connect() fails, as if the printer were switched off. */
    var refuseConnection: Boolean = false

    var closeCount = 0
        private set

    override suspend fun connect(): PrintResult {
        _status.value = PrinterStatus.CONNECTING
        delay(delayMs)
        if (refuseConnection) {
            _status.value = PrinterStatus.ERROR
            return PrintResult.Failure(PrinterError.NOT_CONNECTED, "Simulated: printer is off")
        }
        _status.value = PrinterStatus.READY
        return PrintResult.Success
    }

    override suspend fun print(data: ByteArray): PrintResult {
        if (_status.value != PrinterStatus.READY) {
            return PrintResult.Failure(PrinterError.NOT_CONNECTED)
        }
        _status.value = PrinterStatus.PRINTING
        delay(delayMs)
        failNextPrint?.let { error ->
            failNextPrint = null
            _status.value = PrinterStatus.ERROR
            return PrintResult.Failure(error, "Simulated failure")
        }
        printedJobs += data.copyOf()
        _status.value = PrinterStatus.READY
        return PrintResult.Success
    }

    override fun close() {
        closeCount++
        _status.value = PrinterStatus.DISCONNECTED
    }
}
