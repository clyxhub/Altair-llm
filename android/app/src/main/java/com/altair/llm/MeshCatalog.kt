package com.altair.llm

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 4.5 — mesh inference.
 *
 * Every Altair engine on the LAN advertises its local server over mDNS
 * (`_http._tcp`). This catalog discovers those peers and aggregates their
 * `/v1/models` into the model registry as LOCAL_LAN profiles, so an orchestrator
 * can offload a different modality/model to a sibling device (e.g. a PC) while
 * this phone runs its own model — many local models in parallel across devices.
 */
object MeshCatalog {

    data class Peer(val name: String, val host: String, val port: Int, val models: List<String>)

    @Volatile private var peers: List<Peer> = emptyList()

    fun cached(): List<Peer> = peers

    /** Profiles published by discovered peers. */
    fun profiles(): List<ModelProfile> =
        peers.flatMap { p ->
            p.models.map { m ->
                ModelProfile(
                    id = "lan:${p.name}:$m",
                    label = "$m @ ${p.name}",
                    tier = ModelProfile.Tier.LOCAL_LAN,
                    contextTokens = 8192,
                    supportsTools = false,
                    costPerMTokUsd = 0.0,
                    speedTier = 2
                )
            }
        }

    /** Discover peers for [windowMs], fetch their model lists, then call [onDone]. */
    fun discover(ctx: Context, windowMs: Long = 4000, onDone: (List<Peer>) -> Unit) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            onDone(peers); return
        }
        val found = LinkedHashMap<String, Peer>()
        val lock = Any()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                runCatching { nsd.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(service: NsdServiceInfo?) {}

            override fun onServiceFound(service: NsdServiceInfo?) {
                service ?: return
                runCatching {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo?) {
                            val info = si ?: return
                            val host = info.host?.hostAddress ?: return
                            val name = info.serviceName ?: "peer"
                            Thread({
                                val models = modelsAt(host, info.port)
                                if (models.isNotEmpty()) {
                                    synchronized(lock) { found[name] = Peer(name, host, info.port, models) }
                                }
                            }, "mesh-fetch").start()
                        }
                    })
                }
            }
        }

        runCatching { nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }

        Thread({
            Thread.sleep(windowMs)
            runCatching { nsd.stopServiceDiscovery(listener) }
            synchronized(lock) {
                peers = found.values.toList()
                onDone(peers)
            }
        }, "mesh-discover").start()
    }

    private fun modelsAt(host: String, port: Int): List<String> = runCatching {
        val conn = URL("http://$host:$port/v1/models").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 2500
        val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        conn.disconnect()
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}
