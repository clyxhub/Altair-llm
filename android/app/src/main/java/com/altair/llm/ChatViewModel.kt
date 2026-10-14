package com.altair.llm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Everything the UI renders. */
data class UiState(
    val chats: List<Chat> = emptyList(),
    val current: Chat? = null,
    val streaming: String = "",
    val busy: Boolean = false,
    val status: String = "No model loaded",
    val modelInfo: String = "",
    val models: List<ModelRepository.Entry> = emptyList(),
    val currentModelId: String? = null,
    val currentModelPath: String? = null,
    val preset: PresetEngine.Preset? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val gpuLayers: Int = 0,
    val gpuAuto: Boolean = false,
    val gpuAvailable: Boolean = false,
    val threads: Int = 4,
    val contextSize: Int = 4096,
    val thinking: Boolean = false,
    val webSearch: Boolean = false,
    val attachment: String? = null,
    val attachmentIsAudio: Boolean = false,
    val serverRunning: Boolean = false,
    val serverPort: Int = 8080,
    val providerSelectionVersion: Int = 0,
    /** All mmproj files discovered in the scan roots — the explicit pairing picker. */
    val projectorChoices: List<ModelRepository.Entry> = emptyList()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("altair_llm", Context.MODE_PRIVATE)
    private val chats: MutableList<Chat> = ChatStore.load(application)
    private var currentId: String? = prefs.getString(KEY_CURRENT, null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (chats.isEmpty()) ChatStore.newChat(chats)
        if (currentId == null || chats.none { it.id == currentId }) currentId = chats.first().id

        // GPU policy: CPU by default. GPU (Vulkan) is opt-in and, before it is
        // ever used in the UI process, it must pass an isolated :gpuprobe load
        // (see probeNative) — so a flaky driver can never crash the app on install.
        var gpuPref = prefs.getInt(KEY_GPU_LAYERS, 0)
        var autoPref = prefs.getBoolean(KEY_GPU_AUTO, false)
        if (prefs.getBoolean(KEY_GPU_ATTEMPT, false)) {
            Log.w(TAG, "previous GPU load did not complete — disabling GPU offload (CPU mode)")
            gpuPref = 0
            autoPref = false
            prefs.edit().putInt(KEY_GPU_LAYERS, 0).putBoolean(KEY_GPU_AUTO, false)
                .putBoolean(KEY_GPU_ATTEMPT, false).putBoolean(KEY_GPU_VERIFIED, false).apply()
        }
        _state.value = UiState(
            chats = ordered(),
            current = current(),
            gpuLayers = gpuPref,
            gpuAuto = autoPref,
            thinking = prefs.getBoolean(KEY_THINKING, false),
            webSearch = prefs.getBoolean(KEY_WEB, false),
            serverPort = prefs.getInt(KEY_SERVER_PORT, 8080),
            status = "No model loaded"
        )

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                LlamaBridge.init()
                val gpu = LlamaBridge.supportsGpu()
                _state.update { it.copy(gpuAvailable = gpu) }
                Log.i(TAG, "system: " + LlamaBridge.systemInfo())
            }
            refreshModels()
            ToolRegistry.prefetch(getApplication())
            // Quick win: remember the last-used model and auto-load it.
            val last = prefs.getString(KEY_LAST_MODEL, null)
            if (last != null) {
                val entry = _state.value.models.firstOrNull { it.path == last }
                if (entry != null) loadEntry(entry)
            }
        }
    }

    // --- chat list ----------------------------------------------------------

    private fun ordered(): List<Chat> =
        chats.sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.updatedAt })

    private fun current(): Chat? = chats.firstOrNull { it.id == currentId }

    private fun save() {
        ChatStore.save(getApplication(), chats)
        prefs.edit().putString(KEY_CURRENT, currentId).apply()
    }

    private fun push() = _state.update { it.copy(chats = ordered(), current = current()) }

    fun newChat() {
        val c = ChatStore.newChat(chats)
        currentId = c.id
        save(); push()
    }

    fun selectChat(id: String) { currentId = id; save(); push() }

    fun renameChat(id: String, title: String) { ChatStore.rename(chats, id, title); save(); push() }

    fun deleteChat(id: String) {
        ChatStore.delete(chats, id)
        if (chats.isEmpty()) ChatStore.newChat(chats)
        if (currentId == id) currentId = chats.first().id
        save(); push()
    }

    fun togglePin(id: String) { ChatStore.togglePin(chats, id); save(); push() }

    fun exportCurrent(): String = current()?.let { ChatStore.exportMarkdown(it) } ?: ""

    // --- model library ------------------------------------------------------

    private fun roots(): List<String> =
        prefs.getStringSet(ROOTS, null)?.toList()?.takeIf { it.isNotEmpty() }
            ?: ModelRepository.defaultRoots()

    private suspend fun refreshModels() = withContext(Dispatchers.IO) {
        val r = roots()
        val overrides = projectorOverrides()
        val list = runCatching { ModelRepository.all(getApplication(), r) }.getOrDefault(emptyList())
            .map { e ->
                val p = e.path
                if (p != null && overrides.containsKey(p)) {
                    e.copy(projector = overrides[p]?.takeIf { it.isNotBlank() })
                } else e
            }
        val projs = runCatching { ModelRepository.scanProjectors(r) }.getOrDefault(emptyList())
        _state.update { it.copy(models = list, projectorChoices = projs) }
        // Keep the embedded server catalogue in sync with the library so a running
        // server lists every local GGUF and every selected cloud model.
        LocalServer.updateCatalogue(LocalServer.cardsFrom(list))
    }

    /** Explicit model path -> projector path overrides (empty string means "none"). */
    private fun projectorOverrides(): Map<String, String> =
        prefs.getStringSet(KEY_PROJ_OVERRIDE, null)?.mapNotNull { entry ->
            val i = entry.indexOf('\u0001')
            if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
        }?.toMap() ?: emptyMap()

    /** Explicitly pair (or unpair with null) an mmproj with a local model. */
    fun setProjector(modelPath: String, projectorPath: String?) {
        val set = prefs.getStringSet(KEY_PROJ_OVERRIDE, null)?.toMutableSet() ?: mutableSetOf()
        set.removeAll { it.startsWith(modelPath + "\u0001") }
        if (projectorPath != null) set.add(modelPath + "\u0001" + projectorPath)
        prefs.edit().putStringSet(KEY_PROJ_OVERRIDE, set).apply()
        refreshModelsNow()
    }

    fun refreshModelsNow() {
        viewModelScope.launch { refreshModels() }
    }

    /** Effective GPU layer count: AUTO = all when a GPU backend exists, else CPU. */
    private fun resolveGpu(): Int {
        val s = _state.value
        return if (s.gpuAuto) (if (LlamaBridge.supportsGpu()) GPU_LAYERS_ALL else 0) else s.gpuLayers
    }

    /**
     * Verify GPU offload AND projector loading in the isolated :nativeprobe
     * process. Returns a [ProbeResult]; a driver crash there is contained (the
     * progress file shows which step died) and never reaches the UI process.
     * Blocking; call from Dispatchers.IO.
     */
    private data class ProbeResult(val gpuOk: Boolean, val projectorOk: Boolean, val detail: String)

    private fun probeNative(path: String, mmproj: String?, ctx: Int, threads: Int, ngl: Int): ProbeResult {
        val app = getApplication<Application>()
        val marker = File(app.filesDir, NativeProbeService.PROGRESS_FILE)
        runCatching { marker.delete() }

        val latch = CountDownLatch(1)
        val result = intArrayOf(-1)
        var progress = ""

        val clientMessenger = Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == NativeProbeService.MSG_RESULT) {
                    result[0] = msg.arg1
                    progress = msg.data?.getString("progress") ?: ""
                    latch.countDown()
                }
                true
            }
        )

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    result[0] = -101
                    latch.countDown()
                    return
                }
                val b = Bundle().apply {
                    putString("path", path)
                    putString("mmproj", mmproj.orEmpty())
                    putInt("ctx", ctx)
                    putInt("threads", threads)
                    putInt("ngl", ngl)
                }
                val m = Message.obtain(null, NativeProbeService.MSG_LOAD)
                m.data = b
                m.replyTo = clientMessenger
                try {
                    Messenger(service).send(m)
                } catch (e: RemoteException) {
                    result[0] = -101
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Probe process died (native fault contained there).
                LogRing.add("probe", "probe process died during native load")
                latch.countDown()
            }
        }

        val bound = try {
            app.bindService(Intent(app, NativeProbeService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
        if (!bound) {
            LogRing.add("probe", "could not bind probe service")
            return ProbeResult(gpuOk = false, projectorOk = false, detail = "bind failed")
        }

        val done = latch.await(240, TimeUnit.SECONDS)
        runCatching { app.unbindService(conn) }
        if (progress.isBlank()) progress = runCatching { marker.readText() }.getOrDefault("")
        LogRing.add("probe", "probe finished done=$done rc=${result[0]} file=${progress.trim().replace("\n", "|")}")

        val modelOk = progress.contains("model_ok") || progress.contains("proj_ok") || progress.contains("done")
        val projOk = progress.contains("proj_ok") || progress.contains("done")
        return ProbeResult(gpuOk = done && modelOk, projectorOk = projOk, detail = progress.trim())
    }

    /** Load a library entry: local gguf (+ auto mmproj) or a cloud model. */
    fun loadEntry(entry: ModelRepository.Entry) {
        if (!entry.isLocal) {
            _state.update {
                it.copy(
                    currentModelId = entry.id,
                    currentModelPath = null,
                    status = "Cloud: ${entry.providerName} · ${entry.name}",
                    modelInfo = "${entry.providerName} · ${entry.modelId}",
                    preset = PresetEngine.Preset("Cloud", 0.7f, 0.9f, 40, 8192, "none"),
                    capabilities = ModelCapabilities(embeddings = false),
                    busy = false
                )
            }
            prefs.edit().putString(KEY_LAST_MODEL, null).apply()
            return
        }
        val path = entry.path ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(status = "Loading…", busy = true) }
            val preset0 = PresetEngine.detect(entry.name)
            val gpuAvailable = LlamaBridge.supportsGpu()
            val cfg = AutoTuner.recommend(getApplication(), path, gpuAvailable)

            // Memory-aware context: an 8K f16 KV cache + a 1.3 GB model + a
            // 365 MB projector is what gets the process killed (LMK) right after
            // load on a 6 GB phone — which looks exactly like a crash.
            val prof = DeviceFit.profile(getApplication(), gpuAvailable)
            val modelBytes = runCatching { File(path).length() }.getOrDefault(0L)
            val projBytes = runCatching { entry.projector?.let { File(it).length() } ?: 0L }.getOrDefault(0L)
            val ctxSize = DeviceFit.safeContext(preset0.contextSize, modelBytes, projBytes, prof)
            val gib = 1024.0 * 1024.0 * 1024.0
            LogRing.add(
                "mem",
                "total=%.1fGB avail=%.1fGB model=%.2fGB proj=%.2fGB ctx=%d (preset %d)".format(
                    prof.totalRamBytes / gib, prof.availRamBytes / gib,
                    modelBytes / gib, projBytes / gib, ctxSize, preset0.contextSize
                )
            )
            // AUTO: prefer the resolved policy; otherwise honour the tuned/manual value.
            var gpu = resolveGpu()
            if (gpu <= 0 && _state.value.gpuAuto && cfg.gpuLayers > 0) gpu = cfg.gpuLayers

            // Resolve the projector pairing up front so the isolated probe can
            // verify model+projector together: the mmproj native path has never
            // been proven on this device class, and a fault there used to kill
            // the UI process right after "model loaded".
            val ov = projectorOverrides()[path]
            var projPath = if (ov != null) ov.takeIf { it.isNotBlank() } else entry.projector

            val gpuVerified = prefs.getBoolean(KEY_GPU_VERIFIED, false)
            val projVerified = prefs.getStringSet(KEY_PROJ_VERIFIED, emptySet()) ?: emptySet()
            val projectorVerified = projPath == null || projVerified.contains(projPath)
            val needProbe = (gpu > 0 && !gpuVerified) || !projectorVerified

            if (needProbe) {
                _state.update { it.copy(status = "Verifying hardware (isolated)…") }
                val pr = probeNative(path, projPath, ctxSize, cfg.threads, gpu)

                if (gpu > 0) {
                    if (pr.gpuOk) {
                        if (!gpuVerified) {
                            prefs.edit().putBoolean(KEY_GPU_VERIFIED, true).apply()
                            LogRing.add("load", "GPU verified — offloading $gpu layers")
                        }
                    } else {
                        LogRing.add("load", "GPU verification failed — CPU mode")
                        prefs.edit().putInt(KEY_GPU_LAYERS, 0).putBoolean(KEY_GPU_AUTO, false)
                            .putBoolean(KEY_GPU_VERIFIED, false).apply()
                        _state.update { it.copy(gpuAuto = false, gpuLayers = 0) }
                        gpu = 0
                    }
                }
                if (projPath != null && !projectorVerified) {
                    if (pr.projectorOk) {
                        prefs.edit().putStringSet(KEY_PROJ_VERIFIED, projVerified + projPath).apply()
                    } else {
                        LogRing.add("load", "projector unsafe here — running text-only")
                        projPath = null
                    }
                }
            }

            LogRing.add("load", "ctx=${ctxSize} threads=${cfg.threads} ngl=$gpu proj=${projPath != null}")

            // Mark the attempt in-flight: if the process dies here, init() will
            // detect this flag on next launch and disable GPU offload.
            if (gpu > 0) prefs.edit().putBoolean(KEY_GPU_ATTEMPT, true).apply()
            // KV-cache quantization (default q8_0): roughly halves the context
            // memory so long contexts don't OOM the device.
            // Default to f16 (OFF): quantized KV caches are opt-in because some
            // backends/models reject them, and a bad KV cache silently kills output.
            var kvMode = prefs.getInt(KEY_KV_QUANT, LlamaBridge.KV_Q8_0)
            LlamaBridge.setKvQuant(kvMode)
            var rc = runCatching { LlamaBridge.loadModel(path, ctxSize, cfg.threads, gpu) }.getOrDefault(-99)
            if (rc != 0 && gpu > 0) {
                Log.w(TAG, "GPU load failed rc=$rc — retrying CPU-only")
                gpu = 0
                // A non-fatal GPU failure means the verified assumption no longer
                // holds for this model/driver — re-verify on next attempt.
                prefs.edit().putBoolean(KEY_GPU_VERIFIED, false).apply()
                LlamaBridge.setKvQuant(kvMode)
                rc = runCatching { LlamaBridge.loadModel(path, ctxSize, cfg.threads, 0) }.getOrDefault(-99)
            }
            if (rc != 0 && kvMode != LlamaBridge.KV_OFF) {
                // Some models reject a quantized KV cache — fall back to f16.
                Log.w(TAG, "load failed rc=$rc — retrying with f16 KV cache")
                kvMode = LlamaBridge.KV_OFF
                LlamaBridge.setKvQuant(kvMode)
                rc = runCatching { LlamaBridge.loadModel(path, ctxSize, cfg.threads, gpu) }.getOrDefault(-99)
            }
            if (rc != 0) {
                Log.e(TAG, "load failed rc=$rc")
                prefs.edit().putBoolean(KEY_GPU_ATTEMPT, false).apply()
                _state.update { it.copy(busy = false, status = "Load failed (code $rc)") }
                return@launch
            }
            prefs.edit().putBoolean(KEY_GPU_ATTEMPT, false).apply()

            val preset = PresetEngine.refine(preset0).copy(contextSize = ctxSize)
            val info = PresetEngine.describeModel()

            var projectorOk = false
            projPath?.let { p ->
                projectorOk = runCatching { LlamaBridge.loadProjector(p, cfg.threads) }.getOrDefault(-1) == 0
                if (!projectorOk) LogRing.add("load", "projector load failed after verification")
            }
            val caps = ModelCapabilities.ofLoadedModel(reasoning = preset.reasoning)
            val backend = if (gpu > 0) "GPUx$gpu" else "CPU"
            LogRing.add("load", "loaded ${preset.family} ctx=${LlamaBridge.contextSize()} $backend proj=$projectorOk")

            AutoTuner.record(getApplication(), path, AutoTuner.Config(cfg.threads, gpu))

            // Publish the loaded model to the embedded server.
            LocalServer.updateModel(
                LocalServer.ModelSnapshot(
                    id = entry.name, contextSize = preset.contextSize,
                    capabilities = caps, chatTemplate = LlamaBridge.chatTemplate()
                )
            )

            _state.update {
                it.copy(
                    currentModelId = entry.id, currentModelPath = path,
                    busy = false, preset = preset, capabilities = caps,
                    threads = cfg.threads, contextSize = preset.contextSize,
                    thinking = if (prefs.getBoolean(KEY_THINKING_MANUAL, false)) it.thinking else preset.reasoning,
                    gpuLayers = if (gpu > 0) gpu else it.gpuLayers,
                    modelInfo = "$info · $backend · ${cfg.threads} threads" + if (projectorOk) " · projector ok" else "",
                    status = "Loaded ${preset.family} · ctx ${LlamaBridge.contextSize()} · $backend"
                )
            }
        }
    }

    // --- generation ---------------------------------------------------------

    /**
     * Phase 4.5: multi-agent run. Subagents run sequentially on this device
     * (one resident model), each with its own small context, and the last step
     * synthesizes the final answer. Mode should scale with the model profile in
     * later tuning; three roles keeps a phone budget sane.
     */
    private fun runAgentTask(chat: Chat, task: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sb = StringBuilder("**Multi-agent run**\n\n")
            _state.update { it.copy(streaming = sb.toString()) }

            val roles = listOf(AgentRoles.PLANNER, AgentRoles.BUILDER, AgentRoles.REVIEWER)
            val outputs = ArrayList<Pair<AgentRole, String>>()
            for (role in roles) {
                val brief = outputs.joinToString("\n\n") { "[" + it.first.name + "]\n" + it.second }.take(3000)
                val prompt = "${role.systemPrompt}\n\nTask:\n$task" +
                    (if (brief.isBlank()) "" else "\n\nContext so far:\n$brief")
                val out = StringBuilder()
                runCatching { LlamaBridge.generate(prompt, 384, 0.7f, 0.9f) { t -> out.append(t) } }
                val text = scrub(out.toString().trim())
                outputs.add(role to text)
                sb.append("### ").append(role.name).append("\n").append(text).append("\n\n")
                _state.update { it.copy(streaming = sb.toString()) }
                LogRing.add("agent", "${role.name} -> ${text.take(100)}")
            }

            val synth = outputs.joinToString("\n\n") { "[" + it.first.name + "]\n" + it.second }.take(5000)
            val finPrompt = "Synthesize the final answer for the user from these specialist outputs. " +
                "Be concise and complete.\n\nTask:\n$task\n\n$synth"
            val fin = StringBuilder()
            runCatching { LlamaBridge.generate(finPrompt, 512, 0.7f, 0.9f) { t -> fin.append(t) } }
            sb.append("### Final\n").append(scrub(fin.toString().trim()))

            chat.messages.add(Msg("assistant", sb.toString()))
            chat.updatedAt = System.currentTimeMillis()
            save(); push()
            _state.update { it.copy(busy = false, streaming = "", status = "Agent run complete") }
        }
    }

    private fun buildPrompt(
        preset: PresetEngine.Preset,
        turns: List<Msg>,
        thinking: Boolean,
        memories: List<String> = emptyList()
    ): String {
        // Apply the Qwen3 soft switch to the final user turn first.
        val prepared = turns.mapIndexed { i, m ->
            var c = m.content
            if (!thinking && m.role == "user" && i == turns.lastIndex) c = "$c /no_think"
            ContextBudgeter.Turn(m.role, c)
        }

        // §5 Token budgeter: fit system + turns into the model's REAL window,
        // compacting older turns so the request never overflows (or OOMs).
        val ctxTokens = runCatching { LlamaBridge.contextSize() }.getOrDefault(preset.contextSize)
        val profile = ModelProfile(
            id = "local",
            label = preset.family,
            tier = ModelProfile.Tier.LOCAL_DEVICE,
            contextTokens = ctxTokens,
            reasoning = preset.reasoning
        )
        val budgeted = ContextBudgeter.fit(
            system = SYSTEM_PROMPT,
            turns = prepared,
            profile = profile,
            countTokens = { s -> runCatching { LlamaBridge.countTokens(s) }.getOrDefault((s.length / 4).coerceAtLeast(1)) }
        )
        if (budgeted.summarized) {
            LogRing.add("budget", "compacted ${budgeted.droppedTurns} turn(s) to fit ctx=$ctxTokens")
            // §3 episodic memory: archive what we dropped so it can be recalled.
            val keptLast = budgeted.turns.lastOrNull()?.content?.take(120) ?: ""
            runCatching {
                MemoryStore.addEpisode(
                    getApplication(),
                    current()?.id,
                    "Compacted ${budgeted.droppedTurns} older message(s) in this chat; resuming after: $keptLast"
                )
            }
        }

        val roles = mutableListOf<String>()
        val contents = mutableListOf<String>()
        if (SYSTEM_PROMPT.isNotBlank()) { roles += "system"; contents += SYSTEM_PROMPT }
        // Phase 4: advertise available tools + the (text) call protocol so even
        // small local models can drive them.
        if (prefs.getBoolean(KEY_TOOLS_ENABLED, true)) {
            roles += "system"
            contents += (
                "You can use tools. Available tools:\n" + ToolRegistry.cachedCatalogue() +
                    "\nTo call a tool, reply with exactly one line:\n" +
                    "TOOL: <tool_name> {\"arg\":\"value\"}\n" +
                    "After the result is provided, continue answering the user."
                )
        }
        if (memories.isNotEmpty()) {
            roles += "system"
            contents += ("Relevant long-term memory:\n" + memories.joinToString("\n") { "- $it" })
            LogRing.add("memory", "injected ${memories.size} memory snippet(s)")
        }
        budgeted.turns.forEach { roles += it.role; contents += it.content }

        val tmpl = if (preset.chatTemplate == "gguf" || preset.chatTemplate == "none") "" else preset.chatTemplate
        val viaModel = runCatching { LlamaBridge.applyChatTemplate(tmpl, roles, contents, true) }.getOrNull()
        return viaModel ?: PresetEngine.applyTemplate(preset, SYSTEM_PROMPT, turns.lastOrNull()?.content ?: "")
    }

    /** Remove a chain-of-thought trace (closed, or still-open) when thinking is off. */
    private fun stripThinking(text: String): String {
        var t = text
        if (t.contains(THINK_CLOSE)) t = t.substringAfterLast(THINK_CLOSE)
        else if (t.contains(THINK_OPEN)) t = t.substringBefore(THINK_OPEN)
        return t.trim()
    }

    /** Drop the literal empty-token artifact ("<>") some templates/models emit. */
    private fun scrub(text: String): String {
        var t = text.trimStart()
        while (true) {
            if (t.startsWith(NULL_TOKEN)) { t = t.removePrefix(NULL_TOKEN).trimStart(); continue }
            if (t.startsWith(NULL_WORD, ignoreCase = true) &&
                (t.length == NULL_WORD.length || t[NULL_WORD.length].isWhitespace())
            ) { t = t.substring(NULL_WORD.length).trimStart(); continue }
            break
        }
        return t
    }

    /** What the UI should display for raw model output, honouring thinking mode. */
    private fun display(raw: String): String = if (_state.value.thinking) raw else scrub(stripThinking(raw))

    fun send(input: String, mediaPath: String? = null) {
        val chat = current() ?: return

        // Phase 4.5: multi-agent run via the /agent command.
        val trimmed = input.trim()
        if (trimmed.startsWith("/agent ")) {
            val task = trimmed.removePrefix("/agent ").trim()
            if (task.isBlank()) return
            chat.messages.add(Msg("user", task))
            ChatStore.autoTitle(chat)
            chat.updatedAt = System.currentTimeMillis()
            save()
            _state.update { it.copy(current = current(), streaming = "", busy = true, status = "Agent run…") }
            runAgentTask(chat, task)
            return
        }

        val s = _state.value
        if ((input.isBlank() && mediaPath == null) || s.busy) return

        val text = input.ifBlank { "Describe this." }
        chat.messages.add(Msg("user", text))
        ChatStore.autoTitle(chat)
        chat.updatedAt = System.currentTimeMillis()
        save()
        _state.update {
            it.copy(current = current(), chats = ordered(), streaming = "", busy = true,
                status = "Generating…", attachment = null, attachmentIsAudio = false)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val started = System.currentTimeMillis()
            val s2 = _state.value

            if (s2.currentModelId?.startsWith("cloud:") == true) {
                runCloud(chat, s2, sb)
            } else {
                val preset = s2.preset
                if (preset != null) {
                    var query = text
                    if (s2.webSearch) {
                        val hits = runCatching { WebSearch.search(text) }.getOrDefault(emptyList())
                        val block = WebSearch.contextBlock(text, hits)
                        if (block.isNotBlank()) query = block + "\n\nQuestion: " + text
                    }
                    val turns = chat.messages.dropLast(1) + Msg("user", query)
                    // §3 retrieval: pull relevant long-term memory; depth scales
                    // with the model's context profile (more room => more recall).
                    val depth = ModelProfile(
                        id = "local", label = preset.family,
                        tier = ModelProfile.Tier.LOCAL_DEVICE, contextTokens = s2.contextSize
                    ).retrievalDepth
                    val mems = runCatching { MemoryStore.search(getApplication(), text, depth) }
                        .getOrDefault(emptyList()).map { it.text }
                    val prompt = buildPrompt(preset, turns, s2.thinking, mems)
                    runCatching {
                        if (mediaPath != null) {
                            LlamaBridge.generateWithMedia(prompt, mediaPath, MAX_TOKENS, preset.temperature, preset.topP) { t ->
                                sb.append(t); _state.update { it.copy(streaming = display(sb.toString())) }
                            }
                        } else {
                            LlamaBridge.generate(prompt, MAX_TOKENS, preset.temperature, preset.topP) { t ->
                                sb.append(t); _state.update { it.copy(streaming = display(sb.toString())) }
                            }
                        }
                    }

                    // Phase 4: agentic tool loop — if the reply requests a tool,
                    // run it and feed the result back, at most twice per turn
                    // (phone-friendly budget; the token budgeter still applies).
                    if (prefs.getBoolean(KEY_TOOLS_ENABLED, true)) {
                        var rounds = 0
                        var transcript = prompt + sb.toString()
                        while (rounds < 2) {
                            val directive = runCatching { ToolRegistry.takeDirective(getApplication(), sb.toString()) }.getOrNull() ?: break
                            val (tool, args) = directive
                            val result = runCatching { tool.run(args) }.getOrElse { "tool error: ${it.message}" }
                            LogRing.add("tool", "${tool.spec.name} -> ${result.take(140)}")
                            sb.clear()
                            val next = transcript + "\nTOOL_RESULT (" + tool.spec.name + "): " + result + "\n"
                            runCatching {
                                LlamaBridge.generate(next, MAX_TOKENS, preset.temperature, preset.topP) { t ->
                                    sb.append(t); _state.update { it.copy(streaming = display(sb.toString())) }
                                }
                            }
                            transcript = next
                            rounds++
                        }
                    }
                }
            }

            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            val finalText = if (s2.thinking) sb.toString().trim() else scrub(stripThinking(sb.toString()))
            val speed = if (finalText.isNotEmpty()) " · %.1fs".format(elapsed / 1000.0) else ""

            // 14th requirement: pull any generated files (URLs / base64 / data URIs)
            // out of the reply and materialise them into app storage so they can be
            // viewed, tested, shared and downloaded — regardless of which model made them.
            val artifactList: List<Artifact> = runCatching {
                val raws = mutableListOf<RawArtifact>()
                raws += ArtifactScanner.fromText(finalText)
                runCatching { JSONObject(finalText) }.getOrNull()?.let { raws += ArtifactScanner.fromJson(it) }
                val distinct = raws.distinctBy { it.url ?: it.b64 }
                if (distinct.isEmpty()) emptyList() else ArtifactStore.materializeAll(getApplication<Application>(), distinct)
            }.getOrDefault(emptyList())

            withContext(Dispatchers.Main) {
                val c = current()
                if (c != null) {
                    c.messages.add(Msg("assistant", finalText, artifacts = artifactList))
                    c.updatedAt = System.currentTimeMillis()
                }
                save()
                _state.update {
                    it.copy(current = current(), chats = ordered(), streaming = "", busy = false,
                        status = "Ready$speed")
                }
            }
        }
    }

    private fun runCloud(chat: Chat, s: UiState, sb: StringBuilder) {
        val pid = s.currentModelId?.removePrefix("cloud:")?.substringBefore("::") ?: return
        val model = s.currentModelId?.substringAfter("::") ?: return
        val provider = ProviderStore.resolve(getApplication(), pid) ?: return
        val key = ProviderStore.keysFor(getApplication(), pid).firstOrNull()?.apiKey ?: ""
        if (key.isBlank()) {
            sb.append("[no API key for ${provider.name}]")
            return
        }
        val msgs = chat.messages.map { it.role to it.content }
        val result = runCatching {
            CloudEngine.chat(provider, key, model, msgs, 0.7f) { t ->
                sb.append(t); _state.update { it.copy(streaming = sb.toString()) }
            }
        }
        // Never show a silent empty reply: if the request failed and nothing was
        // streamed, tell the user exactly what went wrong in the assistant bubble.
        if (result.isFailure && sb.isBlank()) {
            val why = result.exceptionOrNull()?.message ?: "unknown error"
            sb.append("[request failed — $why]")
        }
    }

    fun regenerate() {
        val c = current() ?: return
        val lastUser = c.messages.lastOrNull { it.role == "user" } ?: return
        c.messages.removeAll { it.role == "assistant" && c.messages.indexOf(it) > c.messages.indexOf(lastUser) }
        send(lastUser.content)
    }

    /** Re-run generation for the assistant message at [index] (drop it + its prompt first). */
    fun retryAt(index: Int) {
        val c = current() ?: return
        if (index !in c.messages.indices) return
        val userIdx = (index downTo 0).firstOrNull { c.messages[it].role == "user" } ?: return
        val userText = c.messages[userIdx].content
        c.messages.subList(userIdx, c.messages.size).clear()
        save(); push()
        send(userText)
    }

    fun editAndResend(index: Int, newText: String) {
        val c = current() ?: return
        if (index !in c.messages.indices) return
        c.messages.subList(index, c.messages.size).clear()
        send(newText)
    }

    fun deleteMessage(index: Int) {
        val c = current() ?: return
        if (index in c.messages.indices) { c.messages.removeAt(index); save(); push() }
    }

    // --- toggles ------------------------------------------------------------

    fun setThinking(on: Boolean) {
        prefs.edit().putBoolean(KEY_THINKING, on).putBoolean(KEY_THINKING_MANUAL, true).apply()
        _state.update { it.copy(thinking = on) }
    }
    fun setWebSearch(on: Boolean) { prefs.edit().putBoolean(KEY_WEB, on).apply(); _state.update { it.copy(webSearch = on) } }
    fun setAttachment(path: String, isAudio: Boolean = false) = _state.update { it.copy(attachment = path, attachmentIsAudio = isAudio) }
    fun clearAttachment() = _state.update { it.copy(attachment = null, attachmentIsAudio = false) }
    /** Server state for the UI. The port is persisted and never zeroed when the
     *  server stops — 0 is the "not running" socket state, not the configured port. */
    fun setServer(running: Boolean, port: Int = _state.value.serverPort) {
        val effective = if (port > 0) port else _state.value.serverPort
        if (effective > 0) prefs.edit().putInt(KEY_SERVER_PORT, effective).apply()
        _state.update { it.copy(serverRunning = running, serverPort = effective) }
    }
    fun bumpProviderSelection() = _state.update { it.copy(providerSelectionVersion = it.providerSelectionVersion + 1) }

    // --- generated artifacts (any generative model: local or cloud) ----------

    /** Download a remote artifact into app storage and persist the local copy. */
    fun materializeArtifact(a: Artifact) {
        if (a.path != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val done = ArtifactStore.materialize(getApplication<Application>(), a)
            withContext(Dispatchers.Main) { replaceArtifact(done) }
        }
    }

    /** Export an artifact to the device's public Downloads folder. */
    fun downloadArtifact(a: Artifact) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val local = ArtifactStore.materialize(ctx, a)
            val ok = ArtifactStore.exportToDownloads(ctx, local)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    ctx,
                    if (ok) "Saved " + local.name + " to Downloads" else "Could not save " + a.name,
                    Toast.LENGTH_SHORT
                ).show()
                if (local.path != a.path) replaceArtifact(local)
            }
        }
    }

    /**
     * Public hook: attach an already-produced file to the active chat. Any local
     * generator (e.g. a diffusion / music model) or plugin can push bytes here and
     * the file appears as a viewable / downloadable / testable artifact.
     */
    fun addGeneratedFile(name: String, mime: String, bytes: ByteArray, origin: String = "local") {
        val chat = current() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val art = ArtifactStore.saveBytes(getApplication<Application>(), name, mime, bytes, origin)
            withContext(Dispatchers.Main) {
                chat.messages.add(Msg("assistant", "Generated " + art.name, artifacts = listOf(art)))
                chat.updatedAt = System.currentTimeMillis()
                save(); push()
            }
        }
    }

    /** Swap a stored artifact (same id) in place — used after a download. */
    private fun replaceArtifact(a: Artifact) {
        val chat = current() ?: return
        val idx = chat.messages.indexOfFirst { m -> m.artifacts.any { it.id == a.id } }
        if (idx < 0) return
        val m = chat.messages[idx]
        chat.messages[idx] = m.copy(artifacts = m.artifacts.map { if (it.id == a.id) a else it })
        save(); push()
    }

    /** Manual GPU layer count (also switches AUTO off). */
    fun setGpuLayers(layers: Int) {
        val v = layers.coerceIn(0, 200)
        // Any change to the GPU policy invalidates the prior verification so the
        // next load re-runs the isolated probe.
        prefs.edit().putInt(KEY_GPU_LAYERS, v).putBoolean(KEY_GPU_AUTO, false)
            .putBoolean(KEY_GPU_VERIFIED, false).apply()
        _state.update { it.copy(gpuLayers = v, gpuAuto = false) }
    }

    /** AUTO = offload all layers when a GPU backend is present, else CPU. */
    fun setGpuAuto(on: Boolean) {
        prefs.edit().putBoolean(KEY_GPU_AUTO, on).putBoolean(KEY_GPU_VERIFIED, false).apply()
        _state.update { it.copy(gpuAuto = on) }
    }

    fun stop() = LlamaBridge.cancel()

    override fun onCleared() {
        runCatching { LlamaBridge.free() }
        super.onCleared()
    }

    companion object {
        const val TAG = "Altair"
        private const val SYSTEM_PROMPT = "You are a helpful assistant running locally on the user's phone."
        private const val MAX_TOKENS = 512
        const val GPU_LAYERS_ALL = 99
        private const val THINK_OPEN = "\u003Cthink\u003E"
        private const val THINK_CLOSE = "\u003C/think\u003E"
        private const val NULL_TOKEN = "\u003C\u003E"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_GPU_LAYERS = "gpu_layers"
        private const val KEY_GPU_AUTO = "gpu_auto"
        private const val KEY_GPU_ATTEMPT = "gpu_attempt_inflight"
        private const val KEY_GPU_VERIFIED = "gpu_verified"
        private const val KEY_PROJ_VERIFIED = "projector_verified"
        private const val KEY_KV_QUANT = "kv_quant"
        private const val KEY_TOOLS_ENABLED = "tools_enabled"
        private const val KEY_LAST_MODEL = "last_model_path"
        private const val KEY_CURRENT = "current_chat"
        private const val KEY_THINKING = "thinking"
        private const val KEY_THINKING_MANUAL = "thinking_manual"
        private const val KEY_WEB = "web_search"
        private const val KEY_PROJ_OVERRIDE = "projector_overrides"
        private const val NULL_WORD = "null"
        private const val ROOTS = "model_roots"
    }
}