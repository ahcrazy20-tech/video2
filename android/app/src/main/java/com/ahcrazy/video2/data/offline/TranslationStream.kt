package com.ahcrazy.video2.data.offline

import android.os.CancellationSignal
import com.ahcrazy.video2.core.model.SubCue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** At most one MT worker and one pending snapshot, not a worker (or a model) per chunk.
 * A newer committed prefix can replace an older pending one without losing any work. */
internal class TranslationStream(
    private val signal: CancellationSignal,
    consume: (TranslationStream) -> List<SubCue>
) : AutoCloseable {
    data class Snapshot(val cues: List<SubCue>, val end: Boolean)
    private val pending = ArrayBlockingQueue<Snapshot>(1)
    @Volatile var sourceDone = false
        private set
    private val task = FutureTask { consume(this) }

    fun next(): Snapshot {
        signal.throwIfCanceled()
        val next = pending.take()
        signal.throwIfCanceled()
        return next
    }

    // Construct fully before letting the worker access instance fields.
    fun start() { Thread(task, "Video2-local-MT").start() }

    fun finishSource() { sourceDone = true }

    @Synchronized fun submit(cues: List<SubCue>, end: Boolean = false) {
        if (task.isDone || signal.isCanceled) return
        if (end) finishSource()
        pending.clear()
        pending.add(Snapshot(cues.toList(), end))
    }

    fun stop() {
        signal.cancel()
        synchronized(this) {
            pending.clear()
            pending.add(Snapshot(emptyList(), true)) // wake a worker waiting on the mailbox
        }
    }

    /** Caller chooses whether to retry in serial after the worker has released its native context. */
    fun await(): List<SubCue> = try { task.get() }
    catch (error: ExecutionException) { throw error.cause ?: error }

    override fun close() {
        stop()
        // No abandoned native work after an error, cancellation, or service shutdown.
        var interrupted = false
        while (true) {
            try { task.get(); break }
            catch (_: InterruptedException) { interrupted = true }
            catch (_: ExecutionException) { break }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
