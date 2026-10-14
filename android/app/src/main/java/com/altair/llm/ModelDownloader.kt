package com.altair.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable downloader for GGUF files from HuggingFace.
 * HTTP Range-based: pause/resume survives process death (progress = .part file size).
 */
object ModelDownloader {

    data class Job(
        val id: String,
        val url: String,
        val dest: File,
        @Volatile var status: String, // downloading | paused | done | error
        @Volatile var downloaded: Long = 0,
        @Volatile var total: Long = 0,
        @Volatile var error: String? = null,
        @Volatile var cancelRequested: Boolean = false,
        @Volatile var pauseRequested: Boolean = false,
    )

    private val jobs = HashMap<String, Job>()
    private val lock = Any()

    fun start(url: String, dest: File): Job {
        val id = "dl-${System.currentTimeMillis().toString(36)}-${(dest.name).hashCode().toString(36)}"
        val job = Job(id, url, dest, status = "downloading")
        val part = File(dest.parentFile, dest.name + ".part")
        job.downloaded = if (part.exists()) part.length() else 0
        synchronized(lock) { jobs[id] = job }
        Thread { run(job) }.start()
        return job
    }

    private fun run(job: Job) {
        val part = File(job.dest.parentFile, job.dest.name + ".part")
        try {
            while (true) {
                if (job.cancelRequested) {
                    part.delete()
                    job.status = "error"
                    job.error = "cancelled"
                    return
                }
                if (job.pauseRequested) {
                    job.status = "paused"
                    return
                }
                val conn = URL(job.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                if (job.downloaded > 0) conn.setRequestProperty("Range", "bytes=${job.downloaded}-")
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code !in 200..299) {
                    job.status = "error"
                    job.error = "HTTP $code"
                    conn.disconnect()
                    return
                }
                val resuming = code == 206
                if (!resuming) {
                    // server ignored Range — restart from scratch
                    if (job.downloaded > 0) {
                        job.downloaded = 0
                        part.delete()
                        conn.disconnect()
                        continue
                    }
                }
                job.total = job.downloaded + (conn.contentLengthLong.takeIf { it > 0 } ?: 0)

                conn.inputStream.use { input ->
                    RandomAccessFile(part, "rw").use { out ->
                        out.seek(job.downloaded)
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (job.pauseRequested || job.cancelRequested) {
                                conn.disconnect()
                                return@use
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            job.downloaded += n
                        }
                    }
                }
                conn.disconnect()

                if (job.total in 1..job.downloaded) {
                    if (job.cancelRequested) continue
                    if (!part.renameTo(job.dest)) {
                        // fallback: copy+delete (rename can fail across mounts)
                        part.copyTo(job.dest, overwrite = true)
                        part.delete()
                    }
                    job.status = "done"
                    return
                }
                if (job.pauseRequested) { job.status = "paused"; return }
            }
        } catch (e: Exception) {
            if (job.pauseRequested) job.status = "paused"
            else if (job.cancelRequested) { part.delete(); job.status = "error"; job.error = "cancelled" }
            else { job.status = "error"; job.error = e.message ?: e.javaClass.simpleName }
        }
    }

    fun pause(id: String) = synchronized(lock) { jobs[id]?.let { it.pauseRequested = true; it.status = "pausing" } }
    fun resume(id: String) {
        val job = synchronized(lock) { jobs[id] } ?: return
        if (job.status != "paused" && job.status != "pausing" && job.status != "error") return
        job.pauseRequested = false
        job.error = null
        val part = File(job.dest.parentFile, job.dest.name + ".part")
        job.downloaded = if (part.exists()) part.length() else 0
        job.status = "downloading"
        Thread { run(job) }.start()
    }
    fun cancel(id: String) = synchronized(lock) {
        jobs[id]?.let { it.cancelRequested = true; it.status = "pausing" }
    }

    /** All jobs as JSON array of {id,file,url,status,downloaded,total,error}. */
    fun statusJson(): String {
        val arr = JSONArray()
        synchronized(lock) {
            for (j in jobs.values) {
                arr.put(
                    JSONObject()
                        .put("id", j.id)
                        .put("file", j.dest.name)
                        .put("url", j.url)
                        .put("status", j.status)
                        .put("downloaded", j.downloaded)
                        .put("total", j.total)
                        .put("error", j.error ?: JSONObject.NULL)
                )
            }
        }
        return arr.toString()
    }
}
