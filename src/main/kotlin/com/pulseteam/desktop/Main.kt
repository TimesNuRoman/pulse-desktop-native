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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pulseteam.desktop.data.auth.AuthApi
import com.pulseteam.desktop.data.auth.AuthSession
import com.pulseteam.desktop.data.auth.PasswordCache
import com.pulseteam.desktop.data.log.PulseLogger
import com.pulseteam.desktop.data.notes.NoteLink
import com.pulseteam.desktop.data.notes.NoteRepository
import com.pulseteam.desktop.data.sync.SyncEngine
import com.pulseteam.desktop.ui.auth.AuthScreen
import com.pulseteam.desktop.ui.auth.PasswordDialog
import com.pulseteam.desktop.ui.chat.ChatScreen
import com.pulseteam.desktop.ui.chat.ChatViewModel
import com.pulseteam.desktop.ui.common.ErrorBoundary
import com.pulseteam.desktop.ui.notes.NoteEditorScreen
import com.pulseteam.desktop.ui.notes.NotesViewModel
import com.pulseteam.desktop.ui.palette.CommandPalette
import com.pulseteam.desktop.ui.settings.SettingsScreen
import com.pulseteam.desktop.ui.theme.PulseColors
import com.pulseteam.desktop.ui.theme.PulseTheme
import kotlinx.coroutines.launch

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

    val scope = rememberCoroutineScope()

    var paletteOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
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

    val chatViewModel = remember(notesViewModel) {
        ChatViewModel(
            onNotesCreated = { links: List<NoteLink> ->
                links.forEach { link ->
                    notesViewModel.createFromChat(link.title, link.body ?: "")
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        NoteRepository.list()
        notesViewModel.refresh()
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
                    .background(PulseColors.Bg),
            ) {
                if (session == null) {
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
                                lastEvent = if (want) "Voice: recording… (Whisper.cpp wires up in v0.5)"
                                            else "Voice: stopped"
                            },
                            onAttachFile = { lastEvent = "Attach: file picker opens in v0.5" },
                            onToggleWeb = { want ->
                                isWebSearchOn = want
                                lastEvent = if (want) "Web search: ON (uses /v1/web/search when b\u0252ck ready)"
                                            else "Web search: OFF"
                            },
                            onSyncNow = {
                                scope.launch {
                                    val pw = PasswordCache.get() ?: return@launch
                                    try { syncEngine.fullSync(pw, authSession.token ?: "") } finally { pw.fill('\u0000') }
                                }
                            },
                            lastEvent = lastEvent,
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
                                lastEvent = if (want) "Voice: recording… (Whisper.cpp wires up in v0.5)"
                                            else "Voice: stopped"
                            },
                            onAttachFile = { lastEvent = "Attach: file picker opens in v0.5" },
                            onToggleWeb = { want ->
                                isWebSearchOn = want
                                lastEvent = if (want) "Web search: ON (uses /v1/web/search when b\u0252ck ready)"
                                            else "Web search: OFF"
                            },
                            onSyncNow = {
                                scope.launch {
                                    val pw = PasswordCache.get() ?: return@launch
                                    try { syncEngine.fullSync(pw, authSession.token ?: "") } finally { pw.fill('\u0000') }
                                }
                            },
                            lastEvent = lastEvent,
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
                                    is com.pulseteam.desktop.ui.palette.PaletteAction.OpenSettings ->
                                        settingsOpen = true
                                    is com.pulseteam.desktop.ui.palette.PaletteAction.NewChat ->
                                        selectedChatId = "chat-${System.currentTimeMillis()}"
                                    is com.pulseteam.desktop.ui.palette.PaletteAction.NewNote ->
                                        notesViewModel.createNote()
                                    is com.pulseteam.desktop.ui.palette.PaletteAction.OpenNote -> {
                                        notesViewModel.open(action.noteId)
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
                        )
                    }
                }
            }
            }  // ErrorBoundary
        }
    }
}
