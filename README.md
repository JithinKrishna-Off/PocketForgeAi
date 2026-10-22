# PocketForge AI

**A private, on-device AI assistant for Android. Local inference (llama.cpp + Vulkan), encrypted chat storage, panic wipe — nothing ever leaves the phone.**

<p align="center">
  <a href="#privacy--security">Privacy</a> ·
  <a href="#models">Models</a> ·
  <a href="#building">Building</a> ·
  <a href="#apk">APK</a> ·
  <a href="#architecture">Architecture</a>
</p>

---

## Why

Cloud AI assistants permanently log your conversations. PocketForge runs the model on *your* hardware — the GPU in someone's data center never sees a word you type. No network permission is required for inference; chats are stored encrypted at rest with a key that never leaves the Android Keystore, and a panic wipe performs a true **crypto-erase** (key deletion) instead of a slow forensic overwrite.

---

## Features

- **100% local inference** — llama.cpp compiled for `arm64-v8a`, running on **Vulkan** with CPU fallback.
- **Encrypted chat storage** — every conversation is AES-256-GCM encrypted (`PFC1` file format: magic + 12-byte IV + GCM body) with a per-install file key wrapped by an Android Keystore alias (`pf_chat_key_v1`, StrongBox → TEE → software fallback).
- **Panic wipe** — hold-to-confirm button in Settings that: stops generation, unloads the model, **deletes the Keystore key first** (instant crypto-erase of everything), then removes chat files, persona blobs, wrapped key and chat-related settings, then restarts to first-run state. Optional extras: also delete model files, add a launcher shortcut.
- **Legacy migration** — older plaintext chats are re-encrypted automatically on first startup, with checksum verification before plaintext deletion.
- **Recovery, not corruption** — if the key and the stored blob ever disagree (failed restore, key corruption), the app flags the chat store as *unreadable* and shows a reset dialog. It never silently overwrites data.
- **Model catalogue with pinned revisions** — every featured model is pinned to a specific git revision SHA and verified by SHA-256, so you always get exactly the weights tested here.
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

Pre-built debug APK (v2, versionCode 2): **`artifacts/PocketForgeAI-debug.apk`** — install directly:

```powershell
adb install -r -t artifacts\PocketForgeAI-debug.apk
```

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
  │   ├── gguf/                  — GGUF parser + memory estimator
  │   └── memory/                — performance self/system memory (Shizuku FULL mode)
  └── ui/screens/                — Chat, Models, Performance, Settings (incl. PrivacyCard)
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
