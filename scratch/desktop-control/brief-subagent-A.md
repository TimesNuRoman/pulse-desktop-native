# Subagent A brief — low-level primitives for Pulse Desktop "Desktop Control"

## Mission

Implement the **5 low-level data/desktop/ files** for the Pulse Desktop "Desktop Control" feature.
Subagent B (running in parallel) will build the orchestrator + UI on top of your interfaces.

You are NOT building UI, NOT building the orchestrator, NOT touching CommandPalette / SettingsScreen / Main.kt / AppSettings.kt.

## Inputs (read these first, IN ORDER)

1. `C:\Users\1\.minimax\skills\explore-before-build\SKILL.md` — main skill, MANDATORY per Roman
2. `C:\Users\1\.minimax\skills\explore-before-build\references\clean-code.md` — your primary sub-skill
3. `C:\Users\1\.minimax\skills\explore-before-build\references\project-context.md` — the project context frame
4. `C:\Users\1\.minimax\skills\explore-before-build\references\jit-research.md` — for any API question during build
5. `C:\Users\1\.minimax\skills\explore-before-build\references\stage-verification.md` — before claiming done
6. `C:\Users\1\.minimax\skills\explore-before-build\references\goal-verification.md` — final check
7. `C:\Users\1\.minimax\skills\explore-before-build\references\vector.md` — for the final summary
8. `C:\Users\1\.minimax\workspace\pulse-desktop-native\scratch\desktop-control\plan.md` — the 1-page plan
9. `C:\Users\1\.minimax\workspace\pulse-desktop-native\scratch\desktop-control\CONTRACT.md` — **THE source of truth for interfaces**
10. `C:\Users\1\.minimax\workspace\pulse-desktop-native\src\main\kotlin\com\pulseteam\desktop\data\voice\WhisperTranscriber.kt` — read for the subprocess pattern (downloads, logging, StateFlow). Mirror this style.

## Project context (for design decisions)

- **Stack:** Kotlin 2.0.21, Jetpack Compose Multiplatform 1.7.0, JDK 17, JUnit 5
- **No new Gradle deps** — only what's already in `build.gradle.kts`
- **No Tess4J, no JNI** — Tesseract via subprocess, same as WhisperTranscriber
- **Pulse rules:** dark only, Tokyo Night palette, `border-radius: 0` (RectangleShape only), no emoji in UI, Apache 2.0 header on every file, "Pulse team" anonymous persona
- **Logger:** `com.pulseteam.desktop.data.log.PulseLogger` — use it for all logging

## Your files (5 new + 4 tests)

### New production files

1. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/DesktopAction.kt`** — sealed class + data classes + `MouseButton` enum. Pure data, no logic. From CONTRACT §1.

2. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/ScreenCapture.kt`** — interface + `RobotScreenCapture` (real) + `FakeScreenCapture` (test). From CONTRACT §2.
   - `RobotScreenCapture` uses `Toolkit.getDefaultToolkit().screenSize` for screen size, `createMultiResolutionScreenCapture(Rectangle)` for full capture. If only one resolution variant, use that. For region capture, fall back to `createScreenCapture(rect)`.
   - `isAvailable()`: try `Robot()` in a `try { ... } catch (AWTException) { return false }`. If success, return true. Note: capturing Robot in headless envs throws `AWTException` ("headless environment" or "no screen devices").

3. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/OcrEngine.kt`** — interface + `OcrWord` + `OcrResult` data classes + `TesseractCliOcr` (real) + `FakeOcrEngine` (test). From CONTRACT §3.
   - `TesseractCliOcr` does NOT download anything. It shells out to `tesseract <png> <outputBase> -l <lang> tsv`. If `tesseract` is not on PATH, `isAvailable()` returns false, `statusMessage()` returns the OS-specific install hint.
   - Use `ProcessBuilder` + `withContext(Dispatchers.IO)`. 10s timeout. Log to `~/.pulse/logs/tesseract.log` (mirror WhisperTranscriber's pattern at line 219-232).
   - TSV columns: text(11), left(6), top(7), width(8), height(9), conf(10). Drop header. Filter `text.isBlank() || conf < 60`.
   - Image is written to a temp file via `ImageIO.write(image, "png", tempFile)` (no BufferedImage → bytes path needed; simpler).
   - `statusMessage()`: runs `tesseract --version` once, returns `"tesseract 5.4.1"` on success or `"tesseract not found on PATH"` on failure. Cache the result in a `lazy` delegate.

4. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/PcController.kt`** — interface + `RobotPcController` (real) + `FakePcController` (test). From CONTRACT §4.
   - `RobotPcController` uses `Robot().apply { autoDelay = 40 }`. `mouseMove(x, y)`, then `mousePress(InputEvent.BUTTON1_DOWN_MASK)`, then `mouseRelease`.
   - `typeText` uses `KeyEvent.getExtendedKeyCodeForChar(ch)`. Skip `CHAR_UNDEFINED` chars (return early in the forEach). Per-char delay via `robot.delay(perCharDelayMs)`.
   - `pressHotkey` order: press all but last, press+release last, release the rest in reverse.
   - `FakePcController`: data class `Call(op: String, args: Map<String, Any?>)` + `MutableList<Call>`. Each method appends one call.

5. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/SafetyGate.kt`** — class with `MutableStateFlow<SafetyState>`. From CONTRACT §7.
   - Pure logic, no Compose. UI watches the state flow and renders the dialog.

### Test files (JUnit 5, no Compose, follow `NoteLinkParserTest` pattern)

1. `src/test/kotlin/com/pulseteam/desktop/data/desktop/PcControllerTest.kt` — exercise `FakePcController`:
   - `click(100, 200)` → assert `calls[0].op == "click"` and `args["x"] == 100`, `args["y"] == 200`
   - `typeText("hi")` → 2 calls (`press` + `release` per char)
   - `pressHotkey(listOf(VK_CONTROL, VK_C))` → 4 calls: press CTRL, press C, release C, release CTRL
   - `pressKey(VK_ENTER)` → 2 calls (press, release)

2. `src/test/kotlin/com/pulseteam/desktop/data/desktop/SafetyGateTest.kt`:
   - `configure(false, AlwaysConfirm)` then `request(Click(0,0), "x")` → returns `true` (allowed, even at AlwaysConfirm)
   - Default: `request(Click(0,0), "x")` → returns `false`, `state.value.pending != null`
   - `confirm()` after request → returns `true` next call, `state.value.pending == null`
   - `cancel()` after request → returns `true` next call, `state.value.pending == null`

3. `src/test/kotlin/com/pulseteam/desktop/data/desktop/OcrEngineTest.kt`:
   - `FakeOcrEngine(canned = [OcrWord("hello", 0, 0, 50, 20, 90)])` → `ocr.ocr(fakeImage).words.size == 1` and `.text == "hello"`
   - `FakeOcrEngine(available=false).isAvailable() == false`

4. `src/test/kotlin/com/pulseteam/desktop/data/desktop/ScreenCaptureTest.kt`:
   - `FakeScreenCapture(1920, 1080).screenSize() == Rectangle(0, 0, 1920, 1080)`
   - `FakeScreenCapture.captureFull().width == 1920`
   - `isAvailable() == true` (Fake always returns true)

   Don't try to test `RobotScreenCapture` — it requires a display. Test the Fake only.

## Hard constraints

- Apache 2.0 header on every file (copy from WhisperTranscriber.kt:1)
- `package com.pulseteam.desktop.data.desktop` (matches CONTRACT.md)
- Imports: use `java.awt.Rectangle`, `java.awt.Robot`, `java.awt.Toolkit`, `java.awt.event.InputEvent`, `java.awt.event.KeyEvent`, `java.awt.image.BufferedImage`, `java.io.File`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`
- **No `Thread.sleep` in production paths** — use `robot.delay(ms)` in PcController or `proc.waitFor(timeout, TimeUnit)` in OcrEngine
- **No `Random` / `println`** — use `PulseLogger` only
- All unit tests use JUnit 5 (`org.junit.jupiter.api.Test`, `Assertions.*`)

## Output (deliverables, in this exact order)

1. **All 9 files** written and committed to disk (no fake outputs).
2. **Run `gradle compileKotlin` from `C:\Users\1\.minimax\workspace\pulse-desktop-native`** (set `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'` first). Must succeed.
3. **Run `gradle test --tests "com.pulseteam.desktop.data.desktop.*"`** — all 4 test classes pass. Use full path: `.\gradlew.bat test --tests "com.pulseteam.desktop.data.desktop.*"` from project root.
4. **Write `C:\Users\1\.minimax\workspace\pulse-desktop-native\scratch\desktop-control\part-A-summary.md`** with:
   - Final file list (with line counts via `wc -l`)
   - `gradle compileKotlin` output (last 5 lines)
   - `gradle test` output (test count: passed/skipped/failed)
   - Deviations from CONTRACT.md (if any) — be honest, main agent integrates
   - Any blockers / unknowns for Subagent B
   - One-paragraph "what's done"

## Self-review checklist (before writing part-A-summary.md)

Per `stage-verification.md`, verify:
- [ ] All 5 production files compile (no errors, no warnings about unused imports)
- [ ] All 4 test files compile and pass
- [ ] `RobotScreenCapture.createFull()` uses `createMultiResolutionScreenCapture` and picks native-resolution variant
- [ ] `TesseractCliOcr` writes to a temp PNG (via `ImageIO.write`), runs tesseract, parses TSV
- [ ] `TesseractCliOcr.statusMessage()` returns install hint when tesseract missing
- [ ] `FakePcController` records every call
- [ ] `SafetyGate.request` returns `true` when disabled (caller should not call, but if it does, treat as "OK to proceed, no dialog")
- [ ] No emoji, no RoundedCornerShape, no `println`
- [ ] Apache 2.0 header on every file
- [ ] All test classes have at least 2 `@Test` methods each
- [ ] `gradle test` output is captured in the summary

## Constraints to keep you in lane

- **DO NOT** touch any file outside the 9 listed (no Main.kt, no CommandPalette.kt, no AppSettings.kt, no build.gradle.kts)
- **DO NOT** add new Gradle dependencies
- **DO NOT** create a `ui/desktop/` folder (Subagent B owns it)
- **DO NOT** create a `VisionEngine.kt` or `DesktopController.kt` (Subagent B owns them)
- **DO NOT** call into `LlamaClient` / `LlamaEngine` / `ModelsRepository` — those are Subagent B's concern
- **If you find a CONTRACT issue**, write it in part-A-summary.md and use your best judgment + flag it. Don't silently rename.

## Git (commit + push optional)

DO NOT commit or push. Just write the files. Main agent commits at integration time.

## Estimated time

≤ 20 min for compile + 4 test classes + 5 production files. If you go over 30 min, write what you have + a clear "BLOCKED" section in part-A-summary.md and stop.

Start by reading the 6 skill files + plan + CONTRACT + WhisperTranscriber.kt. Then implement.
