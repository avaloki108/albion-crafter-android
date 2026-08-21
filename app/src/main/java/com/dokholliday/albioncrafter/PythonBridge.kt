package com.dokholliday.albioncrafter

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * Single entry point to the Python engine. All engine calls run on one
 * background thread; JSON in, JSON out. Progress events from long-running
 * engine operations are forwarded to [EventSink.onEvent] on the main thread.
 */
object PythonBridge {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "albion-python").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val opCounter = AtomicLong(0)
    @Volatile
    private var started = false

    /** Kotlin-side sink handed to Python; Python calls onEvent(json). */
    class EventSink(val onEvent: (JSONObject) -> Unit) {
        @Suppress("unused")
        fun onEvent(eventJson: String) {
            onEvent(runCatching { JSONObject(eventJson) }.getOrNull() ?: return)
        }
    }

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val dataDir = context.getDir("albion-crafter", Context.MODE_PRIVATE).absolutePath
            val py = Python.getInstance()
            py.getModule("bridge").callAttr("startup", dataDir)
            started = true
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
     * Run a bridge function on the engine thread. If [sink] is provided it is
     * passed as the Python progress callback target.
     */
    fun call(
        function: String,
        vararg args: Any?,
    ): JSONObject {
        val future: Future<JSONObject> = executor.submit<JSONObject> {
            val py = Python.getInstance()
            val bridge = py.getModule("bridge")
            val raw = bridge.callAttr(function, *args).toString()
            JSONObject(raw)
        }
        return future.get()
    }

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

    fun pyObject(function: String, vararg args: Any?): PyObject {
        val py = Python.getInstance()
        return py.getModule("bridge").callAttr(function, *args)
    }
}
