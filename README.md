# STT Floater

Dictate from your Android phone into any Windows window over Tailscale. Originally built to make voice input work inside RealVNC sessions running [Claude Code](https://claude.com/claude-code) — where typing with the Android keyboard is miserable.

Tap a floating bubble on your phone, speak, tap again. Audio is uploaded over your Tailnet, transcribed by [faster-whisper](https://github.com/SYSTRAN/faster-whisper) running on your PC, and pasted into whichever window has focus.

## The problem this solves

If you have a Windows PC running interactive CLIs like [Claude Code](https://claude.com/claude-code) inside PowerShell, and you access that PC from your Android phone via **RealVNC** over **Tailscale**, typing through the Android keyboard inside a remote-desktop session is painfully slow. You already have a great microphone on your phone; this app turns it into a dictation bridge for whichever PC window currently has focus.

The concrete scenario it was built for:

- Windows desktop with multiple monitors and several PowerShell windows, each running Claude Code.
- Phone on the same Tailnet, viewing the PC through RealVNC.
- User wants to speak a prompt on the phone and have it appear in a specific PowerShell window — without touching the phone keyboard or leaving the remote-desktop view.

The floating bubble draws **on top of** RealVNC (and anything else), so dictation is always one tap away.

## How this was built

Most of the code in this repository was written by **[Claude Code](https://claude.com/claude-code) powered by Claude Opus 4.7** (Anthropic's CLI coding assistant). The maintainer [@kevinkicho](https://github.com/kevinkicho) described the goal, ran every build on a real Galaxy S22 + Windows 11 setup, and fed concrete feedback — observed errors, toast messages, wrong behaviors — back to Claude for each iteration. Claude handled the architecture, the Kotlin / Python / XML, the ADB / Gradle plumbing, and diagnosed the design pivots.

Notable iteration: the first approach used Android's `SpeechRecognizer` directly. After observing it fail on the S22 (Bixby starving the mic, then `NO_MATCH` even with Google's on-device recognizer), the project pivoted to recording raw audio with `AudioRecord` and transcribing server-side with Whisper. That pivot was driven by feedback from real device testing, not speculation.

Treat this as working but lightly reviewed code. PRs welcome.

## How it works

```
[Android]                              [Windows PC]
  tap bubble  ──── WAV over HTTP ────▶  Flask server
                   (Tailscale)             │
                                           ▼
                                     faster-whisper
                                           │
                                           ▼
                                  clipboard + Ctrl+V
                                  (into focused window)
```

One tap to start recording, another to stop and send. Drag the bubble to reposition it. Leave the server running in the background on your PC.

## Why not Android's SpeechRecognizer?

Short answer: on Galaxy devices, Samsung's Bixby service holds the microphone and starves other recognizers. On a Galaxy S22 running Android 13, all three recognizer paths (default, `com.google.android.tts`, `createOnDeviceSpeechRecognizer`) failed in different ways. Recording raw audio with `AudioRecord` and transcribing server-side with Whisper sidesteps every OEM quirk — and Whisper is better anyway.

## Requirements

**PC (Windows):**
- Python 3.10 or newer
- Tailscale, logged in
- ~500 MB free disk (Whisper model cache)

**Phone (Android):**
- Android 8.0 / API 26 or newer
- Tailscale, logged in
- Android Studio (to build the APK once)

## Setup

### 1. Clone and start the PC server

```
git clone https://github.com/kevinkicho/speech-to-text-app.git
cd speech-to-text-app
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python server.py
```

First launch downloads the Whisper `base.en` model (~150 MB) and preloads it. The server binds to all interfaces on port 8080 so Tailscale can reach it. For a one-click launcher on Windows, `start.bat` handles venv creation and dependency install.

Find your PC's Tailscale IP:

```
tailscale ip -4
```

### 2. Build and install the Android app

Open `stt-android/` in Android Studio, connect your phone via USB with USB debugging enabled, and click **Run**. The app installs as **STT Floater**.

Command-line alternative:

```
cd stt-android
gradlew installDebug
```

### 3. First run on the phone

1. Launch **STT Floater**.
2. **Server URL**: `http://<your-tailscale-ip>:8080`
3. **Token**: must match `STT_TOKEN` on the PC (default `change-me`).
4. Tap **Start floating bubble**. Grant microphone, notification, and "Display over other apps" permissions as prompted.

## Usage

1. In RealVNC (or whatever you use), tap the window on your PC that should receive the text — giving it focus.
2. Tap the floating 🎤 bubble on your phone.
3. Speak.
4. Tap the bubble again.

After ~1–2 seconds the transcribed text pastes into the focused window. Toggle **Press Enter after sending** in the app if you want automatic submission.

## Configuration

Environment variables on the PC server:

| Variable | Default | Notes |
|---|---|---|
| `STT_TOKEN` | `change-me` | Shared secret — must match the Android app |
| `STT_PORT` | `8080` | Listen port |
| `WHISPER_MODEL` | `base.en` | Try `small.en` for better quality, `tiny.en` for speed. Drop the `.en` suffix for multilingual |
| `WHISPER_DEVICE` | `cpu` | Set to `cuda` for an NVIDIA GPU |
| `WHISPER_COMPUTE` | `int8` | Use `float16` with CUDA |
| `WHISPER_LANGUAGE` | `en` | Set empty for auto-detect |

## API

- `POST /send` — JSON `{text, submit, token}`. Pastes text directly. Used by the PWA client.
- `POST /transcribe_and_send` — raw `audio/wav` body, `X-Token` header, optional `X-Submit` and `X-Paste` headers. Used by the Android app.
- `GET /health` — returns `{ok, whisper_loaded}`.

Both paste endpoints target the currently focused Windows window. They do not choose a window themselves — tap the right window first.

## Security

- The token is a low bar. Anything on your Tailnet can reach the server; for a personal Tailnet that is fine.
- Do not expose port 8080 to the public internet.
- Windows Firewall will prompt on first run — allow on **Private** networks.

## Alternate client: browser PWA

`static/index.html` is a minimal browser client that uses the Web Speech API for transcription and posts to `/send`. It works in Chrome on Android, but requires HTTPS for microphone access. Easiest path is `tailscale serve` to get a Let's Encrypt cert:

```
tailscale serve --bg --https=443 http://localhost:8080
```

Then open `https://<machine>.tail-xxxx.ts.net/` on the phone. The native app is recommended — it uses Whisper (better quality) and can float over other apps, which the PWA cannot.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Toast: **`no protocol`** | Missing `http://` in URL. The app auto-prepends it now; re-save settings. |
| Toast: **`http 401`** | Token mismatch between app and server. |
| Transcribing… then timeout | Windows Firewall blocking port 8080. Allow on **Private networks**. |
| Transcription is empty | Tapped twice too fast. Record at least one second of clear speech. |
| Wrong window got the text | Click the target window *before* tapping the bubble. |

## File-by-file reference

Every file and function, one line each.

### PC server (root of repo)

**`server.py`** — Flask app + Whisper loader.
- `get_whisper()` — lazily loads the `faster-whisper` model on first call and caches it as a module-level singleton.
- `paste_into_focused_window(text, submit)` — copies text to the Windows clipboard, simulates `Ctrl+V`, optionally presses Enter.
- `index()` — serves `static/index.html` (the PWA client).
- `health()` — returns `{ok: true, whisper_loaded: bool}` for diagnostics.
- `send()` — accepts JSON `{text, submit, token}` and pastes the text directly (used by the PWA).
- `transcribe_and_send()` — accepts a raw `audio/wav` body, runs Whisper on it, and pastes the transcript (used by the Android app).
- `_preload()` — background thread started at launch that primes Whisper so the first real request is fast.

**`requirements.txt`** — Python dependencies: Flask, pyperclip, pyautogui, faster-whisper.

**`start.bat`** — Windows one-click launcher; creates a `.venv`, installs deps, sets `STT_TOKEN`, runs `server.py`.

**`static/index.html`** — alternate PWA client that uses the Web Speech API and posts transcripts to `/send`.

### Android app (`stt-android/`)

**`app/src/main/AndroidManifest.xml`** — declares permissions (`INTERNET`, `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`), `MainActivity`, and `OverlayService` with `foregroundServiceType="microphone"`.

**`MainActivity.kt`** — settings screen (server URL, token, auto-Enter toggle, Start/Stop buttons).
- `onCreate()` — inflates the UI and restores saved settings from `Prefs`.
- `savePrefs()` — writes URL / token / submit back to `SharedPreferences`.
- `startFlow()` — enters the permission chain: mic → notifications → overlay.
- `requestNotifIfNeeded()` — requests `POST_NOTIFICATIONS` on Android 13+ and continues to the overlay step.
- `maybeRequestOverlay()` — sends the user to system settings if "Display over other apps" isn't granted yet.
- `launchOverlay()` — starts `OverlayService` as a foreground service.
- `onResume()` — if the user just returned from granting overlay permission, launch the overlay automatically.

**`OverlayService.kt`** — the floating-bubble foreground service.
- `onCreate()` — promotes to foreground, creates the bubble view.
- `onStartCommand()` — handles the "Stop" action fired from the service's notification.
- `startAsForeground()` — creates the notification channel and calls `startForeground` with `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
- `setupBubble()` — builds a `TextView`, attaches it to `WindowManager` as `TYPE_APPLICATION_OVERLAY`.
- `attachTouchListener()` — distinguishes tap from drag by slop; tap toggles recording, drag repositions the bubble.
- `startRecording()` — creates a `WavRecorder`, turns the bubble red, toasts "Recording…".
- `stopAndUpload()` — stops the recorder, wraps PCM as WAV, POSTs to the server via `SttClient.sendAudio`.
- `toast(s)` — short helper for user-visible messages.
- `onDestroy()` — releases the recorder and removes the bubble view.

**`WavRecorder.kt`** — raw audio capture with WAV wrapping.
- `start()` — opens `AudioRecord` (16 kHz mono PCM-16, `VOICE_RECOGNITION` source) and spawns a capture thread.
- `stop()` — stops capture, returns the collected PCM bytes wrapped in a RIFF/WAVE header.
- `toWav(pcm, sr)` — writes the WAV header fields around the raw PCM payload.
- `writeIntLE()` / `writeShortLE()` — little-endian integer writers used by the header.

**`SttClient.kt`** — HTTP client for the two server endpoints.
- `normalize(base)` — prepends `http://` if the server URL lacks a scheme.
- `send(baseUrl, token, text, submit, onResult)` — POSTs JSON to `/send`.
- `sendAudio(baseUrl, token, wav, submit, onResult)` — POSTs raw WAV bytes to `/transcribe_and_send` with `X-Token` / `X-Submit` headers.

**`Prefs.kt`** — thin `SharedPreferences` wrapper exposing `serverUrl`, `token`, and `submit` as Kotlin properties.

**`res/layout/activity_main.xml`** — settings-screen layout (URL field, token field, auto-Enter switch, Start/Stop buttons, status text).

**`res/drawable/bubble_idle.xml`** — blue translucent circle drawn when the bubble is idle.

**`res/drawable/bubble_listening.xml`** — red translucent circle drawn while recording.

**`res/values/colors.xml`**, **`strings.xml`**, **`themes.xml`** — Material 3 theme tokens, the `app_name` string, and brand colors.

**`build.gradle.kts`** (root) and **`app/build.gradle.kts`** — Gradle 8.9, AGP 8.5.2, Kotlin 1.9.24, `compileSdk = 34`, `minSdk = 26`, view binding enabled.

## Project layout

```
speech-to-text-app/
├── server.py                    # Flask + faster-whisper
├── requirements.txt
├── start.bat                    # Windows launcher
├── static/
│   └── index.html               # PWA client (alternate, text-only)
└── stt-android/                 # Native Android app
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── java/com/stt/floater/
        │   ├── MainActivity.kt
        │   ├── OverlayService.kt
        │   ├── WavRecorder.kt
        │   ├── SttClient.kt
        │   └── Prefs.kt
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/bubble_idle.xml
            ├── drawable/bubble_listening.xml
            └── values/{colors,strings,themes}.xml
```

## License

MIT.

## Credits

- [Claude Code](https://claude.com/claude-code) (Claude Opus 4.7) — wrote most of this repo.
- [faster-whisper](https://github.com/SYSTRAN/faster-whisper) — the actual speech recognizer.
- Whisper by OpenAI.
- Tailscale for the transport.
