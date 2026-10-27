# PocketForge AI

**A private, on-device AI assistant for Android. Local inference (llama.cpp + Vulkan), encrypted chat storage, panic wipe — nothing ever leaves the phone.**

<p align="center">
  <a href="#whats-new-in-v020">What's New</a> ·
  <a href="#privacy--security">Privacy</a> ·
  <a href="#models">Models</a> ·
  <a href="#building">Building</a> ·
  <a href="#apk">APK</a> ·
  <a href="#architecture">Architecture</a>
</p>

---

## What's New in v0.4.0

> **Release:** `PocketForgeAI-v0.4.0-arm64-v8a.apk` (versionCode 4) + `PocketForgeDesktop-win-x64.zip`
> **Note:** compiled and packaged builds; on-device behaviour pending manual hardware testing

### Turbo mode (distributed speculative decoding)

Optional PC-assisted generation. The desktop drafts candidate tokens with a
local small model (Qwen3.5-0.8B, same qwen35 tokenizer family); the phone
verifies them greedily against the loaded big model and stays the authority for
the output. Rejected candidates are rolled back so the session stays exact.
Desktop toggle, capability discovery, id-level tokenizer compatibility check
(tokenization probe, not just vocab size), and automatic fallback to Normal on
any unavailability. Greedy-only by design; real-device speed is the user's to
measure.

### Reliability and quality

- Context-shift fix: long sessions no longer lose the system prompt; history is
  dropped and re-primed instead of silently degrading.
- KV cache defaults to q8_0 for K and V (large memory saving at near-lossless
  quality for most models).
- Turn queue on the phone server (depth 8, 30 s timeout, agent priority) with
  honest 429 responses; the desktop retries queue-aware. Desktop reconnect bug
  fixed (button no longer stays disabled after a failed connect).
- In-app dark mode toggle (follows system by default).
- Steering UI removed from the app; the shipped native build carries no steering
  code (third_party tree is stock llama.cpp at the pinned revision).


## 🚀 What's New in v0.2.0

> 📦 **Release:** `PocketForgeAI-v0.2.0-arm64-v8a.apk` (versionCode 2) — 30.3 MB, arm64-v8a only
> 🔏 **SHA-256:** `29d3f4ec59b091f096ca9a43edb58113213128310d4273e68597297fced1e6ae`
> 🧪 **Verified:** Kotlin + native builds, packaged-lib inspection — on-device behaviour pending hardware testing

### 🧠 Neuron steering (Qwen3.5 dense models)

Activation steering compiled into the llama.cpp core: at generation time the
app amplifies or suppresses specific "refusal-direction" neurons inside the
MLP down-projection of chosen transformer layers, to remove refusals and
boost instruction-following without touching model weights.

