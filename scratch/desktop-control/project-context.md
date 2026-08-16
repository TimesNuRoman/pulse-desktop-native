# Project context — Pulse Desktop "Desktop Control" (screen recognition + PC interaction)

**Date:** 2026-08-16
**Repo:** `C:\Users\1\.minimax\workspace\pulse-desktop-native\` (git: TimesNuRoman/pulse-desktop-native)
**Author:** Coder agent (per Roman's request)

## Project

- **Name:** Pulse Desktop
- **Purpose:** Local-first notes + chat + AI workspace. One window, three panels.
- **License:** Apache 2.0, "Pulse team" anonymous persona (Roman's locked rule)

## Stack (verified from build.gradle.kts + README)

- **Kotlin 2.0.21** + **Jetpack Compose Multiplatform 1.7.0** (native desktop, no Electron)
- **Gradle 8.10.2** (wrapper), **JDK 17** (Temurin)
- **DI:** Koin 4.0.0
- **DB:** SQLite via `org.xerial:sqlite-jdbc:3.45.0.0` + FTS4
- **Crypto:** BouncyCastle `bcprov-jdk18on:1.78.1`
- **Async:** `kotlinx-coroutines-core:1.9.0`
- **Test:** JUnit 5 (`org.junit.jupiter:junit-jupiter:5.10.2`)
- **No existing screen capture / OCR / Robot code** — greenfield for this feature

## Locked rules (from memory + README)

- **DARK only**, no light theme
- **Tokyo Night palette** (already in `ui/theme/`)
- **border-radius: 0** global (square edges, no rounded corners)
- **No emoji в UI** (Roman's hard rule for all products)
- **Inter + JetBrains Mono** fonts (loaded from `src/main/resources/fonts/`)
- **Window:** 1280x800 (min 1024x640)
- **"Pulse team" anonymous persona** (not "by Roman")
- **No telemetry, no phone-home**
- **Local-first** — features should work offline if possible

## Existing features (relevant to this task)

- **Command palette** (`ui/palette/CommandPalette`) — Ctrl+K, registered actions. NEW commands should plug here.
- **AI chat** (`data/ai/`) — local LLM (Qwen, Llama, Mistral, Gemma) via llama.cpp subprocess
- **Settings** (`data/settings/`, `ui/settings/`) — 5 tabs including Models
- **Skills** (`data/skills/`, `ui/skills/`) — custom AI templates, picker, run history
- **Whisper CLI** (`data/voice/`) — for voice (similar pattern: subprocess for heavy work)
- **HTTP client** — used for sync (`api.ownlocalml.com`) and local AI

## Architecture proposal (1-page plan)

### Module: `data/desktop/` (NEW)

```
data/desktop/
├── DesktopController.kt       wraps java.awt.Robot (mouse + keyboard + capture)
├── ScreenCapture.kt           BufferedImage captures (full / region)
├── OcrEngine.kt               Tess4J wrapper (Tesseract 5 via tesseract4java)
├── VisionEngine.kt            sends image to AI (OCR text → text LLM; image → multimodal LLM)
├── DesktopAction.kt           sealed class: Click(x,y) | Type(text) | Key(combo) | Scroll(dx,dy)
└── SafetyGate.kt              per-action confirmation prompt
```

### UI surface

- **Command palette entries** (Ctrl+K):
  - "Что на экране?" — capture → OCR → show text in chat
  - "Опиши экран" — capture → multimodal LLM → show description
  - "Кликни: [text]" — user types target → AI finds coords → confirmation → click
  - "Набери: [text]" — capture focus via OCR → confirmation → type
  - "Скриншот" — save full screen to `~/.pulse/captures/yyyy-MM-dd-HHmmss.png`
- **Settings tab "Desktop"** (NEW): enable desktop control, choose vision model, set safety level (always-confirm / once / never)
- **Skill "Desktop Agent"** (NEW, optional Phase 2): multi-step "do X on my computer" prompts

### MVP scope (this iteration)

1. **DesktopController.kt** — java.awt.Robot wrapper (mouse move/click, keyboard type/press, screen capture)
2. **OcrEngine.kt** — Tess4J (Apache 2.0, mature, local, no API cost)
3. **VisionEngine.kt** — if local model is multimodal (Qwen2-VL, LLaVA, Moondream), send image + text prompt; otherwise fall back to OCR text → text LLM
4. **3 command-palette commands**:
   - "Скриншот" (passive, no risk)
   - "Что на экране?" (OCR text → display, no risk)
   - "Кликни: [target]" (active, needs confirmation)
5. **Settings** — Desktop tab with on/off toggle + safety level
6. **Tests** — JUnit for DesktopController (mock Robot for headless), OcrEngine (Tess4J requires native lib — unit test the contract, not the actual OCR)

### Out of scope (Phase 2+)

- Multi-step agent loop ("open browser, go to github.com, click button")
- macOS accessibility permission flow (Windows + Linux only for MVP)
- Scroll/wheel events
- Window-specific captures (only full screen + rectangle for MVP)
- Screen recording (video)

## Key trade-offs

1. **OCR lib choice (3+ viable):**
   - **Tess4J** (`net.sourceforge.tess4j:tess4j:5.x`) — Apache 2.0, mature, ships with native libs, ~30 MB extra. **PICK: most reliable, works offline.**
   - **Tesseract4java** (newer fork, slimmer) — Apache 2.0, ~10 MB, less mature
   - **PaddleOCR via DJL** — best accuracy, but +200 MB, JVM-friendly via Deep Java Library
   - **Send image to cloud** (OpenAI/Claude) — best accuracy for VL tasks, $cost, requires API key
   - **Local VLM only** (Qwen2-VL, Moondream) — accurate for "what", no OCR for "where"

2. **PC interaction (only 1 viable in pure JVM):**
   - **java.awt.Robot** — built-in, free, works on Win/Linux/macOS (with permissions). **PICK.**
   - **JNativeHook** — global keyboard/mouse HOOKS (lower level), overkill for our use case
   - **JNI to Win32/X11/Cocoa** — fastest, but platform-specific code per OS

3. **Vision model (2 viable):**
   - **Multimodal LLM** (Qwen2-VL, LLaVA, Moondream) — accurate "what + where", needs user to download
   - **OCR text → text LLM** — no extra download, works with any model, less accurate for visual context
   - **PICK: hybrid** — try multimodal first, fall back to OCR+text LLM

## Files relevant to the task

- `src/main/kotlin/com/pulseteam/desktop/Main.kt` — entry point (where to register command palette)
- `src/main/kotlin/com/pulseteam/desktop/ui/palette/CommandPalette.kt` — where to add new commands
- `src/main/kotlin/com/pulseteam/desktop/data/ai/` — local AI engine (for vision)
- `src/main/kotlin/com/pulseteam/desktop/data/skills/` — for the Desktop Agent skill
- `src/main/kotlin/com/pulseteam/desktop/ui/settings/` — for Desktop settings tab
- `src/main/kotlin/com/pulseteam/desktop/data/settings/AppSettings.kt` — for desktop control toggle
- `proguard-rules.pro` — needs update for Tess4J + new classes

## Decisions to NOT re-decide

- Use existing local AI engine (no new model download path)
- No cloud APIs (per "local-first" rule, no telemetry, no phone-home)
- Command palette is the entry point (already shipped, just add commands)
- No new font/icon (Tokyo Night already locked)
- No new DI framework (Koin already set up)

## Roman's decisions (2026-08-16, captured)

1. **Vision model:** Local VLM primary (Qwen2-VL / Moondream / LLaVA, user picks in Settings) + **opt-in cloud** (OpenAI/Claude API key in Settings) for higher accuracy. Cloud is OFF by default, requires explicit user opt-in.
2. **Safety default:** **Always-confirm** for every active command (click / type / key press). User sees coords/text in confirm dialog, must click "Yes" to proceed. No way to disable safety gate in MVP (Phase 2 can add "trusted mode" with password unlock).

## Uncertain about (post-decisions)

1. **Tess4J native libs per platform** — do they ship for Windows x64 / macOS arm64 / Linux x64 in the Maven artifact, or do we need to download separately? Need to verify in research subagent.
2. **java.awt.Robot on headless** — Compose Desktop always has a display, but unit tests run in CI without one. Need a test seam (interface + JvmRobot + FakeRobot).
3. **macOS permission UX** — first click of "Click: [target]" would show OS dialog "Pulse wants to control your computer". Should we auto-defer this or do we need a "Grant access" button? **Defer to Phase 2** — Windows + Linux first.
4. **Local VLM model picker** — how does user know which VLM models Pulse supports? Settings should show: Qwen2-VL 2B (3GB), Moondream 1.8B (1GB), LLaVA 7B (5GB), with download status (similar to Models tab in Settings).

## Read references (per explore-before-build)

- `references/project-context.md` (done)
- `references/concurrent-research.md` (next: spawn subagent for Tess4J + alternatives)
- `references/deep-thinking.md` (after research: 3+ candidates → pick one)
- `references/clean-code.md` (for implementation, plan-first)
- `references/multitask.md` + `references/subagent-decomposition.md` (when implementation starts)
- `references/goal-verification.md` (before declaring done)
- `references/vector.md` (post-task)
- `references/backlog.md` (post-task)
- `references/proprietary-design.md` (Pulse rules apply, no emoji, etc.)
