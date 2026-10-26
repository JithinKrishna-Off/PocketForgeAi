# Integration baseline (measured 2026-10-26)

## Android (C:\Users\ASUS\Desktop\PocketForgeAI)
- Branch: research/breakthrough; remote: https://github.com/JithinKrishna-Off/PocketForgeAi.git
- Version: versionName 0.3.0, versionCode 3 (bumped for this release by the lead)
- gradlew.bat present; JDK/AGP unchanged. `gradlew assembleDebug`: BUILD SUCCESSFUL
  (41 s) with the full server-mode change set compiled in.
- adb devices: NO device attached (runtime verification on hardware remains open).
- jniLibs arm64-v8a: 20 .so files staged (11 steering-tree libs incl. libllama.so
  45,736,264 B, libggml-vulkan.so, libmtmd.so, 6 tool impl libs, libpocketforge-llm.so
  1,636,416 B rebuilt with the steering plan field).
- Prior artifacts in artifacts/: v0.2.0 APK + sha256 (preserved, not touched).

## Desktop (created this cycle, being moved to PocketForgeAI/desktop)
- .NET SDK 10.0.201; runtimes: NETCore.App 10.0.5, WindowsDesktop.App 10.0.5, AspNetCore 10.0.5.
- Node v24.20.0, Python 3.13.6 (unused by the shipped stack).
- Projects: app (WPF net10.0-windows), McpTools (console, MCP stdio server+client),
  tools/MockPhone (contract mock), tools/SelfTest (contract + e2e tests).

## Test status at baseline
- Android: gradle assembleDebug PASS (compile-level; no device tests).
- Desktop: solution build PASS; SelfTest 29/29 PASS; McpTools selftest 35/35 PASS.

## Environment blockers
- No physical Android device -> pairing, streaming, steering and hub behaviour on
  hardware are unverified this cycle.
- No Vulkan SDK -> desktop llama Vulkan builds remain blocked (out of scope here).
