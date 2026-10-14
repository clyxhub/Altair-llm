package com.altair.llm

import android.app.ActivityManager
import android.content.Context

/**
 * 17th requirement: an advisory "will this model fit my phone?" estimator.
 *
 * Reads the device's real memory profile and grades a model file as
 *   GREEN  (just right) / YELLOW (barely fits) / RED (too big).
 *
 * This is a WARNING ONLY. It never blocks or gates a download - the caller
 * always keeps the download action enabled; we just surface a coloured badge so
 * the user can decide for themselves.
 *
 * The budget is a fraction of *total* RAM. llama.cpp memory-maps the GGUF, so a
 * model can technically exceed free RAM and still load (pages fault in on
 * demand) - but once the working set collides with the KV cache and activations
 * the process becomes a target for the OS's low-memory killer. The thresholds
 * below therefore grade on "leaves comfortable headroom" rather than "fits in
 * bytes".
 */
object DeviceFit {

    enum class Fit { GREEN, YELLOW, RED }

    data class Profile(
        val totalRamBytes: Long,
        val availRamBytes: Long,
        val heapMb: Int,
        val gpuAvailable: Boolean
    )

    /** Model (+ mmproj) should stay under this fraction of total RAM. */
    private const val COMFORTABLE = 0.45

    /** At/above this fraction of total RAM the model is flagged RED. */
    private const val BARELY = 0.80

    /**
     * Snapshot the device. [gpuAvailable] is passed in (the ViewModel already
     * resolved it once at init) rather than re-probing the native backend here.
     */
    fun profile(context: Context, gpuAvailable: Boolean): Profile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(mi) }
        return Profile(
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            heapMb = runCatching { am?.largeMemoryClass ?: 0 }.getOrDefault(0),
            gpuAvailable = gpuAvailable
        )
    }

    /**
     * Largest context we can hand to llama.cpp without inviting the OS
     * low-memory killer. Weights are mmap'd, but the KV cache is real RAM:
     * an 8K f16 cache on a 6 GB phone plus a ~1.3 GB model + projector is
     * routinely killed right after load (looks like a "crash").
     *
     * Advisory but applied automatically; the user can still raise it in Settings.
     */
    fun safeContext(requested: Int, modelBytes: Long, projectorBytes: Long, p: Profile): Int {
        val gib = 1024.0 * 1024.0 * 1024.0
        val gb = p.totalRamBytes / gib
        val capByRam = when {
            gb >= 12.0 -> 8192
            gb >= 8.0 -> 8192
            gb >= 6.0 -> 4096
            gb >= 4.0 -> 2048
            else -> 1024
        }
        val footprintGb = (modelBytes + projectorBytes.coerceAtLeast(0L)) / gib
        var cap = capByRam
        if (footprintGb >= 2.0 && cap > 4096) cap = 4096
        if (footprintGb >= 3.0 && cap > 2048) cap = 2048
        if (footprintGb >= 4.0 && cap > 1024) cap = 1024
        return minOf(requested, cap).coerceAtLeast(512)
    }

    /**
     * Grade a prospective download. [modelBytes] should include the mmproj size
     * when the user is going to pull that as well.
     */
    fun evaluate(modelBytes: Long, p: Profile): Fit {        if (p.totalRamBytes <= 0L || modelBytes <= 0L) return Fit.YELLOW
        val ratio = modelBytes.toDouble() / p.totalRamBytes.toDouble()
        var fit = when {
            ratio <= COMFORTABLE -> Fit.GREEN
            ratio <= BARELY -> Fit.YELLOW
            else -> Fit.RED
        }
        // If it does not even fit in what is free right now, nudge one level worse.
        if (fit == Fit.GREEN && modelBytes > p.availRamBytes) {
            fit = Fit.YELLOW
        } else if (fit == Fit.YELLOW && modelBytes > (p.availRamBytes * 3) / 2) {
            fit = Fit.RED
        }
        return fit
    }

    fun label(fit: Fit): String = when (fit) {
        Fit.GREEN -> "Just right"
        Fit.YELLOW -> "Barely fits"
        Fit.RED -> "Too big"
    }

    /** Short justification, e.g. "9.4 GB of 11.2 GB RAM (84%)". */
    fun detail(modelBytes: Long, p: Profile): String {
        if (p.totalRamBytes <= 0L || modelBytes <= 0L) return ""
        val pct = (modelBytes.toDouble() / p.totalRamBytes.toDouble() * 100).toInt()
        return ModelRepository.formatSize(modelBytes) + " of " +
            ModelRepository.formatSize(p.totalRamBytes) + " RAM (" + pct + "%)"
    }

    /** One-line device summary shown above the file list. */
    fun deviceSummary(p: Profile): String {
        val accel = if (p.gpuAvailable) "GPU (Vulkan, shared RAM)" else "CPU"
        return ModelRepository.formatSize(p.totalRamBytes) + " RAM - " +
            ModelRepository.formatSize(p.availRamBytes) + " free - " + accel
    }
}
