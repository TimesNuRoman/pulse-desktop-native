// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — main entry. Single Window, dark theme, 1280x800.
// v0.3.0: Auth + Sync wired. AuthSession gates the entire UI; SyncEngine
// pushes/pulls encrypted notes via api.ownlocalml.com.
package com.pulseteam.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pulseteam.desktop.data.ai.AiEngine
import com.pulseteam.desktop.data.ai.LlamaClient
import com.pulseteam.desktop.data.ai.LlamaEngine
import com.pulseteam.desktop.data.ai.LlamaServerProcess
import com.pulseteam.desktop.data.ai.LocalMockEngine
import com.pulseteam.desktop.data.ai.ModelsRepository
import com.pulseteam.desktop.data.ai.RuntimeDownloader
import com.pulseteam.desktop.data.auth.AuthApi
import com.pulseteam.desktop.data.auth.AuthSession
import com.pulseteam.desktop.data.auth.PasswordCache
import com.pulseteam.desktop.data.desktop.DesktopController
import com.pulseteam.desktop.data.desktop.LocalTextLlm
import com.pulseteam.desktop.data.desktop.OcrFallbackVisionEngine
import com.pulseteam.desktop.data.desktop.OcrEngine
import com.pulseteam.desktop.data.desktop.OpenAiCloudVlm
import com.pulseteam.desktop.data.desktop.PcController
import com.pulseteam.desktop.data.desktop.PendingAction
import com.pulseteam.desktop.data.desktop.ProposeResult
import com.pulseteam.desktop.data.desktop.RobotPcController
import com.pulseteam.desktop.data.desktop.RobotScreenCapture
import com.pulseteam.desktop.data.desktop.SafetyGate
import com.pulseteam.desktop.data.desktop.ScreenCapture
import com.pulseteam.desktop.data.desktop.TesseractCliOcr
import com.pulseteam.desktop.data.desktop.VisionEngine
import com.pulseteam.desktop.data.log.PulseLogger
import com.pulseteam.desktop.data.settings.AppSettingsStore
import com.pulseteam.desktop.data.notes.NoteLink
import com.pulseteam.desktop.data.notes.NoteRepository
import com.pulseteam.desktop.data.skills.SkillRepository
import com.pulseteam.desktop.data.sync.SyncEngine
import com.pulseteam.desktop.data.update.UpdateChecker
import com.pulseteam.desktop.data.update.UpdateInfo
import com.pulseteam.desktop.data.update.UpdateStatus
import com.pulseteam.desktop.data.voice.WhisperTranscriber
import com.pulseteam.desktop.data.web.WebSearch
import com.pulseteam.desktop.ui.auth.AuthScreen
import com.pulseteam.desktop.ui.auth.PasswordDialog
import com.pulseteam.desktop.ui.chat.ChatScreen
import com.pulseteam.desktop.ui.chat.ChatViewModel
import com.pulseteam.desktop.ui.common.ErrorBoundary
import com.pulseteam.desktop.ui.notes.NoteEditorScreen
import com.pulseteam.desktop.ui.notes.NotesViewModel
import com.pulseteam.desktop.ui.onboarding.OnboardingScreen
import com.pulseteam.desktop.ui.palette.CommandPalette
import com.pulseteam.desktop.ui.palette.PaletteAction
import com.pulseteam.desktop.ui.desktop.ConfirmActionDialog
import com.pulseteam.desktop.ui.desktop.ClickTargetDialog
import com.pulseteam.desktop.ui.settings.SettingsScreen
import com.pulseteam.desktop.ui.skills.SkillsScreen
import com.pulseteam.desktop.ui.theme.PulseColors
import com.pulseteam.desktop.ui.theme.PulseTheme
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    // Install JVM-wide crash handler first thing — every uncaught exception
    // (including those that escape Compose recomposition) lands in the log.
    PulseLogger.installCrashHandler()
    PulseLogger.info("Pulse starting", mapOf("version" to "1.0.0-rc", "java" to System.getProperty("java.version")))

    val windowState = rememberWindowState(
        size = DpSize(1280.dp, 800.dp),
        position = WindowPosition.PlatformDefault,
    )

    val authSession = remember { AuthSession() }
    val authApi = remember { AuthApi() }
    val syncEngine = remember {
        SyncEngine(authApi).apply {
            currentSessionProvider = {
                object : SyncEngine.AuthSessionLike {
                    override val isAuthenticated: Boolean = authSession.isAuthenticated
                    override val token: String? = authSession.token
                }
            }
            passwordProvider = { PasswordCache.get() }
        }
    }
    val session by authSession.state.collectAsState()
    val syncState by syncEngine.state.collectAsState()

    // AI engine: real llama-server with mock fallback when server can't start
    // (no model downloaded yet, no runtime, etc.). Created once, persists.
    val modelsRepo = remember { ModelsRepository() }
    val runtimeDownloader = remember { RuntimeDownloader() }
    val llamaServer = remember { LlamaServerProcess() }
    val llamaClient = remember { LlamaClient() }
    val aiEngine: AiEngine = remember {
        LlamaEngine(
            repository = modelsRepo,
            server = llamaServer,
            client = llamaClient,
            fallback = LocalMockEngine(),
        )
    }
    // Onboarding gate: shown until both runtime and at least one model
    // are installed. Flips to true automatically when both ready.
    var onboardingComplete by remember { mutableStateOf(false) }
    val modelEntries by modelsRepo.entries().collectAsState()
    LaunchedEffect(modelEntries) {
        val runtimeOk = runtimeDownloader.isInstalled()
        val modelOk = modelEntries.any { it.installed }
        if (runtimeOk && modelOk) {
            onboardingComplete = true
            PulseLogger.info("Onboarding complete (runtime + model present)")
        }
    }

    val scope = rememberCoroutineScope()

    var paletteOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    // Inline target input for the "Кликни: …" palette command. When true,
    // the ClickTargetDialog is rendered on top. User types target → onSubmit
    // dispatches proposeClickOnText(target).
    var clickTargetDialogOpen by remember { mutableStateOf(false) }
    var selectedChatId by remember { mutableStateOf("welcome") }
    // Track whether the user has unlocked the sync key this session.
    // Reset to true once they Skip so we don't keep nagging.
    var unlockDismissed by remember { mutableStateOf(false) }
    // Voice / web-search toggles live in Main so they survive note switches.
    var isListening by remember { mutableStateOf(false) }
    var isWebSearchOn by remember { mutableStateOf(false) }
    var lastEvent by remember { mutableStateOf<String?>(null) }

    val notesViewModel = remember(authSession) { NotesViewModel(syncEngine = syncEngine, authSession = authSession) }
    val notes by notesViewModel.notes.collectAsState()
    val openNote by notesViewModel.openNote.collectAsState()
    val backlinks by notesViewModel.backlinks.collectAsState()

    // Skills repository. Auto-loads ~/.pulse/skills.json on first read.
    // Seeded with two example skills on a fresh install so the user
    // sees how the feature works.
    val skillRepo = remember { SkillRepository() }
    var skillsOpen by remember { mutableStateOf(false) }

    // Update checker. Polls ownlocalml.com/updates/windows-kotlin.json
    // 5s after the app starts. If a newer version is published, the
    // topbar shows a "v1.0.1 available — Download" pill. Click opens
    // the URL in the system browser (we don't do in-place patching).
    val updateChecker = remember { UpdateChecker() }
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5_000)  // let the app finish first frame
        updateStatus = UpdateStatus.Checking
        updateStatus = updateChecker.check()
    }

    val chatViewModel = remember(notesViewModel, aiEngine, skillRepo) {
        ChatViewModel(
            engine = aiEngine,
            webSearch = WebSearch(),
            isWebSearchEnabled = { isWebSearchOn },
            skillRepo = skillRepo,
            onNotesCreated = { links: List<NoteLink> ->
                links.forEach { link ->
                    notesViewModel.createFromChat(link.title, link.body ?: "")
                }
            },
        )
    }

    // Whisper transcriber for voice input. Lazily downloads whisper-cli
    // and the ggml-tiny model on first use; the user sees a status
    // message while the binary or model comes down. After that, every
    // voice click is a synchronous call into whisper.cpp.
    val whisper = remember { WhisperTranscriber() }

    // ---------------------------------------------------------------
    // Desktop control (Phase 1): screen capture + OCR + PC interaction.
    // Real impls (RobotScreenCapture + TesseractCliOcr + RobotPcController).
    // SafetyGate is configured from AppSettings via LaunchedEffect below.
    // ---------------------------------------------------------------
    val screen: ScreenCapture = remember { RobotScreenCapture() }
    val ocr: OcrEngine = remember { TesseractCliOcr() }
    val pc: PcController = remember { RobotPcController() }
    val safety: SafetyGate = remember { SafetyGate() }
    val textLlm = remember(llamaClient) {
        LocalTextLlm(llamaClient = llamaClient) { AppSettingsStore.state.value.activeModelId }
    }
    val cloudVlm = remember {
        OpenAiCloudVlm(apiKeyProvider = { AppSettingsStore.state.value.cloudApiKey })
    }
    val vision: VisionEngine = remember(screen, ocr, textLlm, cloudVlm) {
        OcrFallbackVisionEngine(
            screen = screen,
            ocr = ocr,
            textLlm = textLlm,
            cloudVlm = cloudVlm,
        )
    }
    val desktop: DesktopController = remember(screen, ocr, pc, vision, safety) {
        DesktopController(
            screen = screen,
            ocr = ocr,
            pc = pc,
            vision = vision,
            safety = safety,
        )
    }
    // Sync SafetyGate with AppSettings whenever they change.
    val settings by AppSettingsStore.state.collectAsState()
    LaunchedEffect(settings.desktopEnabled, settings.safetyLevel) {
        safety.configure(settings.desktopEnabled, settings.safetyLevel)
    }
    val safetyState by safety.state.collectAsState()
    val pending: PendingAction? = safetyState.pending

    // First-run hint: if desktop control is enabled but tesseract is missing,
    // surface a chat-level warning so the user knows what to install.
    LaunchedEffect(settings.desktopEnabled, desktop.ocrAvailable) {
        if (settings.desktopEnabled && !desktop.ocrAvailable) {
            lastEvent = "Desktop control: tesseract not found. ${desktop.ocrStatus} (Settings → Desktop)"
        }
    }

    // Pre-create captures dir on first composition so the Settings panel
    // doesn't show a missing-folder error.
    LaunchedEffect(Unit) {
        try {
            val d = java.io.File(System.getProperty("user.home"), ".pulse/captures")
            if (!d.exists()) d.mkdirs()
        } catch (_: Throwable) { /* best effort */ }
    }

    // Hotkey state: Ctrl+Shift+S triggers a screenshot from anywhere in the app.
    // We attach a single onPreviewKeyEvent to the root Box (see Window block).

    LaunchedEffect(Unit) {
        NoteRepository.list()
        notesViewModel.refresh()
        // Auto-trigger runtime download if missing (saves the user a click
        // on first run, but they still need to manually pick a model).
        if (!runtimeDownloader.isInstalled()) {
            PulseLogger.info("Auto-starting runtime download on first launch")
            runtimeDownloader.startDownload()
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "Pulse",
    ) {
        PulseTheme {
            ErrorBoundary(
                onError = { PulseLogger.error("UI crashed", it) },
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PulseColors.Bg)
                    .onPreviewKeyEvent { event ->
                        // Global hotkey: Ctrl+Shift+S = screenshot (Pulse "team" quick-capture).
                        // Suppress when dialogs are open so the user can't accidentally fire
                        // a screenshot mid-confirmation.
                        if (event.type == KeyEventType.KeyDown
                            && event.key == Key.S
                            && event.isCtrlPressed
                            && event.isShiftPressed
                            && !paletteOpen
                            && !clickTargetDialogOpen
                            && pending == null
                        ) {
                            if (!settings.desktopEnabled) {
                                lastEvent = "Desktop control disabled (Settings → Desktop)"
                            } else {
                                scope.launch {
                                    lastEvent = try {
                                        val f = desktop.takeScreenshot()
                                        "Screenshot: ${f.name} (${f.length() / 1024} KB)"
                                    } catch (t: Throwable) {
                                        "Screenshot failed: ${t.message}"
                                    }
                                }
                            }
                            true
                        } else {
                            false
                        }
                    },
            ) {
                if (!onboardingComplete) {
                    OnboardingScreen(
                        repository = modelsRepo,
                        runtime = runtimeDownloader,
                        onReady = { onboardingComplete = true },
                    )
                } else if (session == null) {
                    AuthScreen(
                        session = authSession,
                        api = authApi,
                        onAuthenticated = { /* state will flip automatically via collectAsState */ },
                    )
                } else {
                    // Ask-password dialog: show on first authenticated frame
                    // unless the user already unlocked or skipped this session.
                    if (!PasswordCache.isUnlocked && !unlockDismissed) {
                        PasswordDialog(
                            email = session!!.user.email,
                            onUnlock = { unlockDismissed = true },
                            onSkip = { unlockDismissed = true },
                        )
                    }
                    if (openNote == null) {
                        ChatScreen(
                            chatId = selectedChatId,
                            notes = notes,
                            selectedNoteId = "",
                            onSelectNote = { notesViewModel.open(it) },
                            onNewNote = { notesViewModel.createNote() },
                            onOpenNoteByTitle = { notesViewModel.openByTitle(it) },
                            onOpenSettings = { settingsOpen = true },
                            onOpenPalette = { paletteOpen = true },
                            chatViewModel = chatViewModel,
                            userEmail = session?.user?.email,
                            syncStatus = syncState,
                            onSignOut = { authSession.logout() },
                            currentNote = null,
                            backlinks = emptyList(),
                            recentNotes = notes.take(3),
                            onOpenBacklink = { notesViewModel.open(it) },
                            isListening = isListening,
                            isWebSearchOn = isWebSearchOn,
                            onToggleVoice = { want ->
                                isListening = want
                                if (want) {
                                    val audio = pickAudioFile()
                                    if (audio == null) {
                                        lastEvent = "Voice: cancelled"
                                    } else {
                                        lastEvent = "Voice: transcribing ${audio.name}…"
                                        scope.launch {
                                            val transcript = whisper.transcribe(
                                                audio,
                                                listener = { phase, frac, msg ->
                                                    if (msg != null) lastEvent = "Voice: $msg"
                                                },
                                            )
                                            lastEvent = if (transcript != null) {
                                                "Voice: \"${transcript.take(80)}\""
                                            } else {
                                                "Voice: failed (see ~/.pulse/logs/whisper-cli.log)"
                                            }
                                        }
                                    }
                                } else {
                                    lastEvent = "Voice: stopped"
                                }
                            },
                            onAttachFile = {
                                val f = pickAnyFile()
                                lastEvent = if (f != null) "Attached: ${f.name} (${f.length() / 1024} KB)" else "Attach cancelled"
                            },
                            onToggleWeb = { want ->
                                isWebSearchOn = want
                                lastEvent = if (want) "Web search: ON" else "Web search: OFF"
                            },
                            onSyncNow = {
                                scope.launch {
                                    val pw = PasswordCache.get() ?: return@launch
                                    try { syncEngine.fullSync(pw, authSession.token ?: "") } finally { pw.fill('\u0000') }
                                }
                            },
                            lastEvent = lastEvent,
                            updateInfo = (updateStatus as? UpdateStatus.Available)?.info,
                            onDownloadUpdate = {
                                val info = (updateStatus as? UpdateStatus.Available)?.info
                                if (info != null) updateChecker.openDownload(info.url)
                            },
                        )
                    } else {
                        ChatScreen(
                            chatId = selectedChatId,
                            notes = notes,
                            selectedNoteId = openNote!!.id,
                            onSelectNote = { notesViewModel.open(it) },
                            onNewNote = { notesViewModel.createNote() },
                            onOpenNoteByTitle = { notesViewModel.openByTitle(it) },
                            onOpenSettings = { settingsOpen = true },
                            onOpenPalette = { paletteOpen = true },
                            chatViewModel = chatViewModel,
                            userEmail = session?.user?.email,
                            syncStatus = syncState,
                            onSignOut = { authSession.logout() },
                            currentNote = openNote,
                            backlinks = backlinks,
                            onOpenBacklink = { notesViewModel.open(it) },
                            isListening = isListening,
                            isWebSearchOn = isWebSearchOn,
                            onToggleVoice = { want ->
                                isListening = want
                                if (want) {
                                    val audio = pickAudioFile()
                                    if (audio == null) {
                                        lastEvent = "Voice: cancelled"
                                    } else {
                                        lastEvent = "Voice: transcribing ${audio.name}…"
                                        scope.launch {
                                            val transcript = whisper.transcribe(
                                                audio,
                                                listener = { phase, frac, msg ->
                                                    if (msg != null) lastEvent = "Voice: $msg"
                                                },
                                            )
                                            lastEvent = if (transcript != null) {
                                                "Voice: \"${transcript.take(80)}\""
                                            } else {
                                                "Voice: failed (see ~/.pulse/logs/whisper-cli.log)"
                                            }
                                        }
                                    }
                                } else {
                                    lastEvent = "Voice: stopped"
                                }
                            },
                            onAttachFile = {
                                val f = pickAnyFile()
                                lastEvent = if (f != null) "Attached: ${f.name} (${f.length() / 1024} KB)" else "Attach cancelled"
                            },
                            onToggleWeb = { want ->
                                isWebSearchOn = want
                                lastEvent = if (want) "Web search: ON" else "Web search: OFF"
                            },
                            onSyncNow = {
                                scope.launch {
                                    val pw = PasswordCache.get() ?: return@launch
                                    try { syncEngine.fullSync(pw, authSession.token ?: "") } finally { pw.fill('\u0000') }
                                }
                            },
                            lastEvent = lastEvent,
                            updateInfo = (updateStatus as? UpdateStatus.Available)?.info,
                            onDownloadUpdate = {
                                val info = (updateStatus as? UpdateStatus.Available)?.info
                                if (info != null) updateChecker.openDownload(info.url)
                            },
                            centerContent = {
                                NoteEditorScreen(
                                    note = openNote!!,
                                    onClose = { notesViewModel.close() },
                                    onUpdate = { title, body -> notesViewModel.updateNote(openNote!!.id, title, body) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            },
                        )
                    }

                    if (paletteOpen) {
                        CommandPalette(
                            notes = notes,
                            onDismiss = { paletteOpen = false },
                            onAction = { action ->
                                when (action) {
                                    is PaletteAction.OpenSettings ->
                                        settingsOpen = true
                                    is PaletteAction.OpenSkills ->
                                        skillsOpen = true
                                    is PaletteAction.NewChat ->
                                        selectedChatId = "chat-${System.currentTimeMillis()}"
                                    is PaletteAction.NewNote ->
                                        notesViewModel.createNote()
                                    is PaletteAction.OpenNote -> {
                                        notesViewModel.open(action.noteId)
                                    }
                                    is PaletteAction.TakeScreenshot -> {
                                        if (!settings.desktopEnabled) {
                                            lastEvent = "Desktop control disabled (Settings → Desktop)"
                                        } else {
                                            scope.launch {
                                                lastEvent = try {
                                                    val f = desktop.takeScreenshot()
                                                    "Screenshot: ${f.name} (${f.length() / 1024} KB)"
                                                } catch (t: Throwable) {
                                                    "Screenshot failed: ${t.message}"
                                                }
                                            }
                                        }
                                    }
                                    is PaletteAction.ReadScreenText -> {
                                        if (!settings.desktopEnabled) {
                                            lastEvent = "Desktop control disabled (Settings → Desktop)"
                                        } else if (!desktop.ocrAvailable) {
                                            lastEvent = "OCR unavailable: ${desktop.ocrStatus}"
                                        } else {
                                            scope.launch {
                                                lastEvent = try {
                                                    val text = desktop.readScreenText()
                                                    if (text.isBlank()) "Screen: (no text recognised)"
                                                    else "Screen: \"${text.take(120)}\""
                                                } catch (t: Throwable) {
                                                    "Screen read failed: ${t.message}"
                                                }
                                            }
                                        }
                                    }
                                    is PaletteAction.ClickOnText -> {
                                        if (!settings.desktopEnabled) {
                                            lastEvent = "Desktop control disabled (Settings → Desktop)"
                                        } else if (action.target.isBlank()) {
                                            // Open the inline target input dialog; we dispatch
                                            // the click once the user submits.
                                            clickTargetDialogOpen = true
                                        } else {
                                            scope.launch {
                                                lastEvent = when (val r = desktop.proposeClickOnText(action.target)) {
                                                    is ProposeResult.NeedsConfirmation -> "Click: confirm dialog opened"
                                                    is ProposeResult.NotFound -> "Click: \"${r.target}\" not found on screen"
                                                    is ProposeResult.Unavailable -> "Click: ${r.reason}"
                                                    is ProposeResult.Executed -> "Click: ${r.message}"
                                                }
                                            }
                                        }
                                    }
                                    else -> Unit
                                }
                                paletteOpen = false
                            },
                        )
                    }

                    if (settingsOpen) {
                        SettingsScreen(
                            onDismiss = { settingsOpen = false },
                            userEmail = session?.user?.email,
                            onSignOut = { authSession.logout() },
                            onUnlockSync = {
                                PasswordCache.clear()
                                unlockDismissed = false
                            },
                            modelsRepo = modelsRepo,
                            runtimeDownloader = runtimeDownloader,
                            whisper = whisper,
                            desktop = desktop,
                        )
                    }

                    if (skillsOpen) {
                        SkillsScreen(
                            repository = skillRepo,
                            onDismiss = { skillsOpen = false },
                        )
                    }

                    // Safety confirm dialog (renders on top of everything).
                    if (pending != null) {
                        ConfirmActionDialog(
                            pending = pending,
                            onConfirm = { scope.launch { desktop.executeApproved() } },
                            onCancel = { desktop.cancelPending() },
                        )
                    }

                    // Inline target input for the "Кликни: …" palette command.
                    if (clickTargetDialogOpen) {
                        ClickTargetDialog(
                            initial = "",
                            onSubmit = { target ->
                                clickTargetDialogOpen = false
                                if (!settings.desktopEnabled) {
                                    lastEvent = "Desktop control disabled (Settings → Desktop)"
                                } else {
                                    scope.launch {
                                        lastEvent = when (val r = desktop.proposeClickOnText(target)) {
                                            is ProposeResult.NeedsConfirmation -> "Click: confirm dialog opened"
                                            is ProposeResult.NotFound -> "Click: \"${r.target}\" not found on screen"
                                            is ProposeResult.Unavailable -> "Click: ${r.reason}"
                                            is ProposeResult.Executed -> "Click: ${r.message}"
                                        }
                                    }
                                }
                            },
                            onCancel = { clickTargetDialogOpen = false },
                        )
                    }
                }
            }
            }  // ErrorBoundary
        }
    }
}

