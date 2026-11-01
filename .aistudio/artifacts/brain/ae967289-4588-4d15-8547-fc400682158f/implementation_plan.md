# OpenMinis Sub-70MB Lossless APK Optimization Plan

This plan surgically reduces the OpenMinis APK size from **79.93 MB** (**72.93 MB** repacked) down to **~58–60 MB** (well below the 70 MB ceiling in both decimal MB and binary MiB) without deleting or trimming any application feature, bundled asset, or CPU architecture.

### User Review & Critical Decisions

> [!IMPORTANT]
> Both scope decisions were confirmed in our clarification step and govern this surgical optimization pass:

- **Confirmed Decision 1 — Lossless DEX Merge, Deflate-9, and Unused Material Icon Pruning (~58–60 MB Target)**:
  - Merge the 20 fragmented debug `.dex` files into canonical merged DEX files via `d8 --release --min-api 26` to eliminate 20x duplicated string, type, and method descriptor tables and strip redundant local-variable register debug tables.
  - Prune only the **12,157 unreferenced Material Icon classes** (`androidx.compose.material.icons.**` that are never referenced by the app, keeping all **237 used Material Icons** and 100% of OpenMinis application classes, reflection targets, and libraries untouched).
  - Preserve pre-compressed `.tar.gz` streams in `aapt2` (`noCompress += "gz"`) so `aapt2` does not gunzip and re-inflate the Alpine rootfs archives during packaging.
  - Repackage sequentially from byte offset `0` using maximum-effort `zlib` Deflate (`level=9, memLevel=9`) with zero `zipflinger` virtual header holes and strict 4-byte / 4KB alignment.
- **Confirmed Decision 2 — Keep All ABIs and All Bundled Assets Untouched**:
  - Retain all CPU architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), all three Alpine rootfs archives, both Silero VAD ONNX models, the Jieba dictionaries, KaTeX, and `models-dev-api.json` with zero functional or asset removal.

---

### 1. Overview & Core Concept

- **What It Does**: Eliminates purely structural build and packaging bloat inside the APK container—specifically 12,157 unreferenced Jetpack Compose Material Icon vector factory classes (~10.2 MB compressed / ~40 MB raw DEX), 20-way multidex string-table duplication (~3.4 MB compressed), `aapt2` `.tar.gz` gunzipping expansion (~0.68 MB), and `zipflinger` incremental dead-space holes (~7.0 MB)—while keeping 100% of the app's runtime code, assets, and multi-arch native binaries intact.
- **Target Audience / Persona**: Power users and developers installing OpenMinis via direct APK download (`temp.sh`) or Shizuku-Next / PackageInstaller who need a fast, compact (<60 MB), strictly compliant APK that passes `libziparchive` (`unzip -v`) and `apksigner` v2/v3 verification on Android 8–16.
- **Key Value**: Shrinks download and installation footprint by **~20 MB (~25% reduction)** compared to the raw incremental APK (`79.93 MB` → `~59 MB`), eliminates `libziparchive` `Inconsistent information` errors, and accelerates on-device `dex2oat` compilation during installation because ART no longer compiles 12,157 unused icon classes.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Direct APK Installation**: When the user downloads the APK from `temp.sh` and opens it in Shizuku-Next ("App installer") or Android's system package installer, `unzip -v` completes in <40 ms with zero header warnings, and `pm install` compiles 5 compact DEX files instead of 20 bloated DEX files.
  2. **Runtime UI & Iconography Parity**: Every screen, animated tool call card, `spawn_subagent` multi-worker swarm view, Tampermonkey browser sheet, and Material icon renders identically because all 237 referenced Material Icons remain compiled in the DEX payload.
  3. **Sandbox & Voice Activity Detection**: First-boot Alpine Linux rootfs extraction (`GlobalShellThrottle`) seamlessly detects `.tar.gz` or `.tar` assets across both `aarch64` and `x86_64` devices.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Preserves OpenMinis's high-craft dark/dynamic Material 3 aesthetic, spring-animated tool cards, and multi-worker swarm status pills without visual changes or regressions.
  - *Iconography & Assets*: All 237 filled, outlined, rounded, and auto-mirrored Material Symbols used across chat, browser, terminal, settings, and swarm sheets remain byte-for-byte identical at runtime.
- **Interactive Feedback & Motion**: Faster app launch and lower memory footprint during cold start due to reducing raw uncompressed DEX size from **96.80 MB** (20 DEX files) down to **~52 MB** (4–5 merged DEX files).

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Surgical Pruning of Only Unreferenced `androidx.compose.material.icons.**` Classes (-10.2 MB Compressed / -40.0 MB Raw DEX)**
  - *Chosen Approach*: Filter out only unreferenced icon vector classes under `androidx/compose/material/icons/` (keeping all 237 icon classes referenced by OpenMinis plus their helper classes) prior to `d8 --release` DEX merging.
  - *Why*: `androidx.compose.material:material-icons-extended` is notorious in Android builds for bundling 12,394 individual Kotlin classes—one per icon per style—consuming 41.18 MB of raw DEX (`10.59 MB` compressed in `classes.dex`). Because Material icons are accessed via direct static extension properties (never by dynamic string reflection), omitting the 12,157 unreferenced icon classes is 100% safe and keeps every single class in `com.openminis.**` and all other libraries completely untouched.
  - *Alternatives Considered*: Full R8 whole-program shrinking (`isMinifyEnabled = true`), which can require maintaining reflection keep rules for kotlinx.serialization, Room, Shizuku, and Retrofit/OkHttp; surgical icon class filtering + `d8 --release` achieves the sub-60 MB goal with zero risk of stripping reflective app classes.
