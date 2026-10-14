# Altair

Local-first AI hub — one engine, many faces. **Light · efficient · powerful.**

The full specification lives in [`docs/BUILD-PLAN.md`](docs/BUILD-PLAN.md) and is the
source of truth for this project.

## Status

The Android app (the phone face) is built on the proven on-device llama.cpp
integration ported from `clyxhub/Hermes-llama-android` — native Kotlin/Compose,
in-process GGUF + mmproj inference via JNI/NDK, Vulkan with CPU fallback, HF
model portal, embedded OpenAI-compatible server + mDNS.

The remaining Altair-plan systems — capability registry + token budgeter,
staged memory/retrieval, MCP/plugins, multi-agent orchestration + mesh
inference, agentic web, multiplayer, and device automation — are layered onto
this base per the plan's phase order.

## Repository layout

```
android/    Native Android app (Kotlin/Compose + JNI). The on-device "face".
engine/     Agent core (TypeScript) — OpenAI-compatible runtime (LAN/desktop face)
web/        Web UI (React) — served to LAN/desktop clients
docs/       Build plan (source of truth) & roadmap
.github/    CI — GitHub Actions builds the APK off-device
scripts/    Build helpers (Vulkan patches)
```

## Build

All native compilation happens in GitHub Actions (`.github/workflows/build.yml`):
it fetches llama.cpp at a pinned revision, then Gradle builds the release APK.

```sh
cd android
./gradlew assembleRelease      # requires NDK 26.1, CMake 3.22.1, glslc, llama.cpp in android/third_party
```

The engine and web UI run anywhere Node does:

```sh
cd engine && npm install && npm run build && npm start
cd web && npm install && npm run dev
```
