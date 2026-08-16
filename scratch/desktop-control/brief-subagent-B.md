# Subagent B brief — high-level orchestrator + UI wiring for Pulse Desktop "Desktop Control"

## Mission

Implement the **orchestrator + UI wiring + modified files** for the Pulse Desktop "Desktop Control" feature.
Subagent A (running in parallel) is building the 5 low-level data/desktop/ files. You depend on the **interfaces**
in CONTRACT.md, not on Subagent A's actual code (which compiles in parallel).

You are the integrator: vision engine, controller, dialog, command palette, settings tab, Main.kt wiring.

## Inputs (read these first, IN ORDER)

1. `C:\Users\1\.minimax\skills\explore-before-build\SKILL.md` — main skill, MANDATORY per Roman
2. `C:\Users\1\.minimax\skills\explore-before-build\references\clean-code.md` — your primary sub-skill
3. `C:\Users\1\.minimax\skills\explore-before-build\references\project-context.md` — the project context frame
4. `C:\Users\1\.minimax\skills\explore-before-build\references\jit-research.md` — for any API question during build
5. `C:\Users\1\.minimax\skills\explore-before-build\references\stage-verification.md` — before claiming done
6. `C:\Users\1\.minimax\skills\explore-before-build\references\goal-verification.md` — final check
7. `C:\Users\1\.minimax\skills\explore-before-build\references\proprietary-design.md` — UI rules (no emoji, no rounded)
8. `C:\Users\1\.minimax\skills\explore-before-build\references\vector.md` — for the final summary
9. `C:\Users\1\.minimax\workspace\pulse-desktop-native\scratch\desktop-control\plan.md` — the 1-page plan
10. `C:\Users\1\.minimax\workspace\pulse-desktop-native\scratch\desktop-control\CONTRACT.md` — **THE source of truth for interfaces**
11. `C:\Users\1\.minimax\workspace\pulse-desktop-native\src\main\kotlin\com\pulseteam\desktop\Main.kt` — to understand wiring
12. `C:\Users\1\.minimax\workspace\pulse-desktop-native\src\main\kotlin\com\pulseteam\desktop\ui\palette\CommandPalette.kt` — to add 3 commands
13. `C:\Users\1\.minimax\workspace\pulse-desktop-native\src\main\kotlin\com\pulseteam\desktop\ui\settings\SettingsScreen.kt` — to add Desktop tab
14. `C:\Users\1\.minimax\workspace\pulse-desktop-native\src\main\kotlin\com\pulseteam\desktop\data\settings\AppSettings.kt` — to add 4 fields
15. `C:\Users\1\.minimax\workspace\pulse-desktop-native\src\main\kotlin\com\pulseteam\desktop\data\ai\LlamaClient.kt` — for `LocalTextLlm` impl pattern (HTTP + JSON)

## Project context (for design decisions)

- **Stack:** Kotlin 2.0.21, Jetpack Compose Multiplatform 1.7.0, JDK 17, JUnit 5
- **No new Gradle deps** — only what's already in `build.gradle.kts`
- **No new HTTP lib** — use `HttpURLConnection` (mirroring `WhisperTranscriber.kt` / `LlamaClient.kt`)
- **Pulse rules:** dark only, Tokyo Night palette, `border-radius: 0` (RectangleShape only), no emoji in UI, Apache 2.0 header, "Pulse team" anonymous persona
- **UI primitives:** `RectangleShape` for all shapes, `PulseColors` for all colors, `Kbd` / `StatusDot` / `HDivider` for shared bits — all in `ui/common/CommonComponents.kt`

## Your files (6 NEW + 4 MODIFIED + 2 tests)

### NEW production files (all in `data/desktop/` unless noted)

1. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/VisionEngine.kt`**
   - `OcrWord` is in `OcrEngine.kt` (Subagent A). Don't redefine.
   - Define: `ScreenMatch`, `ScreenDescription`, `VisionEngine` interface, `OcrFallbackVisionEngine` real impl, `FakeVisionEngine` test impl, **`TextLlm` interface**, **`CloudVlm` interface**. From CONTRACT §5 + §6.
   - `OcrFallbackVisionEngine`:
     - `findOnScreen(target)`: capture → `ocr.ocr(img)` → match `target` against `result.words` (case-insensitive, exact > starts-with > contains, first hit wins) → return `ScreenMatch(found=true, x=word.left+word.width/2, y=word.top+word.height/2, conf=word.conf, matchedText=word.text)`. If no match → `ScreenMatch(found=false)`.
     - `describeScreen()`: capture → `ocr.ocr(img)`. If `cloudVlm != null && cloudVlm.isEnabled()` → call `cloudVlm.describe(pngBytes, "Describe what you see in 1-2 sentences")` → return `ScreenDescription(cloudText, true)`. Else → call `textLlm.complete("OCR text: <text>\n\nSummarize what's on screen in 1-2 sentences", maxTokens=200)` → return `ScreenDescription(textLlmResponse, false)`. If both return null → return `ScreenDescription("(no description available)", false)`.

2. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/LocalTextLlm.kt`**
   - `class LocalTextLlm(private val llamaClient: LlamaClient) : TextLlm`
   - `suspend override fun complete(prompt: String, maxTokens: Int): String?` — calls `llamaClient.complete(prompt, maxTokens = maxTokens)`. Read `LlamaClient.kt` for the exact API signature.
   - If `LlamaClient` doesn't have a `complete(prompt, maxTokens)` method, use whatever the closest equivalent is. Flag in summary.

3. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/OpenAiCloudVlm.kt`**
   - `class OpenAiCloudVlm(private val apiKeyProvider: () -> String) : CloudVlm`
   - `isEnabled()`: `apiKeyProvider().isNotBlank()`.
   - `describe(imagePngBytes, prompt)`: POST to `https://api.openai.com/v1/chat/completions` with body:
     ```json
     {
       "model": "gpt-4o-mini",
       "max_tokens": 300,
       "messages": [{"role": "user", "content": [
         {"type": "text", "text": "<prompt>"},
         {"type": "image_url", "image_url": {"url": "data:image/png;base64,<base64>"}}
       ]}]
     }
     ```
   - Parse response, return `choices[0].message.content` as String. Return null on any error.
   - Use `HttpURLConnection` (mirror `LlamaClient.kt` or `SyncEngine.kt` patterns). Use `org.json.JSONObject` (already in build.gradle.kts).
   - Log via `PulseLogger`.