- **Decision 2: Canonical `d8 --release` Multidex Consolidation (-3.4 MB Compressed)**
  - *Chosen Approach*: Merge all DEX files with `d8 --release --min-api 26` into the minimal number of DEX files (4–5 files instead of 20).
  - *Why*: Eliminates 20x cross-DEX duplication of common JVM/Kotlin/Compose string constants (`0x2002` `string_data_item`) and strips redundant local-variable register debug tables (`0x2003` `debug_info_item`, 12.67 MB raw) while preserving full stack-trace line numbers and 100% of classes and methods.
- **Decision 3: Prevent `aapt2` from Gunzipping `.tar.gz` Assets & Apply Max-Effort Deflate-9**
  - *Chosen Approach*: Add `"gz"` to `androidResources.noCompress` so `aapt2` stops decompressing `alpine-minirootfs-*.tar.gz` into `.tar` during asset merging, and repackage all compressible APK entries with `zlib(level=9, wbits=-15, memLevel=9)` in a single monotonic pass.
  - *Why*: Saves ~0.68 MB on the rootfs archives and ~7.16 MB across the archive compared to incremental `zipflinger` output, while guaranteeing 100% consistency between Local File Headers and the Central Directory.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & APK Transformation Pipeline**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Current Incremental APK (79.93 MB)                       │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌───────────────────┐  │
│  │ 20 Fragmented DEXs   │  │ All 4 ABI Native Libs│  │ Assets & Resources│  │
│  │ 31.10 MB (96.8MB raw)│  │ 23.70 MB (57.1MB raw)│  │ 19.60 MB          │  │
│  │ + 12,157 unused icons│  │ arm64/v7a/x86_64/x86 │  │ + 7.0MB zip holes │  │
│  └──────────┬───────────┘  └──────────┬───────────┘  └─────────┬─────────┘  │
└─────────────┼─────────────────────────┼────────────────────────┼────────────┘
              │                         │                        │
              ▼                         │                        ▼
┌─────────────────────────┐             │          ┌──────────────────────────┐
│ 1. Surgical Icon Filter │             │          │ 2. Asset Stream Guard    │
│ Keep all 237 used icons │             │          │ Preserve .tar.gz streams │
│ + 100% of app & libs;   │             │          │ (noCompress += "gz")     │
│ drop 12,157 unused icons│             │          │ & strip zipflinger holes │
└─────────────┬───────────┘             │          └─────────────┬────────────┘
              │                         │                        │
              ▼                         │                        │
┌─────────────────────────┐             │                        │
│ 3. D8 Release Merger    │             │                        │
│ d8 --release --min-api  │             │                        │
│ 26 consolidates 20 DEXs │             │                        │
│ -> 4 DEXs (~17.3 MB)    │             │                        │
└─────────────┬───────────┘             │                        │
              │                         │                        │
              └─────────────────────────┼────────────────────────┘
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│         4. Canonical Sequential Packager (Deflate-9 + 4KB Alignment)        │
│  • Compressible entries: zlib(level=9, wbits=-15, memLevel=9)               │
│  • Stored entries (resources.arsc, .tar.gz): 4-byte / 4KB aligned (0xD935)  │
│  • 0 virtual LFH holes, 0 byte gaps, 100% monotonic LFH offsets             │
│  • Signed with apksigner (v2 + v3 schemes) & verified via libziparchive     │
├─────────────────────────────────────────────────────────────────────────────┤
│                  Final Verified APK: ~58.5 MB (< 60 MB)                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

- **Size Accounting Breakdown (Before vs. After)**:
  - **DEX Bytecode**: `31.10 MB` → **`~17.30 MB`** (`-13.80 MB` via unused Material icon pruning + `d8 --release` string-table consolidation + Deflate-9).
  - **Native Libraries (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`)**: `23.70 MB` → **`~22.15 MB`** (`-1.55 MB` via maximum-effort Deflate-9 with `extractNativeLibs="true"` legacy packaging preserved).
  - **Assets & Raw Resources (all 3 Alpine rootfs tars, Silero VAD v4+v5, Jieba, KaTeX, models-dev)**: `19.60 MB` → **`~18.40 MB`** (`-1.20 MB` via `.tar.gz` preservation + Deflate-9).
  - **Incremental `zipflinger` Dead Space**: `5.53 MB` → **`0.00 MB`** (`-5.53 MB` via clean sequential packaging).
  - **Total Final APK Size**: **`~57.85 MB – 59.50 MB`** (down from `79.93 MB`, saving **>20 MB** with zero deleted features, assets, or ABIs).
- **Verification & Delivery Sequence**:
  1. Update Gradle asset configuration (`noCompress += "gz"`) and compile the project via `compile_applet`.
  2. Run the surgical DEX icon filter + `d8 --release --min-api 26` consolidation and verify that 100% of `com.openminis.**` classes and all 237 referenced Material Icon classes are present in the merged DEX files.
  3. Package the APK sequentially with `zlib(level=9, memLevel=9)` and 4-byte/4KB alignment, sign with `apksigner` (v2 + v3), and validate with `zipalign -c 4`, `apksigner verify -v`, `unzip -v`, and `unzip -t`.
  4. Upload the verified <60 MB APK to `https://temp.sh/upload` and provide the direct download link and SHA-256 checksum.
