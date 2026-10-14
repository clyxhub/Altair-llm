# Local-First AI Hub — Build Plan
(working name: TBD — LobeHub-class app, lighter, local-first, agent-capable)

## 0. Prime directive: LIGHT · EFFICIENT · POWERFUL

Every feature must pass three gates before it ships:
1. **Light** — small install, small RAM, small disk. Concrete budgets:
   - Agent core + UI shell: < 30 MB installed (excl. models)
   - Engine idle RAM: < 80 MB with no model loaded
   - No framework that drags in > 20 MB of runtime; no Electron; no monolith server
   - UI is a compact PWA-grade web bundle served by the engine
2. **Efficient** — fast and frugal at runtime:
   - One resident model per device (mmap'd, auto-unload on idle timeout)
   - KV cache quantized (q8_0/q4_0) + flash attention; context auto-tuned to device RAM
   - Every network call, token, and GPU second attributable; background work zero by default
   - No wake-locks except during active generation / server mode
3. **Powerful** — lightness never amputates capability:
   - Full multimodal artifacts, MCP, multi-agent, mesh inference, agentic web — all present
   - Capability scales with the model's *actual profile*, never with the device

Anti-goal: never trade (1) or (2) for (3). If a feature can't fit, it ships as an opt-in lazy module or moves to a LAN/cloud peer.

## 1. Architecture — "One engine, many faces"

A single lightweight engine process serves everything; all clients hit the same API.

```
┌────────────────────────────────────────────────────────────┐
│                    CORE ENGINE (single process)             │
│  Inference: llama.cpp (GGUF · Vulkan GPU / CPU fallback)    │
│            → local OpenAI-compatible API                    │
│  Agent core: TypeScript on Bun (portable, embeddable)       │
│            → reasoning loop · memory · MCP · tools · agents │
│  Multimodal: whisper.cpp (STT) · Piper (TTS) · SD.cpp(img)  │
│  Storage: SQLite (+ sqlite-vec quantized embeddings)        │
│  Server: embedded HTTP/SSE — localhost + opt-in LAN + mDNS  │
├────────────────────────────────────────────────────────────┤
│  CLIENTS (all consume the same engine)                      │
│  • Web UI (compact React/Svelte PWA) — served by engine     │
│    → phone browser, desktop browser, every LAN device       │
│  • Android/iOS shell: thin WebView wrapper + native embed   │
│  • Cloud providers: OpenAI/Claude/Gemini/OpenRouter/etc.    │
└────────────────────────────────────────────────────────────┘
```

**Stack decisions**
- Inference: llama.cpp per-platform (Android NDK w/ Vulkan; Metal for iOS/mac; CUDA/Vulkan for PC)
- Agent core: TypeScript (Bun single-binary; embeddable runtime on Android)
- UI: lightweight React or Svelte + Vite static bundle (engine serves files + SSE, no framework server)
- DB: SQLite everywhere (sessions, memory index, downloads, audit logs)

## 2. Phase roadmap

### Phase 1 — Core chat engine + model management
- Chat UI: streaming, stop, regenerate, edit-and-resend, multi-chat (pin/rename/delete), folders, branches, search, export/import
- Provider layer: local GGUF + cloud presets (OpenAI, Anthropic, Gemini, Groq, Mistral, xAI, DeepSeek, OpenRouter) + custom OpenAI-compatible endpoints; unlimited keys per provider; cloud errors surfaced in-chat with HTTP status
- **Models screen** "+" menu: add from device (SAF/file picker) · from HuggingFace · from API provider
- **HuggingFace portal** (§4)
- **Auto-tuner**: per-(device, model) persisted config; topology heuristic on first load, refined by measured tok/s
- **PresetEngine**: chat-template auto-detect from GGUF metadata; auto performance defaults per family
- Full sampler panel (temp, top-p/k, min-p, typical, penalties, seed, mirostat, DRY, XTC)
- Thinking mode toggle + collapsible trace; token stats bar (tok/s, prompt/gen tokens, context fill)
- Markdown + code blocks; embedded live server (Android foreground service; localhost default, opt-in LAN, mDNS `appname.local:8080`, persisted port never zeroed)
- [CALISTO-adopted: auto-tuner, fit-grades, mDNS, PresetEngine, sampler panel, foreground service, error surfacing]

### Phase 2 — Artifacts & multimodal generation
- Artifact pane: HTML/JS live preview (testable in-app), Mermaid, SVG; view/interact/download any generated file; save to Downloads
- PDF generation; image generation (local SD.cpp where it fits, cloud otherwise)
- TTS output (Piper local / cloud); vision input (mmproj pairing — auto-scan same repo/folder, one-tap)
- Composer buttons generated per model capability (image/audio/file attach) from GGUF metadata & cloud profiles
- Media cache: capped, LRU-evicted, user-visible storage meter; artifacts regenerable from prompts

### Phase 3 — Hermes-style brain (memory + reasoning)
- Reasoning loop: plan → act → reflect (compact on small models, deeper multi-round on capable ones — driven by capability profile)
- Staged memory: working (current window) / episodic (session summaries) / long-term (extracted facts + procedures)
- SQLite + quantized vector index (int8/binary embeddings) for recall
- Background compaction: sessions crossing a threshold get older turns summarized + archived; raw text evicted, summaries kept
- Token budgeter (§5) runs before every request; degrades gracefully, never overflows

### Phase 4 — Tools, MCP, plugins
- Standard tool interface; built-ins: code execution sandbox, web fetch/browse, file workspace
- **MCP client**: connect external MCP servers (Claude Code / Operit-style ecosystems); no allowlist restrictions — safety via sandbox isolation, not blocking
- **Plugin format**: bundles may ship tools AND agent-role templates; target LobeHub plugin-manifest compatibility for ecosystem import
- **Agentic browser with human-in-the-loop** (§6)

### Phase 4.5 — Multi-agent orchestration & mesh inference
- Agent types as role templates (planner / orchestrator / builder / chat built-ins; user-definable)
- Each subagent: own small context (task brief + relevant memory snippets — never the orchestrator's transcript); outputs to shared session workspace; orchestrator receives condensed results
- Model policies per role resolve against the global capability registry ("any text model ≥32K ctx, cheapest first" / "exactly model X")
- **Mesh inference**: every engine on the LAN advertises its loaded models over mDNS → one unified pool (phone + PC + cloud) running in parallel across devices; single offline phone auto-escalates extra-modality work to cloud
- Compositor principle: models direct, deterministic tools assemble (ffmpeg/canvas for editing, compositing, effect specs)
- Example pipelines: video project (planner → scene-gen agents → sound agent → compositor → editor agent); comics (script writer → panel image gen → lettering → editor)

### Phase 5 — Voice
- whisper.cpp STT + Piper TTS (local), push-to-talk, hands-free voice mode; cloud STT/TTS as profile options

### Phase 6 — Multiplayer / team
- LAN server already multi-user; add identities, rooms, shared sessions, group chats with bots, per-user API-key isolation

### Phase 7 — Android polish & device automation
- Native embed (§3), background service, model library management (download/resume/evict, app-scoped storage, mmap)
- Operit-style device automation: intents, UI control via Accessibility Service
- GPU ladder: Vulkan (primary) → CPU NEON i8mm/dotprod (fallback) → QNN/NNAPI NPU (stretch)

## 3. Android native strategy (Termux demoted to dev/fallback)
- Termux can't reach vendor GPU drivers on many devices (SELinux/driver context) → CPU-only experience; not primary
- llama.cpp compiled with **Android NDK + JNI inside the APK**: app process is a legitimate GPU client → Vulkan works (Adreno/Mali/PowerVR)
- Models in app-scoped storage, mmap'd; SAF import supported but library-managed
- NDK/Vulkan verification checklist across GPU vendors per release

## 4. HuggingFace portal
- Native browse: HF model API search (filter=gguf), task/size/quant/license filters, optional HF token
- Model page: all quants + mmproj files listed with per-file sizes
- GREEN/YELLOW/RED fit grade vs real device RAM (model + mmproj + KV + headroom) — advisory, never blocks
- Download manager: start/pause/resume via HTTP Range, progress in SQLite, survives app kill/reboot; free-space check with floor warning
- Post-download: metadata auto-detect (family/context/capabilities) + mmproj auto-pair offer
- LAN bonus: big models downloaded on PC servable to weaker phones over the network

## 5. Memory & footprint discipline
- Storage: quantized model tiers (Q4_K_M default, smaller for budget devices), sizes pre-download, models deletable; non-model footprint single-digit MB; capped media caches
- RAM: one resident model, mmap, idle auto-unload, KV quant + flash-attn, n_ctx auto-tuned to device RAM (user-overridable)
- Context hierarchy fed to the model:
  `system + compact tools → retrieved memory snippets (top-k) → recent raw turns (sliding window) → older turns as summaries`
- Token budgeter measures every request; adapts to loaded model's n_ctx from GGUF metadata; mid-conversation model switches re-plan (downgrade → summarize older turns; upgrade → restore more)
- "Never blow the window" is enforced mechanically, not by prompt discipline

## 6. Agentic web with human-in-the-loop
- Backends: desktop = headless Chromium over CDP; Android = automation WebView + Accessibility Service
- Live view: streaming compressed screenshots (~1–2 fps idle), URL + action log panel; Android renders the real WebView as an expandable card; LAN devices can watch any engine's session
- Takeover: pause mid-task → browsing surface becomes user-interactive → Resume (agent re-observes page state, continues plan); interrupt/redirect/cancel anytime
- Confirmation gates (user-configured): payments, purchases, messages, large downloads, unknown domains
- Security: agent never types credentials (login/2FA always handed off); isolated browser profile, no silent inheritance of personal sessions; cookies in system keystore, encrypted, per-task wipeable
- Full action audit trail; procedures replayable and promotable to long-term memory

## 7. Capability profiles & routing
- Every model (local or cloud) gets a profile: context, modalities, tool-calling, max output, cost, speed
- Profiles declared for cloud APIs; derived (metadata + auto-tuner measurements) for local GGUF; LAN peers publish theirs; user-editable
- All subsystems read profiles: budgeter, retrieval depth, tool-schema compression, reasoning depth, composer buttons
- **Auto router**: "auto" mode runs tasks on the cheapest capable tier — local when it fits (free, private, offline), cloud when exceeding local limits; LAN PC models slot in as middle tier
- Principle: restrictions attach to the model's actual capability, never to "local vs cloud" as a category

## 8. Risks / realism notes
- Local video generation: not feasible on phones → cloud APIs only (agreed)
- NPU (QNN) support is vendor-specific → stretch goal, never a dependency
- iOS: no foreground-service parity for side-loaded servers; web UI + Metal inference later
- MCP/plugin ecosystems evolve fast → keep the tool interface thin and versioned

## 9. Explicitly out of scope (protects Light/Efficient)
- Electron or heavy framework shells
- Bundling models in the installer
- Cloud dependency for any core local feature (offline chat, local memory, local STT/TTS work airplane-mode)
- Monolithic per-feature services; optional capabilities load lazily or live on a LAN/cloud peer

## 10. Build & release — GitHub Actions CI (all compilation off-device)
- All native compilation (NDK/APK, llama.cpp per-platform libs, desktop binaries) happens in GitHub Actions, never on the user's phone or this machine
- Workflow (`.github/workflows/build.yml`), triggered on push to main + manual dispatch:
  1. Ubuntu runner; JDK 17 + Android SDK + NDK setup (cached)
  2. Build llama.cpp for Android (arm64-v8a) with NDK toolchain — Vulkan backend enabled; cache ccache
  3. Build agent core (Bun/TS bundle) + web UI (Vite static bundle)
  4. Gradle assembleDebug APK (default); assembleRelease when a signing keystore secret is present
  5. Upload APK + artifacts to the run; optional: auto-attach APK to GitHub Releases on version tags
- Later additions (same pattern): desktop builds (Windows/Linux/macOS), iOS (needs a Mac runner / paid Apple dev account)
- Repo setup checklist for the user:
  - Create the GitHub repo and share the link
  - Provide a fine-grained PAT (or add me as a collaborator) so I can commit/push code + workflow
  - Optional secrets: `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD` + `KEY_ALIAS` for signed release builds (unsigned/debug APK works first)
  - No API keys belong in CI — BYOK keys live only on the user's device at runtime
- Iteration loop: user installs each APK artifact on the phone, reports issues here → fixes committed → CI rebuilds. No phone-side tooling ever required.
