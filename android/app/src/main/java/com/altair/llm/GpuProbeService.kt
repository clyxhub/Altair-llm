package com.altair.llm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log

/**
 * Isolated GPU verification.
 *
 * Runs in the dedicated `:gpuprobe` process so that a Vulkan driver crash
 * (SIGSEGV/SIGABRT) during a GPU model load can never kill the UI process.
 * The caller binds, sends the load parameters, and receives the native result
 * code; if this process dies, the caller's onServiceDisconnected fires and GPU
 * is permanently marked unsafe.
 *
 * A native crash guard is installed first so a fault exits this process cleanly
 * instead of raising the system "Altair has stopped" dialog.
 */
class GpuProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        val handler = Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MSG_LOAD) {
                val replyTo = msg.replyTo
                val data: Bundle = msg.data
                val path = data.getString("path") ?: ""
                val ctx = data.getInt("ctx", 4096)
                val threads = data.getInt("threads", 4)
                val ngl = data.getInt("ngl", 99)
                Thread({
                    val rc = try {
                        LlamaBridge.installCrashGuard()
                        LlamaBridge.init()
                        LlamaBridge.setKvQuant(LlamaBridge.KV_OFF)
                        LlamaBridge.loadModel(path, ctx, threads, ngl)
                    } catch (t: Throwable) {
                        Log.w(TAG, "gpu probe threw", t)
                        -1
                    }
                    runCatching { LlamaBridge.free() }
                    Log.i(TAG, "gpu probe finished rc=$rc")
                    try {
                        val m = Message.obtain(null, MSG_RESULT)
                        m.arg1 = rc
                        replyTo?.send(m)
                    } catch (_: RemoteException) {
                    }
                    stopSelf()
                }, "gpu-probe").start()
            }
            true
        }
        return Messenger(handler).binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        const val TAG = "Altair"
        const val MSG_LOAD = 1
        const val MSG_RESULT = 2
    }
}
