# CONTRACT — Pulse Desktop Control interfaces (Phase 1)

**Date:** 2026-08-16
**Owner:** main agent (Roman's Coder)
**Consumers:** Subagent A (low-level impls), Subagent B (orchestrator + UI)

> This is the shared schema. Both subagents conform to the interfaces here. If a subagent
> needs to deviate, they MUST note it in their part-X-summary.md and the main agent decides
> at integration time. Don't silently rename or change signatures.

## Package layout

```
com.pulseteam.desktop.data.desktop
    DesktopAction          (sealed class + data classes)
    ScreenCapture          (interface)
    OcrEngine              (interface + data classes)
    PcController           (interface)
    VisionEngine           (interface + data classes)
    SafetyGate             (class, state holder)
    DesktopController      (orchestrator class)
com.pulseteam.desktop.ui.desktop
    ConfirmActionDialog    (composable)
```

All files in `src/main/kotlin/com/pulseteam/desktop/data/desktop/`.
UI dialog in `src/main/kotlin/com/pulseteam/desktop/ui/desktop/`.
Tests in `src/test/kotlin/com/pulseteam/desktop/data/desktop/`.

## 1. `DesktopAction.kt`

```kotlin
package com.pulseteam.desktop.data.desktop

/** What the user wants Pulse to do on the host PC. */
sealed class DesktopAction {
    /** Move + click at (x, y) on the primary screen (logical px). */
    data class Click(val x: Int, val y: Int, val button: MouseButton = MouseButton.Left) : DesktopAction()
    /** Type a string char-by-char into the currently-focused control. */
    data class Type(val text: String) : DesktopAction()
    /** Press a single key (use VK_* constants from java.awt.event.KeyEvent). */
    data class Key(val keyCode: Int) : DesktopAction()
    /** Press a key combo (e.g. Ctrl+C = [VK_CONTROL, VK_C]). Released in reverse order. */
    data class Hotkey(val keyCodes: List<Int>) : DesktopAction()
    /** Capture full screen to file (no risk; doesn't move cursor). */
    data class Screenshot(val dest: File) : DesktopAction()
}

enum class MouseButton { Left, Right, Middle }
```

**Notes:**
- All coordinates are **logical pixels** (CSS-px, NOT device pixels). `java.awt.Robot` handles DPI scaling since JDK 9.
- `Hotkey.keyCodes` is in press order; impl releases in reverse.

## 2. `ScreenCapture.kt`

```kotlin
package com.pulseteam.desktop.data.desktop

import java.awt.Rectangle
import java.awt.image.BufferedImage

interface ScreenCapture {
    /** Capture the full primary screen. */
    fun captureFull(): BufferedImage
    /** Capture a sub-region (logical pixels). */
    fun captureRegion(rect: Rectangle): BufferedImage
    /** Logical screen size (for region validation / UI). */
    fun screenSize(): Rectangle
    /** True if capture actually works on this OS (false on Wayland / no permission). */
    fun isAvailable(): Boolean
}

/** Real impl: java.awt.Robot + createMultiResolutionScreenCapture. */
class RobotScreenCapture : ScreenCapture { /* ... */ }

/** Test impl: returns a solid-color BufferedImage of the requested size. */
class FakeScreenCapture(
    private val w: Int = 1920,
    private val h: Int = 1080,
    private val color: Int = 0xFF202030.toInt(),
) : ScreenCapture { /* ... */ }
```

**Notes:**
- `RobotScreenCapture` uses `Toolkit.getDefaultToolkit().screenSize` for `screenSize()`.
- `RobotScreenCapture.captureFull()` calls `createMultiResolutionScreenCapture` and picks the high-res variant for HiDPI; if only one variant, uses that.
- `isAvailable()` returns `true` if a `Robot` can be created without `AWTException`. If `false`, `capture*` throws `IllegalStateException("screen capture unavailable on this platform")`.

## 3. `OcrEngine.kt`

```kotlin
package com.pulseteam.desktop.data.desktop

import java.awt.image.BufferedImage

data class OcrWord(
    val text: String,
    /** Bounding box in image-local coords (0,0 = top-left of the source image). */
    val left: Int, val top: Int, val width: Int, val height: Int,
    /** Confidence 0-100, or -1 if unknown. Words with conf < 60 are dropped. */
    val conf: Int,
)

data class OcrResult(
    /** All words concatenated with single spaces. May be empty. */
    val text: String,
    /** Word-level boxes (filtered to conf >= 60). */
    val words: List<OcrWord>,
)

interface OcrEngine {
    /** Run OCR on the given image. Default language = English. */
    suspend fun ocr(image: BufferedImage, lang: String = "eng"): OcrResult
    /** True if the tesseract binary is on PATH and runnable. */
    fun isAvailable(): Boolean
    /** Where the OCR impl logs output (for the Settings panel to surface). */
    fun statusMessage(): String
}

/**
 * Real impl: shells out to `tesseract <input> <output-base> -l <lang> tsv`.
 * Writes the image to a temp PNG, runs tesseract, parses TSV.
 * Logs stdout/stderr to ~/.pulse/logs/tesseract.log.
 * Time: 10s timeout, then proc.destroyForcibly() and return OcrResult("", emptyList()).
 */
class TesseractCliOcr : OcrEngine { /* ... */ }

/** Test impl: returns the canned words as-is. */
class FakeOcrEngine(
    private val canned: List<OcrWord> = emptyList(),
    private val available: Boolean = true,
    private val status: String = "fake",
) : OcrEngine { /* ... */ }
```

**Notes:**
- `TesseractCliOcr` does NOT download tesseract or tessdata. User must install system-wide. If `isAvailable()` returns false, the Settings panel shows the install command (`brew install tesseract` / `apt install tesseract-ocr` / download UB-Mannheim installer).
- TSV columns used: `text` (col 11), `left` (col 6), `top` (col 7), `width` (col 8), `height` (col 9), `conf` (col 10). Skip the header line. Drop words with `text.isBlank() || conf < 60`.
- `statusMessage()` returns e.g. `"tesseract 5.4.1"` on success or `"tesseract not found on PATH"` on failure.

## 4. `PcController.kt`

```kotlin
package com.pulseteam.desktop.data.desktop

interface PcController {
    fun click(x: Int, val y: Int, button: MouseButton = MouseButton.Left)
    fun typeText(text: String, perCharDelayMs: Long = 30)
    fun pressKey(keyCode: Int)
    fun pressHotkey(keyCodes: List<Int>)
    /** True if mouse/keyboard events actually deliver on this OS. */
    fun isAvailable(): Boolean
}

/** Real impl: java.awt.Robot. */
class RobotPcController : PcController { /* ... */ }

/** Test impl: records every call into a list for assertions. */
class FakePcController : PcController {
    data class Call(val op: String, val args: Map<String, Any?> = emptyMap())
    val calls: MutableList<Call> = mutableListOf()
    /* each method appends a Call then returns */
}
```

**Notes:**
- `RobotPcController` uses `Robot().apply { autoDelay = 40 }`.
- `typeText` uses `KeyEvent.getExtendedKeyCodeForChar(ch)`; `CHAR_UNDEFINED` chars are silently skipped (no exception).
- `pressHotkey` presses all but last, presses/releases last, releases the rest in reverse.
- `isAvailable()` true if Robot constructs without exception.

## 5. `VisionEngine.kt`

```kotlin
package com.pulseteam.desktop.data.desktop

/** Result of "find <target> on screen". */
data class ScreenMatch(
    val found: Boolean,
    /** Center of the matched word's bounding box (logical px), null if not found. */
    val x: Int? = null,
    val y: Int? = null,
    /** Confidence 0-100, null if not found. */
    val confidence: Int? = null,
    /** The exact text that was matched (may differ in case from the query). */
    val matchedText: String? = null,
)

/** Result of "describe what's on screen" — a short text summary. */
data class ScreenDescription(
    val text: String,
    val usedVisionModel: Boolean,  // true if image was sent to a VLM, false if OCR+text-LLM
)

interface VisionEngine {
    /** Capture the current screen and find the center of <target>. */
    suspend fun findOnScreen(target: String): ScreenMatch
    /** Capture the current screen and produce a short text description. */
    suspend fun describeScreen(): ScreenDescription
}

/** Real impl: OCR-only for findOnScreen (always works); OCR+text-LLM for describeScreen unless cloudVlm is set. */
class OcrFallbackVisionEngine(
    private val screen: ScreenCapture,
    private val ocr: OcrEngine,
    private val textLlm: TextLlm,            // see §6
    private val cloudVlm: CloudVlm?,         // see §6
) : VisionEngine { /* ... */ }

/** Test impl: returns canned values. */
class FakeVisionEngine(
    private val match: ScreenMatch = ScreenMatch(false),
    private val description: ScreenDescription = ScreenDescription("(fake)", false),
) : VisionEngine { /* ... */ }
```

**Notes:**
- `findOnScreen`: capture → ocr → case-insensitive contains/exact match for `target` → return `ScreenMatch(found=true, x=word.left+word.width/2, y=word.top+word.height/2, conf=word.conf, matchedText=word.text)`. If no match → `ScreenMatch(found=false)`.
- `describeScreen`: capture → ocr → if `cloudVlm != null && cloudVlm.isEnabled()`, send image + "Describe what you see" prompt → return `ScreenDescription(cloudText, usedVisionModel=true)`. Else, send ocrText + "Summarize what's on screen in 1-2 sentences" prompt to `textLlm` → return `ScreenDescription(textLlmResponse, usedVisionModel=false)`.
- Match priority for `findOnScreen`: exact word match (case-insensitive) > starts-with > contains. First hit wins.

## 6. TextLlm + CloudVlm (interfaces used by VisionEngine)

These should be **defined in VisionEngine.kt** (small interfaces, ~10 lines each) so the subagents don't have to agree on file boundaries:

```kotlin
package com.pulseteam.desktop.data.desktop

/** Text-only LLM — wraps the existing LlamaClient / openai text completion. */
interface TextLlm {
    suspend fun complete(prompt: String, maxTokens: Int = 256): String?
}

/** Opt-in cloud VLM — sends image+prompt to OpenAI/Claude. */
interface CloudVlm {
    fun isEnabled(): Boolean
    suspend fun describe(imagePngBytes: ByteArray, prompt: String): String?
}
```

Subagent B's `OcrFallbackVisionEngine` will receive concrete `TextLlm` / `CloudVlm` impls. For Phase 1, the **production TextLlm** wraps the existing `LlamaClient` (see `data/ai/LlamaClient.kt`). The **production CloudVlm** is a small `OpenAiCloudVlm(apiKey)` that POSTs to `https://api.openai.com/v1/chat/completions` with `gpt-4o-mini` (cheap + supports vision). If `apiKey.isBlank()`, `isEnabled()` returns false.

**Subagent B owns the concrete TextLlm / CloudVlm production classes** (in `data/desktop/`, separate files `LocalTextLlm.kt` and `OpenAiCloudVlm.kt`). These are the only files outside the 7 listed in the plan that Subagent B adds.

## 7. `SafetyGate.kt`

```kotlin
package com.pulseteam.desktop.data.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SafetyLevel { AlwaysConfirm /* Phase 2: OncePerCommand, Never */ }

data class SafetyState(
    val enabled: Boolean = true,
    val level: SafetyLevel = SafetyLevel.AlwaysConfirm,
    val pending: PendingAction? = null,  // non-null = dialog is showing
)

/** A pending action that needs the user's confirmation before executing. */
data class PendingAction(
    val action: DesktopAction,
    val summary: String,           // human-readable, e.g. "Click at (450, 220) — \"Save\"?"
    val screenshotPath: File?,     // PNG of the screen at the time the action was proposed, null if none
)

/**
 * The single source of truth for "is this action safe to execute without asking?".
 * UI watches [state] to show the confirm dialog when [SafetyState.pending] is non-null.
 */
class SafetyGate {
    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    /** Called by UI when settings change. */
    fun configure(enabled: Boolean, level: SafetyLevel) {
        _state.value = _state.value.copy(enabled = enabled, level = level)
    }

    /** Decide whether [action] needs confirmation. If yes, populate [state.pending] and return false. */
    fun request(action: DesktopAction, summary: String, screenshotPath: File? = null): Boolean {
        if (!_state.value.enabled) {
            return true  // desktop control disabled entirely — caller should treat as "allowed but no-op"? Actually no, caller should not call this. Just execute.
        }
        return when (_state.value.level) {
            SafetyLevel.AlwaysConfirm -> {
                _state.value = _state.value.copy(pending = PendingAction(action, summary, screenshotPath))
                false  // not yet allowed; wait for user to confirm
            }
        }
    }

    /** User clicked "Confirm" in the dialog. Clear pending and signal approval. */
    fun confirm() {
        _state.value = _state.value.copy(pending = null)
    }

    /** User clicked "Cancel" in the dialog. Clear pending and signal rejection. */
    fun cancel() {
        _state.value = _state.value.copy(pending = null)
    }
}
```

**Notes:**
- `Screenshot` and reading text (no host control) are NOT routed through `SafetyGate` — they execute immediately, no confirm.
- `Click`, `Type`, `Key`, `Hotkey` ARE routed through `SafetyGate.request(...)` and execute only after `confirm()`.

## 8. `DesktopController.kt` (the orchestrator)

```kotlin
package com.pulseteam.desktop.data.desktop

import java.io.File

/**
 * Single entry point for the UI. The Main wires up real impls (RobotScreenCapture,
 * TesseractCliOcr, RobotPcController, OcrFallbackVisionEngine, SafetyGate) and
 * passes [DesktopController] into CommandPalette + SettingsScreen.
 */
class DesktopController(
    private val screen: ScreenCapture,
    private val ocr: OcrEngine,
    private val pc: PcController,
    private val vision: VisionEngine,
    private val safety: SafetyGate,
    private val capturesDir: File = File(System.getProperty("user.home"), ".pulse/captures"),
) {
    /** Captures the screen and saves to capturesDir. Returns the file. */
    suspend fun takeScreenshot(): File

    /** Captures the screen, runs OCR, returns the text. No safety gate. */
    suspend fun readScreenText(): String

    /**
     * Captures the screen, runs vision.findOnScreen(target). If found, requests
     * SafetyGate confirmation with a preview screenshot. Returns a result the UI
     * can render. The UI calls [executeApproved] after the user confirms.
     */
    suspend fun proposeClickOnText(target: String): ProposeResult

    /**
     * Executes the currently-pending SafetyGate action. Called by UI after the user
     * clicks "Confirm" in ConfirmActionDialog. Returns true if a pending action was
     * found and executed, false otherwise.
     */
    suspend fun executeApproved(): Boolean

    /** Cancels any pending action. Called by UI on "Cancel". */
    fun cancelPending() { safety.cancel() }
}

sealed class ProposeResult {
    data class NeedsConfirmation(val summary: String, val previewPath: File) : ProposeResult()
    data class NotFound(val target: String) : ProposeResult()
    data class Unavailable(val reason: String) : ProposeResult()
    data class Executed(val message: String) : ProposeResult()  // for Screenshot / readScreenText
}
```

**Notes:**
- `proposeClickOnText` always shows confirm (Phase 1 = always-confirm). It does NOT execute the click. UI renders the dialog and calls `executeApproved` if user clicks Confirm.
- `executeApproved` looks at `safety.state.value.pending`, executes via `pc.click(...)` (or other action), then `safety.confirm()`. Returns true.
- `takeScreenshot` saves to `capturesDir/yyyy-MM-dd-HHmmss.png`, returns the file. Creates dir if needed.
- `readScreenText` captures → OCR → returns `ocr.text`. No side effects.

## 9. `ui/desktop/ConfirmActionDialog.kt`

```kotlin
package com.pulseteam.desktop.ui.desktop

import androidx.compose.runtime.Composable
import com.pulseteam.desktop.data.desktop.PendingAction

/**
 * Modal that shows when SafetyGate.pending is non-null.
 * Renders:
 *   - Backdrop (PulseColors.Drop, click-to-cancel)
 *   - Centered card with: action summary, preview screenshot (if any), [Cancel] [Confirm] buttons
 *   - Confirm calls onConfirm(); Cancel calls onCancel()
 *   - Squared edges, Tokyo Night palette.
 */
@Composable
fun ConfirmActionDialog(
    pending: PendingAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
)
```

**Notes:**
- Width 560dp, max height 640dp, vertical layout.
- The preview screenshot is shown via `java.awt.imageio.ImageIO.read(file)` → `Image(bitmap.asImageBitmap())` from compose ui.
- Buttons: Cancel (left, secondary), Confirm (right, primary = PulseColors.Accent).
- Esc key = Cancel. Enter key = Confirm.

## 10. Settings additions (`AppSettings.kt`)

Add 4 fields to `AppSettings`:
```kotlin
data class AppSettings(
    /* ... existing ... */
    val desktopEnabled: Boolean = false,
    val safetyLevel: SafetyLevel = SafetyLevel.AlwaysConfirm,
    val visionModel: VisionModel = VisionModel.OcrOnly,
    val cloudApiKey: String = "",  // OpenAI; empty = not configured
)

enum class VisionModel { OcrOnly, OpenAiCloud }
```

Update `load()` + `persist()` to round-trip these 4 fields through the properties file. Existing `~/.pulse/settings.properties` from earlier Pulse versions won't have these keys → defaults apply → no breakage.

## 11. Command palette additions (`CommandPalette.kt`)

Add 3 new `PaletteAction` variants:
```kotlin
sealed class PaletteAction {
    /* ... existing ... */
    object TakeScreenshot : PaletteAction()
    object ReadScreenText : PaletteAction()
    data class ClickOnText(val target: String) : PaletteAction()  // user types target via palette input
}
```

`buildCommands(notes)` adds 3 entries under section "Desktop":
```kotlin
out += PaletteCommand("screenshot", "Скриншот (save to ~/.pulse/captures)", "Desktop", PaletteAction.TakeScreenshot, "Ctrl ⇧ S")
out += PaletteCommand("read-screen", "Что на экране? (OCR)", "Desktop", PaletteAction.ReadScreenText)
out += PaletteCommand("click-on-text", "Кликни: …", "Desktop", PaletteAction.ClickOnText(""))  // target is user input
```

For "Кликни: …" — main agent's `Main.kt` handles the `PaletteAction.ClickOnText(target)` by showing a small inline input modal. Subagent B should not implement the inline input — just the action data class.

## 12. Settings additions (`SettingsScreen.kt`)

Add `SettingsTab.Desktop` to the enum. Add `DesktopPanel` composable. Add `ActivityRail` + `SettingsNav` entries. Add the case to the `when` block in SettingsScreen.

`DesktopPanel` content:
- Toggle: "Enable desktop control" → updates `AppSettingsStore.desktopEnabled`
- Row: "Safety level" → shows "Always confirm" (locked in MVP, "More options in a future update" hint)
- Row: "Vision model" → dropdown between `OcrOnly` and `OpenAiCloud`
- Row (only if OpenAiCloud): "OpenAI API key" → text input (masked) → updates `AppSettingsStore.cloudApiKey`
- Status row: `OcrEngine.statusMessage()` + dot (green/red)
- Status row: `PcController.isAvailable()` + dot (green/red)
- Status row: `ScreenCapture.isAvailable()` + dot (green/red)

## 13. Main.kt wiring

Subagent B adds:
- `val desktop = remember { DesktopController(...) }` — pass real impls
- Pass `desktop` to `CommandPalette(onAction = { ... handle new actions ... })`
- Pass `desktop` to `SettingsScreen(desktop = desktop, ...)`
- Render `ConfirmActionDialog` when `safety.state.pending != null` (UI layer listens to `desktop.safetyGateState()`)

## Anti-patterns / don'ts

- **Don't** import `androidx.compose.foundation.shape.RoundedCornerShape` — use `RectangleShape` everywhere.
- **Don't** use `Icons.Default.*` that doesn't already exist in `materialIconsExtended` (use only what's already used in the codebase, e.g. `Search`, `Memory`, `Person`, `Tune`, `GraphicEq`).
- **Don't** add new Gradle dependencies (no Tess4J, no OpenAI client lib — use `HttpURLConnection` like WhisperTranscriber does).
- **Don't** add emoji to UI strings.
- **Don't** modify `core_rules.js` or any lesside files. This is Pulse Desktop only.
- **Don't** auto-trigger downloads in `init {}` — lazy on first use.
- **Don't** swallow exceptions silently — log via `PulseLogger.error(...)` and surface a clear error to the user.
- **Don't** call `java.awt.Toolkit.getDefaultToolkit().screenSize` from a non-UI thread — wrap in `withContext(Dispatchers.IO)`.
- **Don't** put `Random` / `Thread.sleep` / `println` in production paths.

## File paths summary (for both subagents)

```
NEW files (Subagent A):
  src/main/kotlin/com/pulseteam/desktop/data/desktop/DesktopAction.kt
  src/main/kotlin/com/pulseteam/desktop/data/desktop/ScreenCapture.kt
  src/main/kotlin/com/pulseteam/desktop/data/desktop/OcrEngine.kt
  src/main/kotlin/com/pulseteam/desktop/data/desktop/PcController.kt
  src/main/kotlin/com/pulseteam/desktop/data/desktop/SafetyGate.kt
  src/test/kotlin/com/pulseteam/desktop/data/desktop/PcControllerTest.kt
  src/test/kotlin/com/pulseteam/desktop/data/desktop/SafetyGateTest.kt
  src/test/kotlin/com/pulseteam/desktop/data/desktop/OcrEngineTest.kt
  src/test/kotlin/com/pulseteam/desktop/data/desktop/ScreenCaptureTest.kt

NEW files (Subagent B):
  src/main/kotlin/com/pulseteam/desktop/data/desktop/VisionEngine.kt            (incl. TextLlm + CloudVlm interfaces)
  src/main/kotlin/com/pulseteam/desktop/data/desktop/LocalTextLlm.kt
  src/main/kotlin/com/pulseteam/desktop/data/desktop/OpenAiCloudVlm.kt
  src/main/kotlin/com/pulseteam/desktop/data/desktop/DesktopController.kt
  src/main/kotlin/com/pulseteam/desktop/ui/desktop/ConfirmActionDialog.kt
  src/test/kotlin/com/pulseteam/desktop/data/desktop/DesktopControllerTest.kt
  src/test/kotlin/com/pulseteam/desktop/data/desktop/VisionEngineTest.kt

MODIFIED files (Subagent B):
  src/main/kotlin/com/pulseteam/desktop/data/settings/AppSettings.kt   (+4 fields, +1 enum, load/persist)
  src/main/kotlin/com/pulseteam/desktop/ui/palette/CommandPalette.kt  (+3 actions, +3 commands)
  src/main/kotlin/com/pulseteam/desktop/ui/settings/SettingsScreen.kt  (+1 tab, +1 panel, +1 rail entry)
  src/main/kotlin/com/pulseteam/desktop/Main.kt                       (wire DesktopController, render ConfirmActionDialog)

NOT MODIFIED (other subagent's scope):
  All other data/* and ui/* files
  build.gradle.kts
  src/main/resources/*
```