4. **`src/main/kotlin/com/pulseteam/desktop/data/desktop/DesktopController.kt`**
   - Per CONTRACT §8. Methods: `takeScreenshot()`, `readScreenText()`, `proposeClickOnText(target)`, `executeApproved()`, `cancelPending()`, plus a public `safetyGateState: StateFlow<SafetyState>` (delegated from SafetyGate).
   - `takeScreenshot`: `mkdirs` on capturesDir, `captureFull()` → `ImageIO.write(img, "png", File(capturesDir, "yyyy-MM-dd-HHmmss.png"))`. Returns the file.
   - `readScreenText`: `captureFull()` → `ocr.ocr(img).text`. No side effects.
   - `proposeClickOnText(target)`: `captureFull()` → save preview to `File(capturesDir, "preview-yyyy-MM-dd-HHmmss.png")` → `vision.findOnScreen(target)`. If not found → `ProposeResult.NotFound(target)`. If `!screen.isAvailable() || !pc.isAvailable() || !ocr.isAvailable()` → `ProposeResult.Unavailable("...")`. Else → `safety.request(Click(x, y), summary = "Click at ($x, $y) — \"${target}\"", screenshotPath = preview)` → if returns true, execute immediately (shouldn't happen with AlwaysConfirm, but handle it). If returns false, return `ProposeResult.NeedsConfirmation(summary, preview)`.
   - `executeApproved`: `safety.state.value.pending?.let { execute(it.action); safety.confirm(); true } ?: false`. For `Click`, call `pc.click(action.x, action.y)`. For `Type`/`Key`/`Hotkey` — Phase 1 stubs (return true after safety.confirm()). The Type/Key/Hotkey UI flow is not wired in Phase 1.

5. **`src/main/kotlin/com/pulseteam/desktop/ui/desktop/ConfirmActionDialog.kt`**
   - Per CONTRACT §9. Modal with backdrop, centered card, summary text, preview image (use `ImageIO.read(file).asImageBitmap()` from `androidx.compose.ui.graphics.asImageBitmap`), Cancel + Confirm buttons.
   - Width 560dp, max height 640dp.
   - Esc key = Cancel. Enter key = Confirm. Use `onPreviewKeyEvent` on the outer Box like the existing CommandPalette pattern.
   - Squared edges, Tokyo Night, all colors from `PulseColors`.
   - Render preview image as `Image(bitmap = ..., modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit)`.

### MODIFIED files

6. **`src/main/kotlin/com/pulseteam/desktop/data/settings/AppSettings.kt`**
   - Add `VisionModel` enum (just two values: `OcrOnly`, `OpenAiCloud`) at the top.
   - Add 4 fields to `AppSettings`: `desktopEnabled: Boolean = false`, `safetyLevel: SafetyLevel = SafetyLevel.AlwaysConfirm`, `visionModel: VisionModel = VisionModel.OcrOnly`, `cloudApiKey: String = ""`.
   - Add import for `com.pulseteam.desktop.data.desktop.SafetyLevel`.
   - Update `load()` to read these 4 properties (default values if missing).
   - Update `persist()` to write these 4 properties.
   - **Do not break existing keys.** Older `~/.pulse/settings.properties` won't have these → defaults apply → no breakage.

7. **`src/main/kotlin/com/pulseteam/desktop/ui/palette/CommandPalette.kt`**
   - Add 3 new `PaletteAction` variants: `object TakeScreenshot`, `object ReadScreenText`, `data class ClickOnText(val target: String)`.
   - Add 3 entries in `buildCommands(notes)` under section "Desktop":
     ```kotlin
     out += PaletteCommand("screenshot", "Скриншот (save to captures)", "Desktop", PaletteAction.TakeScreenshot, "Ctrl ⇧ S")
     out += PaletteCommand("read-screen", "Что на экране? (OCR)", "Desktop", PaletteAction.ReadScreenText)
     out += PaletteCommand("click-on-text", "Кликни: …", "Desktop", PaletteAction.ClickOnText(""))
     ```
   - Don't add the inline input UI for `ClickOnText` — that's Main.kt's job.

8. **`src/main/kotlin/com/pulseteam/desktop/ui/settings/SettingsScreen.kt`**
   - Add `SettingsTab.Desktop("desktop")` to the enum.
   - Add `SettingsSection.Desktop("Desktop", Icons.Default.Memory, SettingsTab.Desktop)` to the section enum.
   - Add `Icons.Default.Memory` to the ActivityRail items list.
   - Add case `SettingsTab.Desktop -> DesktopPanel(...)` in the when block.
   - Update `SettingsScreen` signature to accept `desktop: DesktopController? = null` and `desktopEnabled: Boolean = false` (read from AppSettingsStore).
   - `DesktopPanel` composable:
     - Toggle: "Enable desktop control" → `AppSettingsStore.update { it.copy(desktopEnabled = !it.desktopEnabled) }`
     - Status rows: `OcrEngine.statusMessage()` + `OcrEngine.isAvailable()` (green/red dot). Same for `PcController` and `ScreenCapture` (exposed via `desktop: DesktopController?` getters). Add 3 public getter methods to `DesktopController`: `ocrStatus(): String`, `ocrAvailable(): Boolean`, `pcAvailable(): Boolean`, `screenAvailable(): Boolean`. Mirror the field of the same name in the impl.
     - "Vision model" dropdown: shows current value, clicking cycles between `OcrOnly` and `OpenAiCloud`.
     - "OpenAI API key" (only if `visionModel == OpenAiCloud`): text input (masked with `*` chars, but for simplicity just use a normal `BasicTextField` with a placeholder "sk-..." — no actual masking in MVP). Updates `cloudApiKey` in AppSettingsStore.
     - "Safety level" row: shows "Always confirm" (locked, with hint "More options in a future update").
     - All rows use the existing `ToggleRow` / `SelectRow` helpers at the bottom of the file (or copy their pattern).

9. **`src/main/kotlin/com/pulseteam/desktop/Main.kt`**
   - Add imports for: `DesktopController`, `RobotScreenCapture`, `TesseractCliOcr`, `RobotPcController`, `OcrFallbackVisionEngine`, `LocalTextLlm`, `OpenAiCloudVlm`, `ConfirmActionDialog`, `PaletteAction.{TakeScreenshot,ReadScreenText,ClickOnText}`.
   - Add `val desktop = remember { DesktopController(...) }` with real impls wired:
     ```kotlin
     val screen = remember { RobotScreenCapture() }
     val ocr = remember { TesseractCliOcr() }
     val pc = remember { RobotPcController() }
     val textLlm = remember { LocalTextLlm(llamaClient) }
     val cloudVlm = remember {
         OpenAiCloudVlm(apiKeyProvider = { AppSettingsStore.state.value.cloudApiKey })
     }
     val vision = remember { OcrFallbackVisionEngine(screen, ocr, textLlm, cloudVlm) }
     val safety = remember { SafetyGate() }
     val desktop = remember {
         DesktopController(
             screen = screen, ocr = ocr, pc = pc,
             vision = vision, safety = safety,
         )
     }
     ```
   - Sync `safety.configure(...)` with `AppSettingsStore.state.value.desktopEnabled` / `safetyLevel` via a `LaunchedEffect(settings)`:
     ```kotlin
     val settings by AppSettingsStore.state.collectAsState()
     LaunchedEffect(settings.desktopEnabled, settings.safetyLevel) {
         safety.configure(settings.desktopEnabled, settings.safetyLevel)
     }
     ```
   - In the existing `when (action)` block in `CommandPalette(...)`, add:
     ```kotlin
     is PaletteAction.TakeScreenshot -> {
         scope.launch { lastEvent = "Screenshot: " + (desktop.takeScreenshot()?.name ?: "failed") }
     }
     is PaletteAction.ReadScreenText -> {
         scope.launch { lastEvent = "Screen: \"${desktop.readScreenText().take(80)}\"" }
     }
     is PaletteAction.ClickOnText -> {
         scope.launch {
             when (val r = desktop.proposeClickOnText(action.target)) {
                 is ProposeResult.NeedsConfirmation -> lastEvent = "Click: confirm dialog"
                 is ProposeResult.NotFound -> lastEvent = "Click: \"${r.target}\" not found on screen"
                 is ProposeResult.Unavailable -> lastEvent = "Click: ${r.reason}"
                 is ProposeResult.Executed -> lastEvent = "Click: ${r.message}"
             }
         }
     }
     ```
   - After the `if (paletteOpen) CommandPalette(...)` block, add a `if (desktop.safetyGateState.collectAsState().value.pending != null) ConfirmActionDialog(...)` block. Pass `pending`, `onConfirm = { scope.launch { desktop.executeApproved() } }`, `onCancel = { desktop.cancelPending() }`.
   - Update `SettingsScreen(...)` call to pass `desktop = desktop`.

### Test files (JUnit 5)

10. **`src/test/kotlin/com/pulseteam/desktop/data/desktop/DesktopControllerTest.kt`**
    - Use `FakeScreenCapture`, `FakeOcrEngine(canned=[OcrWord("Submit", 100, 200, 80, 30, 95)])`, `FakePcController`, `FakeVisionEngine`, real `SafetyGate`.
    - `proposeClickOnText("Submit")` → `ProposeResult.NeedsConfirmation(...)`. Then `safety.confirm()` → `executeApproved()` returns `true`. Assert `pc.calls.last().op == "click"` with x=140, y=215.
    - `proposeClickOnText("missing")` → `ProposeResult.NotFound("missing")`.
    - `takeScreenshot()` → returns a `File` in the captures dir.
    - `readScreenText()` → returns "Submit" (the canned word's text).

11. **`src/test/kotlin/com/pulseteam/desktop/data/desktop/VisionEngineTest.kt`**
    - `FakeVisionEngine(match = ScreenMatch(found=true, x=100, y=200, conf=95, matchedText="OK")).findOnScreen("OK")` → matches.
    - `OcrFallbackVisionEngine` with `FakeScreenCapture` + `FakeOcrEngine(canned=[OcrWord("ok", 0, 0, 20, 20, 90)])` + `TextLlm` fake returning "ok button" + `null` cloudVlm → `findOnScreen("ok")` returns `ScreenMatch(found=true, x=10, y=10, conf=90)`. `describeScreen()` returns `ScreenDescription("ok button", false)`.

## Hard constraints

- Apache 2.0 header on every NEW file (copy from existing files)
- **No new Gradle deps.** Use `HttpURLConnection` + `org.json` (already on classpath).
- **No new icons** beyond what's already in `materialIconsExtended` and used in the codebase (`Search`, `Memory`, `Person`, `Tune`, `GraphicEq`).
- **No `RoundedCornerShape` anywhere** — `RectangleShape` only.
- **No emoji in UI** (locked Pulse rule).
- **No telemetry, no phone-home**.
- All HTTP calls via `withContext(Dispatchers.IO)`.
- All UI strings in Russian where they match the existing palette ("Скриншот", "Что на экране", "Кликни") but the toggle labels and section names in English to match the existing Settings panel style.

## Output (deliverables, in this exact order)

1. **All 6 NEW files + 4 MODIFIED files** written and saved.
2. **Run `gradle compileKotlin`** from `C:\Users\1\.minimax\workspace\pulse-desktop-native` with `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'`. Must succeed. **Note: Subagent A is writing in parallel, so you may hit a brief moment where Subagent A's files don't exist yet. If so, re-run compileKotlin after both subagents are done.** Document any compile errors in part-B-summary.md so the main agent can fix.
3. **Run `gradle test --tests "com.pulseteam.desktop.data.desktop.*"`** — all tests pass.
4. **Write `C:\Users\1\.minimax\workspace\pulse-desktop-native\scratch\desktop-control\part-B-summary.md`** with:
   - Final file list (new + modified, with line counts)
   - `gradle compileKotlin` output (last 10 lines)
   - `gradle test` output (test count: passed/skipped/failed)
   - All deviations from CONTRACT.md (if any)
   - Any blockers / unknowns for the main agent
   - One-paragraph "what's done"

## Self-review checklist (before writing part-B-summary.md)

Per `stage-verification.md`, verify:
- [ ] All 6 new files compile (no errors, no warnings about unused imports)
- [ ] All 4 modified files compile
- [ ] All 2 new test files compile and pass
- [ ] `VisionEngine.kt` includes `TextLlm` and `CloudVlm` interfaces (not in separate files)
- [ ] `DesktopController` has `safetyGateState` getter delegated to SafetyGate
- [ ] `OpenAiCloudVlm` uses `HttpURLConnection` + `org.json` (no new deps)
- [ ] `AppSettings` round-trips 4 new fields (load + persist)
- [ ] `CommandPalette` has 3 new actions + 3 new buildCommands entries
- [ ] `SettingsScreen` has `SettingsTab.Desktop` + `DesktopPanel` composable
- [ ] `Main.kt` wires all 5 impls into `DesktopController`, syncs `SafetyGate.configure(...)` with `AppSettingsStore`, renders `ConfirmActionDialog` when `pending != null`
- [ ] No emoji, no RoundedCornerShape, no `println`, no new Gradle deps
- [ ] Apache 2.0 header on every NEW file
- [ ] All test classes have at least 2 `@Test` methods each

## Constraints to keep you in lane

- **DO NOT** touch any file outside the 6 new + 4 modified + 2 tests listed
- **DO NOT** add new Gradle dependencies
- **DO NOT** modify `core_rules.js` or any lesside / Pulse Android files
- **DO NOT** commit or push to git — main agent integrates and commits

## Estimated time

≤ 30 min for 6 new + 4 modified + 2 tests + 4 file reads. If you go over 45 min, write what you have + clear "BLOCKED" section in part-B-summary.md and stop.

Start by reading the 8 skill files + plan + CONTRACT + Main.kt + CommandPalette.kt + SettingsScreen.kt + AppSettings.kt + LlamaClient.kt. Then implement.
