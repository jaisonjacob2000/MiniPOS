package com.jaison.minipos.pos

import com.jaison.minipos.domain.MenuItem
import com.jaison.minipos.domain.Order
import com.jaison.minipos.printer.EscPosEncoder
import com.jaison.minipos.printer.PrintResult
import com.jaison.minipos.printer.PrinterStatus
import com.jaison.minipos.printer.ReceiptFormatter
import com.jaison.minipos.printer.ReceiptPrinter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

data class PosState(
    val order: Order = Order(),
    val orderNumber: Int = 1,
    val isPrinting: Boolean = false,
    /** A one-off message for the user (shown as a snackbar), or null. */
    val message: String? = null,
    /** Plain-text copy of the last receipt printed, for the on-screen preview. */
    val lastReceipt: String? = null,
)

/**
 * All of the POS behavior, with no Android dependencies, so it is fully unit tested.
 * The ViewModel is a thin wrapper that calls into this class.
 *
 * Key rule: a failed print never loses the customer's order. The order stays on screen
 * until a receipt actually prints.
 */
class PosController(
    initialPrinter: ReceiptPrinter,
    private val formatter: ReceiptFormatter = ReceiptFormatter(),
    private val encoder: EscPosEncoder = EscPosEncoder(),
    private val timestamp: () -> String,
) {
    private val _state = MutableStateFlow(PosState())
    val state: StateFlow<PosState> = _state.asStateFlow()

    private val _printer = MutableStateFlow(initialPrinter)
    val printer: StateFlow<ReceiptPrinter> = _printer.asStateFlow()

    /** Status of whichever printer is currently selected, switching when the printer changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val printerStatus: Flow<PrinterStatus> = _printer.flatMapLatest { it.status }

    fun addItem(item: MenuItem) {
        if (_state.value.isPrinting) return
        _state.update { it.copy(order = it.order.add(item)) }
    }

    fun removeOne(item: MenuItem) {
        if (_state.value.isPrinting) return
        _state.update { it.copy(order = it.order.removeOne(item)) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissReceipt() = _state.update { it.copy(lastReceipt = null) }

    /** Swap to a different printer, closing the old connection. */
    fun usePrinter(newPrinter: ReceiptPrinter) {
        val old = _printer.value
        if (old === newPrinter) return
        old.close()
        _printer.value = newPrinter
    }

    /** Prints the receipt for the current order. Returns true if it printed. */
    suspend fun checkout(): Boolean {
        val current = _state.value
        if (current.isPrinting) return false
        if (current.order.isEmpty) {
            _state.update { it.copy(message = "Add at least one item first") }
            return false
        }

        _state.update { it.copy(isPrinting = true, message = null) }
        val printer = _printer.value
        val receipt = formatter.customerReceipt(current.order, current.orderNumber, timestamp())

        val result = when (val connection = ensureConnected(printer)) {
            is PrintResult.Failure -> connection
            PrintResult.Success -> printer.print(encoder.encode(receipt))
        }

        return when (result) {
            PrintResult.Success -> {
                _state.update {
                    PosState(
                        order = Order(taxRateBps = it.order.taxRateBps),
                        orderNumber = it.orderNumber + 1,
                        message = "${formatter.orderLabel(it.orderNumber)} printed on ${printer.name}",
                        lastReceipt = receipt.toPlainText(),
                    )
                }
                true
            }
            is PrintResult.Failure -> {
                _state.update {
                    it.copy(
                        isPrinting = false,
                        message = "${result.error.userMessage}. Order kept, fix the printer and try again.",
                    )
                }
                false
            }
        }
    }

    private suspend fun ensureConnected(printer: ReceiptPrinter): PrintResult =
        if (printer.status.value == PrinterStatus.READY) PrintResult.Success else printer.connect()
}
