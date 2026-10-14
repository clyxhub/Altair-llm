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
import java.io.File

/**
 * Isolated native verification.
 *
 * Runs in the dedicated `:nativeprobe` process so that a native fault during a
 * GPU model load OR an mmproj projector load can never kill the UI process.
 *
 * The probe writes a progress file in app storage BEFORE each risky step, so
 * if the process dies mid-way the caller can tell exactly which step crashed
 * (model load vs projector load) from the file's last contents. It also sends
 * the final result code back over a reply Messenger when it survives.
 *
 * A native crash guard is installed first, so a fault exits this process
 * cleanly instead of raising the system "Altair has stopped" dialog.
 */
class NativeProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        val handler = Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MSG_LOAD) {
                val replyTo = msg.replyTo
                val data: Bundle = msg.data
                val path = data.getString("path") ?: ""
                val mmproj = data.getString("mmproj").orEmpty()
                val ctx = data.getInt("ctx", 4096)
                val threads = data.getInt("threads", 4)
                val ngl = data.getInt("ngl", 0)
                Thread({ runProbe(replyTo, path, mmproj, ctx, threads, ngl) }, "native-probe").start()
            }
            true
        }
        return Messenger(handler).binder
    }

    private fun runProbe(replyTo: Messenger?, path: String, mmproj: String, ctx: Int, threads: Int, ngl: Int) {
        val marker = File(filesDir, PROGRESS_FILE)
        var rc = -1
        try {
            marker.writeText("starting\n")
            LlamaBridge.installCrashGuard()
            LlamaBridge.init()
            LlamaBridge.setKvQuant(LlamaBridge.KV_OFF)

            marker.writeText("model_loading\n")
            rc = LlamaBridge.loadModel(path, ctx, threads, ngl)
            if (rc != 0) {
                marker.writeText("model_fail:$rc\n")
                LogRing.add("probe", "model load failed rc=$rc (ngl=$ngl)")
                reply(replyTo, rc)
                return
            }
            marker.writeText("model_ok\n")
            LogRing.add("probe", "model ok (ngl=$ngl)")

            if (mmproj.isNotBlank()) {
                marker.writeText("proj_loading\n")
                val rp = LlamaBridge.loadProjector(mmproj, threads)
                if (rp != 0) {
                    marker.writeText("proj_fail:$rp\n")
                    LogRing.add("probe", "projector failed rc=$rp")
                    reply(replyTo, 1000 + rp)
                    return
                }
                marker.writeText("proj_ok\n")
                LogRing.add("probe", "projector ok")
            }
            marker.writeText("done\n")
            reply(replyTo, 0)
        } catch (t: Throwable) {
            marker.writeText("exception:${t.message}\n")
            LogRing.add("probe", "probe threw: ${t.message}")
            reply(replyTo, -1)
        } finally {
            runCatching { LlamaBridge.free() }
            stopSelf()
        }
    }

    private fun reply(replyTo: Messenger?, rc: Int) {
        try {
            val m = Message.obtain(null, MSG_RESULT)
            m.arg1 = rc
            m.data = Bundle().apply { putString("progress", File(filesDir, PROGRESS_FILE).readText()) }
            replyTo?.send(m)
        } catch (_: RemoteException) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        const val MSG_LOAD = 1
        const val MSG_RESULT = 2
        const val PROGRESS_FILE = "native_probe_progress"
    }
}
