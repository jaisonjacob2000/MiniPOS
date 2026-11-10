package com.jaison.minipos.printer.acme

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stands in for the real vendor library, which we would only have as a closed .aar.
 *
 * It behaves like a real SDK: answers arrive later, on the SDK's own thread called
 * "acme-sdk", never on the caller's thread. The switches below let tests (and demos) make
 * it misbehave the way real SDKs do, which is hard to do on demand with real hardware.
 */
class SimulatedAcmeSdk(
    @Volatile var latencyMs: Long = 10,
) : AcmeSdk {

    /** What the SDK answers to open() and send(). */
    @Volatile var openCode: Int = AcmeCode.OK
    @Volatile var sendCode: Int = AcmeCode.OK

    /** Never answers at all (a hung printer, a lost callback). */
    @Volatile var silent: Boolean = false

    /** Answers the same request twice (a known class of SDK bug). */
    @Volatile var duplicateReplies: Boolean = false

    /** Answers before the call has even returned, on the caller's thread. */
    @Volatile var synchronous: Boolean = false

    /** send() throws instead of answering (SDK in a bad state, not initialised, and so on). */
    @Volatile var throwOnSend: Boolean = false

    // What tests look at.
    val sentJobs = CopyOnWriteArrayList<ByteArray>()
    val callbackErrors = CopyOnWriteArrayList<Throwable>()
    val abortCount = AtomicInteger()
    val shutdownCount = AtomicInteger()
    @Volatile var lastCallbackThread: Thread? = null

    @Volatile private var open = false

    /** Bumped by abort(). Work queued before an abort is dropped, like a real job queue. */
    private val generation = AtomicInteger()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "acme-sdk").apply { isDaemon = true }
    }

    override fun isOpen(): Boolean = open

    override fun open(callback: AcmeCallback) {
        reply(callback) {
            if (openCode == AcmeCode.OK) open = true
            AcmeReply(openCode)
        }
    }

    override fun send(data: ByteArray, callback: AcmeCallback) {
        if (throwOnSend) throw IllegalStateException("Acme SDK is not initialised")
        reply(callback) {
            if (sendCode == AcmeCode.OK) sentJobs += data.copyOf()
            AcmeReply(sendCode)
        }
    }

    override fun abort() {
        abortCount.incrementAndGet()
        generation.incrementAndGet()
    }

    override fun shutdown() {
        shutdownCount.incrementAndGet()
        open = false
    }

    private fun reply(callback: AcmeCallback, work: () -> AcmeReply) {
        if (silent) return
        val queuedIn = generation.get()
        val deliver = {
            try {
                if (generation.get() == queuedIn) {
                    val result = work()
                    lastCallbackThread = Thread.currentThread()
                    callback.onReply(result)
                    if (duplicateReplies) callback.onReply(result)
                }
            } catch (t: Throwable) {
                // A crash on the SDK's thread would take the whole app down. Record it instead.
                callbackErrors += t
            }
        }
        if (synchronous) {
            deliver()
        } else {
            worker.execute {
                Thread.sleep(latencyMs)
                deliver()
            }
        }
    }
}
