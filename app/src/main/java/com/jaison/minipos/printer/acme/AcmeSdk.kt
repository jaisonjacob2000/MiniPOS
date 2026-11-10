package com.jaison.minipos.printer.acme

/*
 * This file describes the API of "Acme Print SDK", a made-up vendor library.
 *
 * Real printer, scanner and payment-terminal SDKs look like this. You get a compiled library
 * (an .aar), not its source. You do not control which thread it calls you back on, it speaks
 * in its own numeric codes, and it can misbehave in ways its documentation does not mention.
 * The job of the code in this package is to keep all of that away from the rest of the app.
 */

/** Result codes the vendor documents. A newer SDK version may add more. */
object AcmeCode {
    const val OK = 0
    const val BUSY = 1
    const val PAPER_EMPTY = 2
    const val COVER_OPEN = 3
    const val OFFLINE = 4
    const val TIMEOUT = 5
}

data class AcmeReply(val code: Int, val detail: String? = null) {
    val isOk: Boolean get() = code == AcmeCode.OK
}

/** The SDK calls this when an operation finishes, usually on one of its own threads. */
fun interface AcmeCallback {
    fun onReply(reply: AcmeReply)
}

/**
 * The vendor API: nothing blocks, nothing is suspend, everything answers through a callback.
 * Any of these calls may also throw if the SDK is in a state it does not like.
 */
interface AcmeSdk {
    fun isOpen(): Boolean

    /** Starts opening the connection to the printer. */
    fun open(callback: AcmeCallback)

    /** Starts sending bytes to the printer. */
    fun send(data: ByteArray, callback: AcmeCallback)

    /** Asks the SDK to stop whatever it is doing. Best effort. */
    fun abort()

    /** Releases the connection. */
    fun shutdown()
}
