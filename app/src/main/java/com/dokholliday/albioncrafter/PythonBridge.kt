package com.dokholliday.albioncrafter

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single entry point to the Python engine. All engine calls run on one
 * background thread (FIFO); JSON in, JSON out. Progress events from
 * long-running engine operations are forwarded to [EventSink.onEvent].
 *
 * [startAsync] must be called once from the UI before any other call; all
 * subsequent calls queue behind interpreter startup on the same executor.
 */
object PythonBridge {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "albion-python").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val opCounter = AtomicLong(0)
    private val startLock = ReentrantLock()
    @Volatile
    private var started = false
    @Volatile
    private var dataDir: String? = null

    /** Kotlin-side sink handed to Python; Python calls onEvent(json). */
    class EventSink(val onEvent: (JSONObject) -> Unit) {
        @Suppress("unused")
        fun onEvent(eventJson: String) {
            runCatching { JSONObject(eventJson) }.getOrNull()?.let(onEvent)
        }
    }

    /** Initialize the interpreter and the engine stack on the engine thread. */
    fun startAsync(context: Context) {
        val dir = context.getDir("albion-crafter", Context.MODE_PRIVATE).absolutePath
        executor.execute {
            startLock.withLock {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
                if (!started) {
                    dataDir = dir
                    Python.getInstance().getModule("bridge").callAttr("startup", dir)
                    started = true
                }
            }
        }
    }

    fun newOpId(): String = "op-${opCounter.incrementAndGet()}"

    fun cancel(opId: String) {
        executor.execute {
            runCatching {
                Python.getInstance().getModule("bridge").callAttr("cancel", opId)
            }
        }
    }

    /**
     * Run a bridge function on the engine thread. Blocking; call from a
     * background context only. If [sink] is provided it is passed to Python
     * as the progress callback target.
     */
    fun call(
        function: String,
        vararg args: Any?,
    ): JSONObject {
        val future: Future<JSONObject> = executor.submit<JSONObject> {
            val py = Python.getInstance()
            val raw = py.getModule("bridge").callAttr(function, *args).toString()
            JSONObject(raw)
        }
        return future.get()
    }

    /** Fire-and-forget variant; result delivered on the main thread. */
    fun callAsync(
        function: String,
        vararg args: Any?,
        onDone: (Result<JSONObject>) -> Unit,
    ): Future<*> = executor.submit {
        val result = runCatching {
            val py = Python.getInstance()
            val raw = py.getModule("bridge").callAttr(function, *args).toString()
            JSONObject(raw)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(result) }
    }
}
