# Altair LLM

Local-first AI hub — one lightweight engine, many faces.

**Prime directive: LIGHT · EFFICIENT · POWERFUL**

- 🪶 **Light**: < 30 MB app footprint (excl. models), < 80 MB idle RAM
- ⚡ **Efficient**: one resident model, mmap weights, quantized KV cache, zero background work
- 💪 **Powerful**: multimodal artifacts, MCP, multi-agent mesh inference, agentic web — capability scales with the model's *actual profile*, never with the device

## Repository layout

```
engine/     Agent core (TypeScript) — OpenAI-compatible API server + agent runtime
web/        Web UI (React + Vite) — served by the engine; works on phone, desktop, LAN
android/    Android shell (WebView + JNI; llama.cpp NDK integration lands next)
docs/       Build plan & roadmap
.github/    CI — GitHub Actions builds the APK off-device
```

## Status

Phase 0 — skeleton + CI pipeline. See `docs/BUILD-PLAN.md` for the full roadmap.

## Quick start (desktop)

```sh
# terminal 1 — engine
cd engine && npm install && npm run dev

# terminal 2 — web UI (dev mode, proxies /v1 to engine)
cd web && npm install && npm run dev
```

Or build the web bundle once and let the engine serve it:

```sh
cd web && npm install && npm run build
cp -r dist ../android/app/src/main/assets/www/   # optional: bundle into APK
cd ../engine && npm install && npm run build && npm start
# → http://localhost:8787
```

The engine exposes an OpenAI-compatible API (`/v1/chat/completions` with SSE streaming),
so any OpenAI-compatible client can point at `http://<device>:8787/v1`.
