package com.jaison.minipos.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * A real thermal receipt printer over Bluetooth Classic (the serial port profile most
 * cheap ESC/POS printers use). Pair the printer in Android's Bluetooth settings first;
 * the app only connects to devices that are already paired.
 *
 * The caller must hold BLUETOOTH_CONNECT on Android 12+ before using this class.
 */
@SuppressLint("MissingPermission")
class BluetoothEscPosPrinter(
    private val device: BluetoothDevice,
    override val name: String,
) : ReceiptPrinter {

    val address: String get() = device.address

    private var socket: BluetoothSocket? = null
    private val _status = MutableStateFlow(PrinterStatus.DISCONNECTED)
    override val status: StateFlow<PrinterStatus> = _status.asStateFlow()

    override suspend fun connect(): PrintResult = withContext(Dispatchers.IO) {
        if (socket?.isConnected == true) {
            _status.value = PrinterStatus.READY
            return@withContext PrintResult.Success
        }
        _status.value = PrinterStatus.CONNECTING
        try {
            val newSocket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE)
            newSocket.connect() // blocks, which is why this runs on Dispatchers.IO
            socket = newSocket
            _status.value = PrinterStatus.READY
            PrintResult.Success
        } catch (e: IOException) {
            closeQuietly()
            _status.value = PrinterStatus.ERROR
            PrintResult.Failure(PrinterError.NOT_CONNECTED, e.message)
        } catch (e: SecurityException) {
            _status.value = PrinterStatus.ERROR
            PrintResult.Failure(PrinterError.NOT_CONNECTED, "Bluetooth permission not granted")
        }
    }

    override suspend fun print(data: ByteArray): PrintResult = withContext(Dispatchers.IO) {
        val current = socket ?: return@withContext PrintResult.Failure(PrinterError.NOT_CONNECTED)
        _status.value = PrinterStatus.PRINTING
        try {
            val output = current.outputStream
            // Many inexpensive printers have small buffers and silently drop bytes if a
            // whole receipt arrives at once, so send it in chunks with a short pause.
            data.toList().chunked(CHUNK_SIZE).forEach { chunk ->
                output.write(chunk.toByteArray())
                output.flush()
                delay(CHUNK_PAUSE_MS)
            }
            _status.value = PrinterStatus.READY
            PrintResult.Success
        } catch (e: IOException) {
            closeQuietly()
            _status.value = PrinterStatus.ERROR
            PrintResult.Failure(PrinterError.IO_ERROR, e.message)
        }
    }

    override fun close() {
        closeQuietly()
        _status.value = PrinterStatus.DISCONNECTED
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // Already closed or the printer went away; nothing else to do.
        }
        socket = null
    }

    private companion object {
        /** Standard UUID for the Bluetooth Serial Port Profile (SPP). */
        val SERIAL_PORT_PROFILE: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val CHUNK_SIZE = 512
        const val CHUNK_PAUSE_MS = 20L
    }
}
