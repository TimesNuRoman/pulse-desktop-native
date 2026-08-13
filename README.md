# Pulse Desktop

Local-first notes + chat + AI workspace. One window, three panels, all on your machine.

- Notes on the left, chat in the middle, context on the right.
- Everything is Markdown. Everything links. Everything is searchable.
- No account required for local use. No telemetry. No phone-home.
- Optional end-to-end encrypted sync (you keep the keys, server sees only ciphertext).

## Screenshot

<p align="center">
  <img src="docs/screenshots/01-main.png" alt="Pulse Desktop main view" width="800"/>
</p>

## Features

- **Local SQLite + FTS4** notes with full-text search and `[[wiki-style]]` backlinks
- **AI chat** with a local engine (Qwen, Llama, Mistral, Gemma) — your call which model runs
- **End-to-end encrypted sync** via `api.ownlocalml.com` (AES-256-GCM + scrypt-derived key)
- **Markdown rendering** with code blocks, links, and inline note references
- **Command palette** (Ctrl+K) for actions and notes
- **Settings**: account, models, routing, inference, hotkeys
- **System tray**, dark theme (Tokyo Night), square edges, native feel

## Install

Download the latest release for your platform from [Releases](https://github.com/LessideRoman/pulse-desktop/releases):

| Platform | File |
|----------|------|
| Windows  | `Pulse-1.0.0.exe` (NSIS installer) or `Pulse-1.0.0.msi` |
| macOS    | `Pulse-1.0.0.dmg` |
| Linux    | `Pulse-1.0.0.deb` (Ubuntu 22.04+) |

> **Windows SmartScreen warning on first run:** Pulse is not yet code-signed (cert
> in progress). Click "More info" → "Run anyway" — the publisher shows as "Unknown".
> We're working on a signing certificate for v1.0.0 stable.

## Build from source

Requires **JDK 17** (Temurin recommended) and a working internet connection (Gradle
will pull Compose Multiplatform 1.7.0 and dependencies).

```bash
git clone https://github.com/LessideRoman/pulse-desktop
cd pulse-desktop
./gradlew packageDistributionForCurrentOS
```

Output lands in `build/compose/binaries/main/{exe,msi,dmg,deb}/`. On Windows:

```powershell
.\gradlew.bat packageDistributionForCurrentOS
```

## Tech

- **Kotlin 2.0.21** + **Jetpack Compose Multiplatform 1.7.0** (native desktop, no Electron)
- **SQLite** (via `org.xerial:sqlite-jdbc`) with FTS4 + triggers
- **Bouncy Castle** for scrypt + AES-256-GCM
- **Koin** for DI
- **No Node, no Tauri, no React, no Vue.** Just JVM and Compose.

## Project layout

```
src/main/kotlin/com/pulseteam/desktop/
├── Main.kt                    entry: Window, auth gate, routing
├── data/
│   ├── ai/AiEngine.kt         local AI engine interface + mock
│   ├── auth/                  AuthApi, AuthSession, PasswordCache
│   ├── db/Database.kt         SQLite + FTS4 + triggers + migrations
│   ├── log/PulseLogger.kt     file-based logger with rotation
│   ├── notes/                 Note + NoteRepository + NoteLinkParser
│   ├── settings/AppSettings   local settings (routing, model, inference)
│   └── sync/                  SyncEngine + Crypto (E2E)
├── ui/
│   ├── auth/                  AuthScreen, PasswordDialog
│   ├── chat/                  ChatScreen + ChatViewModel
│   ├── notes/                 NoteEditorScreen, NotesViewModel, MarkdownBody
│   ├── palette/CommandPalette
│   ├── settings/SettingsScreen (5 tabs)
│   ├── shell/Shell            topbar, sidebar, statusbar, right panel
│   ├── common/                shared composables
│   └── theme/                 Tokyo Night palette, dark, square edges
└── ...
```

## Configuration

Everything lives in `~/.pulse/`:

```
~/.pulse/
├── auth.properties            session token + user
├── settings.properties        active model, routing, temperature, top-p
├── notes-v2.db                SQLite with FTS4 virtual table
├── sync.properties            sync cursor (last seen remote revision)
├── sync.log                   sync engine log
└── logs/
    └── pulse.log              app log (rotated at 5 MB → pulse.log.1)
```

## Privacy

- Notes never leave your machine unless you sign in and enable sync.
- Sync: server stores only ciphertext + nonce + tag. Your password derives
  the encryption key via scrypt(N=2^15, r=8, p=1) and never leaves the device.
- No analytics, no tracking, no third-party fonts/icons, no auto-update beacon
  (planned for v1.1, opt-in only).

## Roadmap

| Version | Status   | Highlights |
|---------|----------|------------|
| v0.5.0  | shipped  | notes, FTS4, AI chat (mock), auth, E2E sync, settings |
| v0.6.0  | planned  | Skills (custom AI templates, picker, run history) |
| v0.7.0  | planned  | Local AI (llama.cpp subprocess) + model download |
| v1.0.0  | planned  | code signing, auto-update, full test coverage, README polished |
| v1.1.0  | future   | i18n (ru + en), Voice (Whisper), SQLCipher encryption-at-rest |

## Contributing

This is a private project right now. Public contribution flow opens with v1.0.0.

If you find a bug or have a feature idea, open an issue on
[GitHub Issues](https://github.com/LessideRoman/pulse-desktop/issues).

## License

Apache 2.0. See [LICENSE](LICENSE) for the full text.

Copyright 2026 Pulse team.
