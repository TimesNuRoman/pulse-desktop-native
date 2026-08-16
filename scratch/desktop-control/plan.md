# Pulse Desktop "Desktop Control" — Phase 1 plan

**Date:** 2026-08-16
**Branch:** `feat/desktop-control` (local) → push to `TimesNuRoman/pulse-desktop-native` as PR or single commit
**Goal:** ship working screen-capture + OCR + click-by-text + screenshot command-palette flow

## Scope (MVP)

User-visible:
- 3 command-palette commands (Ctrl+K):
  - **"Скриншот"** — passive, saves full screen PNG to `~/.pulse/captures/yyyy-MM-dd-HHmmss.png`. No risk.
  - **"Что на экране?"** — capture → OCR → display text in chat. No risk.
  - **"Кликни: <target>"** — capture → OCR → find target word → SafetyGate confirm dialog with highlight → click. Active, always-confirm.
- Settings → "Desktop" tab: enable/disable, safety level (always-confirm only in MVP), vision model picker (OCR-only default, opt-in OpenAI cloud for image-understanding), opt-in OpenAI API key field.

Out of scope (Phase 2+):
- Multi-step agent loop
- macOS accessibility permission flow (Windows + Linux only for MVP — macOS UI to be added when Apple Silicon path is sorted)
- Scroll/wheel
- Window-specific captures
- Screen recording
- "Набери: <text>" — not in Phase 1, requires focused-element detection

## Architecture

```
data/desktop/                              [NEW]
├── DesktopAction.kt                       sealed class: Click | Type | Key | Hotkey | Screenshot
├── ScreenCapture.kt                       interface + RobotScreenCapture + FakeScreenCapture
├── OcrEngine.kt                           interface + TesseractCliOcr + FakeOcrEngine
├── PcController.kt                        interface + RobotPcController + FakePcController
├── VisionEngine.kt                        interface + OcrFallbackVisionEngine + FakeVisionEngine
├── DesktopController.kt                   orchestrator (capture + ocr + vision + pc + safety glue)
└── SafetyGate.kt                          safety policy + state flow
ui/desktop/                                [NEW]
└── ConfirmActionDialog.kt                 modal "Click here?" preview (Compose)
ui/palette/CommandPalette.kt               [+3 PaletteAction + 3 buildCommands entries]
ui/settings/SettingsScreen.kt              [+1 SettingsTab.Desktop + DesktopPanel]
Main.kt                                    [+DesktopController param wired to palette + settings]
data/settings/AppSettings.kt               [+desktopEnabled + safetyLevel + visionModel + cloudApiKey]
```

## Key decisions (locked)