/** Open a native file picker, return selected File or null on cancel. */
private fun pickAnyFile(): File? {
    return try {
        val dlg = FileDialog(null as java.awt.Frame?, "Attach file", FileDialog.LOAD)
        dlg.isVisible = true
        val dir = dlg.directory
        val name = dlg.file
        if (dir != null && name != null) File(dir, name) else null
    } catch (t: Throwable) {
        PulseLogger.warn("File picker failed", mapOf("err" to t.message))
        null
    }
}

/** Open a native file picker filtered to common audio extensions. */
private fun pickAudioFile(): File? {
    return try {
        // java.awt.FileDialog doesn't support extension filters on all
        // platforms, but the dialog naturally shows supported formats. We
        // do a manual post-filter.
        val dlg = FileDialog(null as java.awt.Frame?, "Attach audio", FileDialog.LOAD)
        dlg.isVisible = true
        val dir = dlg.directory
        val name = dlg.file
        val file = if (dir != null && name != null) File(dir, name) else null
        if (file != null) {
            val ext = file.extension.lowercase()
            val supported = setOf("wav", "mp3", "m4a", "ogg", "flac", "opus", "webm", "mp4")
            if (ext !in supported) {
                PulseLogger.warn("Audio file has unsupported extension", mapOf("file" to file.name, "ext" to ext))
                null
            } else file
        } else null
    } catch (t: Throwable) {
        PulseLogger.warn("Audio picker failed", mapOf("err" to t.message))
        null
    }
}
