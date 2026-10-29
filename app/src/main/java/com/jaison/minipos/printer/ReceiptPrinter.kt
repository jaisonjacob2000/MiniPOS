package com.jaison.minipos.printer

import kotlinx.coroutines.flow.StateFlow

/**
 * The one interface the rest of the app knows about. Every printer, whether simulated,
 * Bluetooth, network or a vendor SDK, implements it, so supporting a new device means
 * writing one new class and leaving the POS logic alone.
 */
interface ReceiptPrinter {
    val name: String
    val status: StateFlow<PrinterStatus>

    suspend fun connect(): PrintResult

    /** Sends raw ESC/POS bytes. Only valid while [status] is READY. */
    suspend fun print(data: ByteArray): PrintResult

    /** Releases the connection. Safe to call more than once. */
    fun close()
}

enum class PrinterStatus { DISCONNECTED, CONNECTING, READY, PRINTING, ERROR }

enum class PrinterError(val userMessage: String) {
    NOT_CONNECTED("Printer not connected"),
    PAPER_OUT("Printer is out of paper"),
    IO_ERROR("Lost connection to the printer while printing"),
}

sealed interface PrintResult {
    data object Success : PrintResult
    data class Failure(val error: PrinterError, val detail: String? = null) : PrintResult
}