1. **OCR = Tesseract via subprocess** (matches Pulse's existing Whisper CLI pattern, no new Java dep). User must install `tesseract` system-wide; Pulse detects + shows install instructions on Settings → Desktop.
2. **PC interaction = `java.awt.Robot`** (JDK built-in, zero new deps).
3. **Screen capture = `java.awt.Robot.createScreenCapture(Rectangle)`** + multi-resolution for HiDPI.
4. **Safety default = always-confirm** (per Roman's choice 2026-08-16). Even after first use, every active action shows the confirm dialog. No way to disable in MVP.
5. **Vision = OCR + text LLM by default**, opt-in cloud for image-understanding (Roman's choice 2026-08-16). Local VLMs (Moondream, Qwen2-VL) stubbed in model picker but not implemented in Phase 1.
6. **Interfaces + Fake impls for testability** — `RobotScreenCapture`, `TesseractCliOcr`, `RobotPcController` are real impls; `Fake*` versions for unit tests. `DesktopController` is testable without display.
7. **All settings persisted via existing `AppSettingsStore`** — add 4 fields: `desktopEnabled`, `safetyLevel`, `visionModel`, `cloudApiKey`.
8. **No emoji in UI** (locked Pulse rule).
9. **Square edges, Tokyo Night, dark only** (locked Pulse rules).
10. **No telemetry, no phone-home, local-first** (locked Pulse rules).

## Risks & watch-outs

- **macOS Accessibility**: not in Phase 1. Tesseract + Robot may silently no-op on macOS. Document "Windows + Linux only" in the Desktop settings panel.
- **Linux Wayland**: `java.awt.Robot` is X11-only. Document.
- **Windows HiDPI**: `createMultiResolutionScreenCapture` + select native variant.
- **Tesseract missing**: detect on first use, show clear "Install tesseract" instructions with platform-specific command. Don't fail loudly on app start.
- **Confirm dialog modal**: must use a Compose modal that **blocks the underlying Window** so the user can't accidentally trigger other actions. Pattern: `Box(Modifier.fillMaxSize().background(PulseColors.Drop).clickable {})` like the existing settings overlay.

## Files & test plan

| File | Type | Tests |
|---|---|---|
| `data/desktop/DesktopAction.kt` | value class | none (just data) |
| `data/desktop/ScreenCapture.kt` | interface + 2 impls | unit: Fake, Robot (skipped in CI) |
| `data/desktop/OcrEngine.kt` | interface + 2 impls | unit: Fake returns canned; Real skipped in CI |
| `data/desktop/PcController.kt` | interface + 2 impls | unit: Fake records calls; Real skipped in CI |
| `data/desktop/VisionEngine.kt` | interface + 2 impls | unit: OcrFallbackVisionEngine with Fakes |
| `data/desktop/DesktopController.kt` | orchestrator | unit: with all Fakes |
| `data/desktop/SafetyGate.kt` | state holder | unit: require/allow logic |
| `ui/desktop/ConfirmActionDialog.kt` | composable | manual (UI test would need rule) |
| `ui/palette/CommandPalette.kt` | modified | manual (Ctrl+K) |
| `ui/settings/SettingsScreen.kt` | modified | manual (Settings open) |
| `Main.kt` | modified | manual (app boots) |
| `data/settings/AppSettings.kt` | modified | unit: load/save new fields |

Unit tests live in `src/test/kotlin/com/pulseteam/desktop/data/desktop/`. Follow `NoteLinkParserTest` pattern (JUnit 5, no Compose).

## Subagent split (multitask)

- **Subagent A (low-level)**: 5 files = `DesktopAction`, `ScreenCapture`, `OcrEngine`, `PcController`, `SafetyGate`. Owns interfaces + impls. No UI.
- **Subagent B (high-level)**: 3 new files = `VisionEngine`, `DesktopController`, `ConfirmActionDialog` + 3 modified files = `CommandPalette.kt`, `SettingsScreen.kt`, `Main.kt`, `AppSettings.kt`. Owns integration.

Both subagents get the same `CONTRACT.md` so interfaces are stable.

## Verification (main agent, after both subagents done)

1. `cd C:\Users\1\.minimax\workspace\pulse-desktop-native; $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'; .\gradlew.bat compileKotlin` — must succeed, zero errors
2. `.\gradlew.bat test` — all unit tests pass
3. `.\gradlew.bat run` — app boots to OnboardingScreen (no crash on startup with new code paths)
4. Build .exe + .msi: `.\gradlew.bat packageReleaseExe packageReleaseMsi` — both build, file sizes reasonable (<80 MB)
5. Commit + push as `TimesNuRoman <125929449+TimesNuRoman@users.noreply.github.com>` on `feat/desktop-control` branch (or main if single commit)

## Out-of-band follow-ups (backlog)

- macOS Accessibility onboarding screen (with `x-apple.systempreferences:` URL)
- Local VLM support (Moondream 1.8B + Qwen2-VL 2B)
- "Набери: <text>" — type text into focused element
- Scroll/wheel commands
- Multi-step agent loop (Skill "Desktop Agent")
- Per-app capture (single window, not full screen)
