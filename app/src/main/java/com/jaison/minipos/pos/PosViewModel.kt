package com.jaison.minipos.pos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jaison.minipos.domain.MenuItem
import com.jaison.minipos.domain.SampleMenu
import com.jaison.minipos.printer.BluetoothEscPosPrinter
import com.jaison.minipos.printer.FakeReceiptPrinter
import com.jaison.minipos.printer.PrinterError
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class PairedDevice(val name: String, val address: String)

/**
 * Thin Android wrapper around [PosController]: it owns the coroutine scope and the
 * Bluetooth system services, and passes everything else straight through.
 */
class PosViewModel(app: Application) : AndroidViewModel(app) {

    val simulatedPrinter = FakeReceiptPrinter(name = "Simulated printer", delayMs = 700)

    private val controller = PosController(
        initialPrinter = simulatedPrinter,
        timestamp = { LocalDateTime.now().format(TIMESTAMP_FORMAT) },
    )

    val menu: List<MenuItem> = SampleMenu.items
    val state = controller.state
    val printer = controller.printer
    val printerStatus = controller.printerStatus

    fun addItem(item: MenuItem) = controller.addItem(item)
    fun removeOne(item: MenuItem) = controller.removeOne(item)
    fun dismissMessage() = controller.dismissMessage()
    fun dismissReceipt() = controller.dismissReceipt()

    fun checkout() {
        viewModelScope.launch { controller.checkout() }
    }

    fun useSimulatedPrinter() = controller.usePrinter(simulatedPrinter)

    /** Demo helper: make the simulated printer fail once, to show the order is kept. */
    fun simulatePaperOutOnNextPrint() {
        simulatedPrinter.failNextPrint = PrinterError.PAPER_OUT
    }

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasBluetoothPermission()
    fun pairedDevices(): List<PairedDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = bluetoothAdapter() ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { PairedDevice(name = it.name ?: it.address, address = it.address) }
            .sortedBy { it.name.lowercase() }
    }

    fun useBluetoothPrinter(device: PairedDevice) {
        val adapter = bluetoothAdapter() ?: return
        val printer = BluetoothEscPosPrinter(adapter.getRemoteDevice(device.address), device.name)
        controller.usePrinter(printer)
        viewModelScope.launch { printer.connect() }
    }

    private fun bluetoothAdapter() =
        getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

    override fun onCleared() {
        controller.printer.value.close()
    }

    private companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
