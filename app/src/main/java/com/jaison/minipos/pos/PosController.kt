package com.jaison.minipos.pos

import com.jaison.minipos.domain.MenuItem
import com.jaison.minipos.domain.Order
import com.jaison.minipos.printer.EscPosEncoder
import com.jaison.minipos.printer.PrintResult
import com.jaison.minipos.printer.PrinterError
import com.jaison.minipos.printer.PrinterStatus
import com.jaison.minipos.printer.Receipt
import com.jaison.minipos.printer.ReceiptFormatter
import com.jaison.minipos.printer.ReceiptPrinter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

/** A kitchen ticket that has not printed yet. The sale it belongs to is already complete. */
data class PendingTicket(val orderNumber: Int, val receipt: Receipt)

data class PosState(
    val order: Order = Order(),
    val orderNumber: Int = 1,
    val isPrinting: Boolean = false,
    /** A one-off message for the user (shown as a snackbar), or null. */
    val message: String? = null,
    /** Plain-text copy of the last receipt printed, for the on-screen preview. */
    val lastReceipt: String? = null,
    /** Kitchen tickets still waiting to print, oldest first. */
    val pendingTickets: List<PendingTicket> = emptyList(),
)

/**
 * All of the POS behavior, with no Android dependencies, so it is fully unit tested.
 * The ViewModel is a thin wrapper that calls into this class.
 *
 * Key rule: a failed print never loses the customer's order. The order stays on screen
 * until a receipt actually prints.
 *
 * When [printKitchenTickets] is on, a kitchen ticket prints right after the customer receipt.
 * The receipt is what completes the sale. If the ticket then fails, the sale stays complete
 * (reprinting the receipt would hand the customer a duplicate) and the ticket waits in
 * [PosState.pendingTickets] until it prints.
 */
class PosController(
    initialPrinter: ReceiptPrinter,
    private val formatter: ReceiptFormatter = ReceiptFormatter(),
    private val encoder: EscPosEncoder = EscPosEncoder(),
    private val printKitchenTickets: Boolean = false,
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

    /**
     * Prints the receipt for the current order (and its kitchen ticket, if enabled).
     * Returns true if the sale completed, which means the customer receipt printed.
     *
     * Whatever happens, the order is kept until a receipt really prints, and the "printing"
     * lock is always released: on a printer error, on an unexpected exception from a vendor
     * SDK, and when the calling coroutine is cancelled (for example the screen closes).
     */
    suspend fun checkout(): Boolean {
        val current = claimCheckout() ?: return false
        val printer = _printer.value
        var saleCommitted = false
        try {
            val time = timestamp()
            val receipt = formatter.customerReceipt(current.order, current.orderNumber, time)
            val result = send(printer, encoder.encode(receipt))
            if (result is PrintResult.Failure) {
                releaseLock("${result.error.userMessage}. Order kept, fix the printer and try again.")
                return false
            }

            // The customer has their receipt: the sale is done. Record that before anything
            // else can go wrong, so a failure while printing the kitchen ticket cannot undo it.
            val label = formatter.orderLabel(current.orderNumber)
            val ticket = if (printKitchenTickets) {
                PendingTicket(current.orderNumber, formatter.kitchenTicket(current.order, current.orderNumber, time))
            } else {
                null
            }
            saleCommitted = true
            _state.update {
                PosState(
                    order = Order(taxRateBps = it.order.taxRateBps),
                    orderNumber = it.orderNumber + 1,
                    isPrinting = ticket != null, // keep the lock while the ticket prints
                    message = "$label printed on ${printer.name}",
                    lastReceipt = receipt.toPlainText(),
                    pendingTickets = if (ticket != null) it.pendingTickets + ticket else it.pendingTickets,
                )
            }
            if (ticket != null) {
                finishTickets(printer, "$label printed on ${printer.name}, with kitchen ticket")
            }
            return true
        } catch (e: CancellationException) {
            // Cancellation is not an error, so it must keep travelling up. But first release
            // the lock, or the cashier could never print again.
            releaseLock(
                if (saleCommitted) {
                    "Printing was interrupted. The kitchen ticket is waiting: use Reprint kitchen ticket."
                } else {
                    "Printing was interrupted. Order kept, check the printer and try again."
                },
            )
            throw e
        }
    }

    /** Prints any kitchen tickets that are waiting. Returns true if none are left waiting. */
    suspend fun reprintKitchenTickets(): Boolean {
        if (!claimReprint()) return false
        try {
            return finishTickets(_printer.value, "Kitchen tickets printed")
        } catch (e: CancellationException) {
            releaseLock("Printing was interrupted. The kitchen ticket is still waiting.")
            throw e
        }
    }

    /** Prints waiting tickets, then releases the lock with a message about how it went. */
    private suspend fun finishTickets(printer: ReceiptPrinter, successMessage: String): Boolean {
        val failure = flushTickets(printer)
        if (failure == null) {
            releaseLock(successMessage)
            return true
        }
        releaseLock(
            "${failure.error.userMessage}. The sale is complete, but a kitchen ticket did not " +
                "print. Use Reprint kitchen ticket.",
        )
        return false
    }

    /** Oldest first. Stops at the first failure so tickets never reach the kitchen out of order. */
    private suspend fun flushTickets(printer: ReceiptPrinter): PrintResult.Failure? {
        while (true) {
            val next = _state.value.pendingTickets.firstOrNull() ?: return null
            val result = send(printer, encoder.encode(next.receipt))
            if (result is PrintResult.Failure) return result
            _state.update { s ->
                s.copy(pendingTickets = s.pendingTickets.filterNot { it.orderNumber == next.orderNumber })
            }
        }
    }

    /**
     * Decides, in one atomic step, whether this checkout may run, and if so takes the
     * "printing" lock. Returns the state it claimed, or null if the checkout must not run.
     *
     * The old version read the state, checked it, and wrote it back as three separate steps,
     * which is only safe if nothing else can run in between. compareAndSet makes that true
     * by construction: if another caller changed the state first, we look again.
     */
    private fun claimCheckout(): PosState? {
        while (true) {
            val current = _state.value
            if (current.isPrinting) return null
            if (current.order.isEmpty) {
                _state.update { it.copy(message = "Add at least one item first") }
                return null
            }
            if (_state.compareAndSet(current, current.copy(isPrinting = true, message = null))) {
                return current
            }
        }
    }

    /** Same idea as [claimCheckout], for reprinting: only one print job at a time. */
    private fun claimReprint(): Boolean {
        while (true) {
            val current = _state.value
            if (current.isPrinting || current.pendingTickets.isEmpty()) return false
            if (_state.compareAndSet(current, current.copy(isPrinting = true, message = null))) {
                return true
            }
        }
    }

    private fun releaseLock(message: String) =
        _state.update { it.copy(isPrinting = false, message = message) }

    /** Connects if needed, then prints. Turns an exception from a vendor SDK into a failure. */
    private suspend fun send(printer: ReceiptPrinter, bytes: ByteArray): PrintResult =
        try {
            when (val connection = ensureConnected(printer)) {
                is PrintResult.Failure -> connection
                PrintResult.Success -> printer.print(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never trust third-party code not to throw.
            PrintResult.Failure(PrinterError.UNKNOWN, e.message)
        }

    private suspend fun ensureConnected(printer: ReceiptPrinter): PrintResult =
        if (printer.status.value == PrinterStatus.READY) PrintResult.Success else printer.connect()
}