- ⚙️ **Spec format** `L:N:dir[:dose][,...]` — e.g. the default `14:8949:+1,13:7956:-1` (layer:neuron:direction, dose optional, auto = scaled by the neuron's output norm)
- 🎯 **Targets:** layer 14 neuron 8949 (amplified) + layer 13 neuron 7956 (suppressed) — the default is tuned for abliterated Qwen3.5-4B/9B
- 🔍 **Per-model compatibility check** before load: architecture (`qwen35` dense only), trunk layer range and per-neuron tensor dims verified against the GGUF header — MoE and other architectures are flagged unsupported
- 🕹️ **UI:** Settings card ("Neuron steering") with a global switch + editable spec, plus a per-model steering switch in the Models load dialog
- 📡 **Runtime status:** the Performance/plan surface reports whether steering is active and which spec was applied (e.g. `steer=14:8949:+1,13:7956:-1` in the load log)
- ♻️ **Reload to apply:** changing the spec requires a model reload; the KV cache is never persisted, so state can't leak between loads
- ⚠️ **Behavioural effect unverified:** compatibility is verified statically; A/B effect testing on hardware is still pending

### 🌐 Model Hub (live Hugging Face search)

A fifth tab that searches Hugging Face directly and downloads GGUF models —
no more waiting for catalogue updates:

- 🔎 **Debounced live search** of the HF Hub (400 ms, GGUF-tagged repos, load-more pagination)
- 📄 **Expandable repo detail:** license, GGUF architecture, context length, per-file size + SHA-256, direct repo link
- 🧠 **Steering-aware badges:** each GGUF file is pre-checked against the steering spec and labelled supported / unsupported / unknown
- 🛡️ **Safety rails:** repo-ID validation, gated/private repos filtered out, multi-part shards excluded, HTTPS + revision-pinned download URLs, downloads only on explicit taps (no auto-fetch)
- 🔁 **Reuses the hardened download pipeline:** resume, SHA-256 verification against `lfs.oid`, storage pre-checks (file + 5% + 256 MiB headroom), foreground notification, cancel/retry — and installs into the same registry as catalogue models

### ⚡ Performance & engine work

- 🚀 **CPU-variant pinning + lazy Vulkan dlopen** (landed with v0.2.0 builds): 0.9 → **12.3 tok/s** decode on the reference device (Snapdragon 8 Elite-class, 4B Q4_K_M) — the app no longer pays Vulkan-probe costs when running CPU-only
- 🧵 **Thread fallback fix:** auto thread count now matches the native rule (`min(6, cores-2)`)
- 🧮 **O(1) token accumulation:** per-token string concatenation replaced with a StringBuilder (was quadratic over long replies)
- 💾 **Performance tab lightened:** 1 Hz tick no longer re-parses GGUF headers, re-reads `/proc` or blocks the engine lane on the main thread (cached per loaded model, device facts every 5 s, loop moved off the main thread)
- 🗜️ **Chat I/O off the main thread:** conversation saves and the periodic decrypt-refresh no longer run on Main during generation
- 🧹 **Optimized native libs:** llama.cpp rebuilt for `arm64-v8a` with **KleidiAI** kernels (armv8.0 → armv9.2+SME, 7 variants), **static OpenMP** (zero `libomp.so` dependency), GGML backend DL loading, release optimizations
- 🐞 **Critical JNI fix:** `pfSetPlan` null-contract corrected (a latent bug that would have failed every model load)

### ⚠️ Known limitations (this release)

- 🔬 On-device behaviour (steering effect, Hub downloads, decode tps) is **not yet hardware-verified** — static checks + API verification only
- 🎛️ Steering covers **qwen35 dense** models only (MoE/CLEF excluded by design); draft/MTP decoding is not steered
- 🖥️ Vulkan remains built but off by default (lazy-load probe first); desktop Vulkan builds blocked on missing SPIRV-Headers in the build env
- 📡 Hub pagination uses limit-growth + dedup (HF `offset` param is not honored); `lfs.oid` treated as SHA-256 (verified for sampled files)

### 📚 Full release notes

- 📋 Ship-readiness audit: [`docs/android-steering-performance-ship-audit.md`](docs/android-steering-performance-ship-audit.md)
- 🛠️ Implementation report (agents, build matrix, verification): [`docs/android-steering-performance-implementation.md`](docs/android-steering-performance-implementation.md)

---

## Why

Cloud AI assistants permanently log your conversations. PocketForge runs the model on *your* hardware — the GPU in someone's data center never sees a word you type. No network permission is required for inference; chats are stored encrypted at rest with a key that never leaves the Android Keystore, and a panic wipe performs a true **crypto-erase** (key deletion) instead of a slow forensic overwrite.

---

## Features

- **100% local inference** — llama.cpp compiled for `arm64-v8a`, running on **Vulkan** with CPU fallback.
- **Neuron steering** 🧠 — per-model activation steering for abliterated Qwen3.5 (spec-validated before load; see [What's New](#whats-new-in-v020)).
- **Encrypted chat storage** — every conversation is AES-256-GCM encrypted (`PFC1` file format: magic + 12-byte IV + GCM body) with a per-install file key wrapped by an Android Keystore alias (`pf_chat_key_v1`, StrongBox → TEE → software fallback).
- **Panic wipe** — hold-to-confirm button in Settings that: stops generation, unloads the model, **deletes the Keystore key first** (instant crypto-erase of everything), then removes chat files, persona blobs, wrapped key and chat-related settings, then restarts to first-run state. Optional extras: also delete model files, add a launcher shortcut.
- **Legacy migration** — older plaintext chats are re-encrypted automatically on first startup, with checksum verification before plaintext deletion.
- **Recovery, not corruption** — if the key and the stored blob ever disagree (failed restore, key corruption), the app flags the chat store as *unreadable* and shows a reset dialog. It never silently overwrites data.
- **Model catalogue with pinned revisions** — every featured model is pinned to a specific git revision SHA and verified by SHA-256, so you always get exactly the weights tested here.
- **Model Hub** 🌐 — search Hugging Face live and download any GGUF through the verified, resumable download pipeline (see [What's New](#whats-new-in-v020)).
- **Memory panel** — the Performance tab shows self/process memory in BASIC mode, and system-wide detail via Shizuku (FULL mode).
- **Screenshot protection** — `FLAG_SECURE` on the chat window by default (blocks screenshots and the recents thumbnail), toggleable in Settings.
- **No cloud backup** — `allowBackup=false` plus `dataExtractionRules` excludes chats, key material and model files from cloud backups, device transfer and D2D restore.

## Models

9 curated **abliterated** (system-prompt-refusal removed) open-weight models, all verified and pinned:

| ID | Model | Size |
|----|-------|------|
| `abl-llama32-1b-q3km` | Llama-3.2-1B-Instruct-abliterated (Q3_K_M via tensorblock) | ~0.8 GB |
| `abl-qwen3-1.7b-q4km` | Qwen3-1.7B [ElementMerc, Uncensored] (Q4_K_M) | ~1.1 GB |
| `abl-qwen25-coder-3b-q4km` | Qwen2.5-Coder-3B-Instruct-abliterated (Q4_K_M) | ~1.9 GB |
| `abl-qwen3-4b-2507-q4km` | Qwen3-4B-Instruct-2507-abliterated (Q4_K_M) | ~2.5 GB |
| `abl-qwen35-4b-q4km` | Qwen3.5-4B [HauhauCS, Aggressive] (Q4_K_M) | ~2.7 GB |
| `abl-gemma4-e2b-q4kp` | Gemma-4-E2B [HauhauCS] (Q4_K_P) | ~2.4 GB |
| `abl-gemma3n-e4b-q4km` | Gemma 3n E4B Instruct-abliterated (Q4_K_M) | ~4.2 GB |
| `abl-gemma4-e4b-q4kp` | Gemma-4-E4B [HauhauCS] (Q4_K_P) | ~4.8 GB |
| `abl-qwen35-9b-q4km` | Qwen3.5-9B [HauhauCS] (Q4_K_M) | ~5.5 GB |

Toast the **Models** tab to search, download (pinned revision) or import your own GGUF. Sizes scale on the "fits this device" badge using available RAM, and candidates are ranked by a runtime-aware memory estimator.

## Privacy & Security

**Threat model:** protects chat data at rest — phone lost / imaged / app data extracted while the app is closed, and keeps chats out of any ADB, cloud or vendor backup.

**Not protected against:** root malware while the app is running, or someone using the unlocked phone with the app open.

Details:

- Chat files: AES-256-GCM (`ChatCrypto.kt`), file key wrapped by a Keystore-backed AES key (`pf_chat_key_v1`).
- Persona / system prompts: stored separately, encrypted (`PersonaStore.kt`).
- Wipe order in `PanicWipe.kt`: cancel engine → unload → **Keystore `deleteEntry` FIRST** → chats → persona → key blob → chat-related settings → caches → app restart. Crypto-erase is the guarantee, not forensic overwrite.
- Grepped post-install for any chat-related plaintext across `files/`, `databases/`, `shared_prefs/`, `cache/`, `code_cache/`, `no_backup/` (verified: zero hits).

## Building

```powershell
git clone https://github.com/JithinKrishna-Off/PocketForgeAi.git
cd PocketForgeAi
git submodule update --init --recursive    # pulls third_party/llama.cpp
.\gradlew :app:assembleDebug
```

- Android Studio Koala+ / AGP 8.x, compileSdk 35, **arm64-v8a only** (abiFilters in `app/build.gradle.kts`).
- `useLegacyPackaging` is set for JNI libs — llama.cpp discovers CPU-variant backends via `dlopen` on extracted files first.
- See third_party llama.cpp submodule for the BSD/MIT-style licence of the inference core.

## APK

Pre-built debug APK, v0.3.0 (versionCode 3), arm64-v8a: **`artifacts/PocketForgeAI-v0.3.0-server-arm64-v8a.apk`** — install directly:

```powershell
adb install -r -t artifacts\PocketForgeAI-v0.3.0-server-arm64-v8a.apk
```

| 📦 Artifact | 📏 Size | 🔏 SHA-256 |
|---|---|---|
| `PocketForgeAI-v0.3.0-server-arm64-v8a.apk` | 28,020,490 B | `52e509e701539329015b8fa780a493683432e3e8936da58a5e156dfa1b9d5a38` |
| `desktop/PocketForgeDesktop-win-x64.zip` (Windows client + MCP tools) | 90,889,113 B | `b44f71884aed37e101748d42b734d29c5f4867b3ad11854d7793afa1a6d2cffb` |

Verify before install:

```powershell
Get-FileHash artifacts\PocketForgeAI-v0.3.0-server-arm64-v8a.apk -Algorithm SHA256
```

(Also mirrored locally under `releases/` with its `.sha256` sidecar; `artifacts/` is the git-tracked copy. Full manifest: [`releases/manifest.md`](releases/manifest.md).)

## 🖥️ Desktop client + MCP (v0.3.0)

`desktop/` holds the Windows companion: a lightweight WPF chat client that connects
to the phone's **Desktop server** (Settings → Desktop server; pairing code +
bearer token), streams responses over SSE, and runs a laptop-side **MCP tool
host** (search_files, read_file, list_dir, git_status, approval-gated run_command
behind a workspace-roots + secret-deny security layer).

```powershell
cd desktop
dotnet build app/PocketForgeDesktop.csproj     # WPF client
dotnet build McpTools/McpTools.csproj          # MCP tool server (spawned by the app)
dotnet run --project McpTools                  # -- selftest (35 checks)
powershell -File smoke-test.ps1                # contract tests vs mock server
powershell -File build.ps1                     # single-file publish + zip
```

- 📶 Connect over Wi-Fi (phone IP + port 8751) or USB (`adb reverse tcp:8751 tcp:8751`).
- 🔐 Pair with the 6-digit code shown in the phone's server settings; the token is
  stored DPAPI-protected; revoke any time on the phone.
- 🛠️ Protocol + security model: [`desktop/docs/mcp-tools.md`](desktop/docs/mcp-tools.md),
  [`docs/pocketforge-api-v1.md`](docs/pocketforge-api-v1.md).

## Architecture

```
app/src/main/java/com/pocketforge/ai/
  ├── PocketForgeApp.kt          — app init: crypto ensure + legacy chat migration
  ├── MainActivity.kt            — nav root, FLAG_SECURE wiring
  ├── crypto/
  │   ├── ChatCrypto.kt          — AES-256-GCM file crypto, Keystore-wrapped DEK, KeyStatus
  │   └── PanicWipe.kt           — ordered crypto-erase wipe
  ├── chat/ChatStore.kt          — encrypted chat persistence + migration + unreadable flow
  ├── settings/
  │   ├── SettingsStore.kt       — DataStore prefs (chat-related keys cleared on wipe)
  │   └── PersonaStore.kt        — encrypted persona store
  ├── data/
  │   ├── catalogue/CatalogRepository.kt   — pinned model catalogue
  │   ├── hub/HfCatalogService.kt          — live Hugging Face search + GGUF file listing (v0.2.0)
  │   ├── steering/SteeringCompatibility.kt — shared steering spec validation + GGUF assessment (v0.2.0)
  │   ├── gguf/                  — GGUF parser + memory estimator
  │   └── memory/                — performance self/system memory (Shizuku FULL mode)
  └── ui/screens/                — Chat, Models, Hub, Performance, Settings (incl. PrivacyCard)
```

- Inference: `app/src/main/cpp/pf_llm.cpp` (JNI bridge → llama.cpp), backend init at `lib/arm64`, Vulkan on Mali-G720-class devices.
- Engine state (session KV-cache) is **never persisted** to disk — the KV cache lives in memory only.

## Status / Known issues

- [ ] Deterministic end-of-generation stamping with native-side post-processing.
- [ ] Models screen sometimes shows "Download" instead of "Load" for already-staged smoke models (registry load diagnostics logged under tag `PFLlamaJNI`).
- [ ] Shizuku-based system-wide memory panel needs manual Shizuku grant on device to go past BASIC mode.
- [ ] Library packaging still ships all llama.cpp CPU variants (`armv9.0/9.2` logs "not supported" on load then falls back to `armv8.6` — harmless).

## Licence

App code: MIT. llama.cpp: see upstream (MIT). Model weights: each retains its original upstream license (see `app/src/main/assets/model_catalogue.json` for per-model `license` field).

- **Source**: https://github.com/JithinKrishna-Off/PocketForgeAi
- **Contact**: Jithin Krishna — via GitHub `@JithinKrishna-Off`
