package com.jaison.minipos.printer

/**
 * Splits bytes into pieces of at most [size] bytes each.
 *
 * This is the logic [BluetoothEscPosPrinter] uses to feed a small printer buffer. It lives
 * here, away from the Bluetooth classes, so it can be unit tested without a device. The old
 * version turned every byte into a boxed object first (`data.toList().chunked(n)`); this
 * copies ranges of the array directly.
 */
internal fun ByteArray.inChunksOf(size: Int): List<ByteArray> {
    require(size > 0) { "Chunk size must be positive" }
    return indices.step(size).map { start -> copyOfRange(start, minOf(start + size, this.size)) }
}
