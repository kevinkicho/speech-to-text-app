# STT Floater

> **Designed to pair with [claude-sessions-app](https://github.com/kevinkicho/claude-sessions-app).**
> STT Floater routes transcribed speech into named tmux sessions (`ses1`, `ses2`, `ses3`, …) on your PC. Those sessions are managed by **claude-sessions-app**, which you should install first. Without it (or some equivalent tmux setup), there's nowhere for the transcribed text to land except a generic Windows paste.
>
> **Connectivity is Tailscale-only.** Phone and PC must be on the same Tailnet — the phone reaches the PC at its `100.x.x.x` Tailscale IP. The server refuses non-Tailnet origins as a defense-in-depth measure. No public exposure, no port forwarding.

Dictate from your Android phone into your Windows PC or directly into a live tmux session, over Tailscale. Originally built to drive [Claude Code](https://claude.com/claude-code) conversations running in PowerShell or WSL tmux without typing on the Android keyboard.

Tap a floating bubble on your phone, speak, tap again. Audio is uploaded over your Tailnet, transcribed by [faster-whisper](https://github.com/SYSTRAN/faster-whisper) on your PC, and delivered to one of three places depending on your settings:

1. **PC** — pasted into whichever Windows window is focused (original mode).
2. **Phone clipboard** — copied to Android's clipboard for you to paste anywhere on the phone.
3. **tmux session** — typed directly into a named tmux session running on your PC via `tmux send-keys`, no window focus required.

The floating bubble shows a live pill under the mic indicating where your text will go (`→ ses3`, `→ 📋`, or nothing for PC mode).

## The problem this solves

You have a Windows PC running interactive CLIs like [Claude Code](https://claude.com/claude-code). You access the PC from your Android phone or tablet — maybe via RealVNC, maybe via SSH-into-tmux from Termux. Typing through the Android keyboard inside a remote-desktop session is painful, and even in SSH it's slow.

This app turns your phone's microphone into a dictation bridge, with three routing modes so you can pick what fits: paste into the focused Windows window, copy to phone clipboard, or write directly into a named tmux session shared across all attached devices.

## How this was built

All of the code in this repo was written by **[Claude Code](https://claude.com/claude-code) powered by Claude Opus 4.7** (Anthropic's CLI coding assistant). The maintainer [@kevinkicho](https://github.com/kevinkicho) described the need, ran every build on a real Galaxy S22 Ultra + Galaxy Tab S7 + Windows 11 setup, and fed concrete feedback — observed errors, toast messages, wrong behaviors — back to Claude for each iteration. Claude handled the architecture, the Kotlin / Python / XML, the ADB / Gradle plumbing, and diagnosed the design pivots along the way.

Notable design pivots, all driven by real-device testing:

- First approach used Android's `SpeechRecognizer` directly. On a Galaxy S22, Bixby starved the mic and all three recognizer paths failed — pivoted to recording raw audio with `AudioRecord` and transcribing server-side with Whisper.
- First output path only pasted into the focused Windows window via `pyautogui`. Added clipboard mode and then tmux-send-keys mode as use cases expanded beyond RealVNC.
- For other Android apps (not Termux), added an Accessibility Service that finds the focused editable field, sets text, and clicks the app's Send button (multilingual keyword match: en/ko/ja/zh/es/fr/de/it/ru).

Treat this as working but lightly reviewed code. PRs welcome.

## How it works

```
[Android phone]                              [Windows PC]
  tap bubble → record WAV
                                ↓ over Tailscale HTTP
                                        Flask server (:8080)
                                              │
                                              ▼
                                         faster-whisper
                                              │
                                              ▼
                          ┌───────────────────┼────────────────────┐
                          ▼                   ▼                    ▼
                   pyautogui paste    clipboard+\n back       tmux send-keys
                   (PC focused win)   to phone clipboard      -t <session>
```

Routing is decided per-utterance by request headers:

- `X-Tmux-Session: ses3` or `auto` → tmux send-keys. Highest priority; skips everything else.
- `X-Paste: false` → skip PC paste. The phone app enables this in clipboard mode and writes the returned text locally.
- Default → PC paste into focused window, optionally followed by Enter.

## Routing modes

### 1. PC paste mode (default)

Transcript is typed into whichever Windows window has focus. Good if you're working on PC, bad if you're away from the PC and don't want to juggle focus. Press Enter after paste is on by default.

### 2. Phone clipboard mode

Transcript comes back to the phone and goes into Android's clipboard. You long-press → Paste in any app (Termux, Messages, Chrome, etc). If **"Then paste in & press enter"** is on:

- Clipboard has a trailing `\n` so paste in Termux submits in one action.
- An **Accessibility Service** (if you've enabled it once in Android Settings) finds the focused input field in non-Termux apps, performs Set-Text, and clicks the app's Send button — fully hands-free. Works in Messages, Chrome, Notes, KakaoTalk, etc. Termux's custom TerminalView ignores the accessibility paste, so it falls back to the clipboard flow.

### 3. Tmux send-keys mode (recommended for Claude Code workflows)

Transcript is written **directly into a named tmux session** via `wsl -d Ubuntu -- tmux send-keys -t <session> "<text>" Enter`. Because the tmux session is shared across every attached device (phone Termux, tablet Termux, PC Windows Terminal), the text appears simultaneously on all of them. Doesn't care what PC window is focused, doesn't need accessibility, doesn't need manual paste.

**Auto-detect:** when **"Auto-detect active tmux session"** is on in the phone app, the overlay polls the PC every 4 seconds for the most-recently-attached session (`tmux list-sessions -F '#{session_last_attached} #{session_name}'`) and shows it live in the pill under the mic. SSH into a different session on the phone and within ~4 s the pill updates to match. One less thing to configure.

## Why not Android's SpeechRecognizer?

Short answer: on Galaxy devices, Samsung's Bixby service holds the microphone and starves other recognizers. On a Galaxy S22 running Android 13, all three recognizer paths (default, `com.google.android.tts`, `createOnDeviceSpeechRecognizer`) failed in different ways. Recording raw audio with `AudioRecord` and transcribing server-side with Whisper sidesteps every OEM quirk — and Whisper is better anyway.

## Requirements

**PC (Windows 10/11):**
- Python 3.10 or newer
- Tailscale, logged in
- WSL + Ubuntu + `tmux` (only needed for tmux-send-keys mode)
- ~500 MB free disk (Whisper model cache)

**Phone (Android):**
- Android 8.0 / API 26 or newer
- Tailscale, logged in
- Android Studio (one-time, to build the APK)

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

First launch downloads the Whisper `base.en` model (~150 MB) and preloads it. The server binds to all interfaces on port 8080 so Tailscale can reach it. `start.bat` is a one-click launcher that handles venv creation + dep install.

Find your PC's Tailscale IP:

```
tailscale ip -4
```

### 2. Build and install the Android app

Open `stt-android/` in Android Studio, connect your phone via USB with USB debugging enabled, click **Run**. Command-line alternative:

```
cd stt-android
gradlew installDebug
```

### 3. First run on the phone

1. Launch **STT Floater**.
2. **Server URL**: `http://<your-tailscale-ip>:8080`
3. **Token**: must match `STT_TOKEN` on the PC (default `change-me`).
4. Tap **Start floating bubble**. Grant microphone, notification, and "Display over other apps" permissions when prompted.

### 4. (Optional) Enable auto-paste for non-Termux apps

To have transcripts auto-typed + sent in apps like Messages, Chrome, KakaoTalk:

1. On the phone: **Settings → Accessibility → Installed apps** (or "Downloaded services").
2. Tap **"STT Floater auto-paste"** → flip **On**.
3. Accept Android's accessibility warning.

The service only activates when **"Copy to clipboard after transcribing"** and **"Then paste in & press enter"** are both on in the app. It finds the focused editable field, calls `ACTION_SET_TEXT`, then scans the window for a Send button (matches `send` / `보내기` / `送信` / and other language keywords) and clicks it.

### 5. (Optional) Enable tmux-send-keys mode

1. In the phone app, flip **"Auto-detect active tmux session"** → On.
2. Stop and Start the bubble so it picks up the new pref.
3. The pill under the mic shows `→ (searching…)` then updates to `→ ses3` (or whatever session is most-recently attached).
4. SSH into a different tmux session from phone Termux → within ~4 s the pill updates.
5. Tap the mic, speak, tap again — text + Enter lands in that tmux session, visible on every attached device.

## Usage cheatsheet

- **Want text in a Windows app (Word, browser, etc.)?** Disable auto-detect and clipboard. Click the target window on PC. Tap bubble, speak, tap.
- **Want text in a specific tmux session (ses1, ses2, ses3, ...)?** Enable auto-detect. Make sure you've SSH'd into that session at least once recently so tmux marks it "last attached." Pill should show it. Tap, speak, tap.
- **Want text in Messages / Chrome / Notes?** Enable clipboard + "Then paste in & press enter", enable Accessibility service once. Tap compose field, tap bubble, speak, tap. Text + Send is automatic.
- **Want text in Termux outside of tmux-send-keys mode?** Enable clipboard + "Then paste in & press enter". Tap bubble, speak, tap. Long-press in Termux → Paste. The trailing `\n` submits.

### Auto vs. explicit pick (the multi-client tmux gotcha)

The pill under the mic has two modes: `Auto` (default) and a specific session you pick from the tap-to-pick menu (e.g. `• ses2`).

`Auto` resolves to **the most-recently-attached tmux session**, where "attached" means *any client* on *any device* ran `tmux attach -t sesN`. tmux tracks one global timestamp per session — not per user, not per device.

This bites in two real-life cases:

- **Two devices SSH'd in at once.** You attach `ses1` from the phone for log-tailing, then attach `ses2` from PowerShell on the laptop a few seconds later. `Auto` now picks `ses2` because the laptop attached it most recently, even though you're looking at `ses1` on the phone. Speak → text lands in `ses2`. Surprise.
- **Switching panes ≠ re-attaching.** Inside tmux, switching with `Ctrl-B s` or `Ctrl-B (` does not bump `session_last_attached`. So if you attached `ses1` an hour ago and have been navigating around inside it the whole time, `Auto` may still report `ses1` as last-attached even after a brief detour through `ses3`. Usually fine, occasionally counter-intuitive.

**The fix is the explicit pick.** Tap the pill → pick `ses2` from the menu → it stays locked to `ses2` until you change it (or pick `Auto` again). Every utterance routes there regardless of which session is "current" anywhere else.

## Configuration

Environment variables on the PC server:

| Variable | Default | Notes |
|---|---|---|
| `STT_TOKEN` | `change-me` | Shared secret — must match the Android app |
| `STT_PORT` | `8080` | Listen port |
| `WSL_DISTRO` | `Ubuntu` | WSL distro for tmux send-keys |
| `WHISPER_MODEL` | `base.en` | Try `small.en` for better quality, `tiny.en` for speed. Drop `.en` for multilingual |
| `WHISPER_DEVICE` | `cpu` | Set to `cuda` for an NVIDIA GPU |
| `WHISPER_COMPUTE` | `int8` | Use `float16` with CUDA |
| `WHISPER_LANGUAGE` | `en` | Empty for auto-detect |

## API

- `GET /health` — returns `{ok, whisper_loaded}`.
- `GET /active_session` (header `X-Token`) — returns `{ok, session}` with the most-recently-attached tmux session name, or empty string if none.
- `POST /send` — JSON `{text, submit, token}`. Pastes text directly (used by the PWA).
- `POST /transcribe_and_send` — raw `audio/wav` body. Headers:
  - `X-Token`: auth
  - `X-Submit`: `true` / `false` — press Enter after paste (PC mode only)
  - `X-Paste`: `true` / `false` — set to `false` to skip the PC paste (clipboard mode uses this)
  - `X-Tmux-Session`: `auto`, specific name, or omitted. If set, routes via `tmux send-keys` and skips PC paste.

Response always includes `{ok, text, chars, tmux_target?}` so the phone can display or clipboard-copy the transcript.

## Security

- The token is a low bar. Anything on your Tailnet can reach the server; for a personal Tailnet that is fine.
- Do not expose port 8080 to the public internet.
- Windows Firewall will prompt on first run — allow on **Private** networks.
- Accessibility Service has broad permissions on Android. Only enable the STT Floater service; disable it if you uninstall the app.

## Alternate client: browser PWA

`static/index.html` is a minimal browser client that uses the Web Speech API for transcription and posts to `/send`. Works in Chrome on Android, requires HTTPS for microphone access. Easiest path is `tailscale serve`:

```
tailscale serve --bg --https=443 http://localhost:8080
```

Then open `https://<machine>.tail-xxxx.ts.net/` on the phone. The native app is strongly recommended over this — better quality (Whisper) and can float over other apps (PWAs cannot).

## SSH key rotation

The Flask server includes a key-rotation workflow so you can swap the SSH keypair that your Android devices use to connect to the PC, without touching individual files by hand. Two ways in:

**Via the Claude Sessions GUI** (see [claude-sessions-app](https://github.com/kevinkicho/claude-sessions-app)) — a **🔑 Rotate SSH** button opens a panel with:

- Live list of ADB-connected devices.
- **🔑 Rotate keys (UAC)** — generates a fresh ed25519 keypair, triggers one UAC prompt to replace `administrators_authorized_keys` on Windows, issues a 10-minute rotation token, and auto-pushes the new private key to every connected device via ADB.
- **Push current key** — re-push the existing key to newly-connected devices (no UAC, no re-rotation). Handy when you only have one USB port and need to update a second device.
- **▶ Run rotate-key on devices** — brings Termux to the foreground on each connected Android device and types `rotate-key` via ADB input events. No typing on the device.
- **Remote token** field with copy-to-clipboard and a live countdown — for devices not plugged in; they fetch the new key from `/keyfile` over Tailnet using the token.

**Via CLI** (the scripts live locally in `tools/`, which is gitignored because it contains key material):

- `rotate-ssh.bat` — double-click to run the interactive rotation script. Pushes to each connected ADB device, then prompts to plug in another and press ENTER to push again. Prints a token for remote devices.
- `rotate-key` — on-device Termux command. Looks for a locally-pushed key at `/sdcard/Download/id_ed25519` first; falls back to fetching over Tailnet with a token. Self-updates from `/sdcard/Download/rotate-key.sh` on every run.

### Bootstrapping a new device (one-time)

Once per device, `adb push` `rotate-key.sh` to `/sdcard/rk.sh` (the GUI and `rotate-ssh.bat` do this for you during rotation), then in Termux:

```
bash /sdcard/rk.sh install
```

That copies the script into `~/rotate-key.sh` and registers a `rotate-key` alias in `~/.bashrc`. After that, **every future rotation** is just:

```
rotate-key
```

No tokens, no long paths, no reinstall. When the PC ships an updated `rotate-key.sh`, the script detects the newer copy in `/sdcard/Download` on the next run and overwrites itself silently.

### The `/keyfile` endpoint

Gated at three layers before serving the private key:

1. Origin must be a Tailnet IP (CGNAT range `100.64.0.0/10`); anything else gets `403 forbidden`.
2. `X-Rotation-Token` header must match an entry in `tools/rotation-tokens.json` that hasn't expired.
3. Tokens are valid for 10 minutes from issuance; expired ones are filtered out on every request.

Every fetch is logged to the server console with the calling IP and a truncated token prefix. The endpoint is never reachable from the public internet as long as port 8080 stays inside your Tailnet.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Toast `no protocol` | Missing `http://` in URL. The app auto-prepends it now; re-save settings. |
| Toast `http 401` | Token mismatch between app and server. |
| `Transcribing…` then timeout | Windows Firewall blocking port 8080. Allow on **Private networks**. |
| Transcription is empty | Tapped twice too fast. Record at least one second of clear speech. |
| PC paste mode → wrong window got the text | Click the target window *before* tapping the bubble. |
| Pill shows `→ (searching…)` forever | No tmux session is currently attached on the PC. Run `tmux attach -t ses3` (or `ses3` if using Claude Sessions) on the PC or phone first. |
| Auto-paste works in Messages but not KakaoTalk | Add more Send button keywords to `SttAccessibilityService.findSendButton()`. Default covers en/ko/ja/zh/es/fr/de/it/ru. |
| Enter doesn't fire in some app | That app uses Enter for newline (chat apps often do). The Accessibility Service tries a Send button match first; if no match it falls back to `ACTION_IME_ENTER`. If neither works, dump the accessibility tree (`adb shell uiautomator dump`) and add the app's Send-button description to the keyword list. |

## File-by-file reference

### PC server (root of repo)

**`server.py`** — Flask app + Whisper + tmux router.
- `get_whisper()` — lazily loads the `faster-whisper` model on first call, caches it.
- `paste_into_focused_window(text, submit)` — clipboard + `Ctrl+V` + optional Enter (via pyautogui).
- `resolve_tmux_target(target)` — resolves `'auto'` to the most-recently-attached tmux session by parsing `tmux list-sessions -F '#{session_last_attached} #{session_name}'`.
- `tmux_send(session, text)` — runs `tmux send-keys -t <session> -l <text>` then `tmux send-keys -t <session> Enter`. Literal-text mode avoids tmux key-name misinterpretation.
- `index()` — serves the PWA.
- `health()` — diagnostics.
- `active_session()` — returns the currently-auto-resolvable tmux session (used by the phone's live pill).
- `send()` — JSON text → PC paste.
- `transcribe_and_send()` — audio → Whisper → routes to tmux / clipboard / PC paste based on headers.
- `_preload()` — background-thread Whisper warm-up.

**`requirements.txt`** — Flask, pyperclip, pyautogui, faster-whisper.

**`start.bat`** — Windows one-click launcher.

**`static/index.html`** — PWA client (text-only, alternate).

### Android app (`stt-android/`)

**`AndroidManifest.xml`** — permissions (`INTERNET`, `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`), `MainActivity`, `OverlayService` (`foregroundServiceType="microphone"`), and `SttAccessibilityService` with `BIND_ACCESSIBILITY_SERVICE`.

**`MainActivity.kt`** — settings screen.
- `onCreate()` — inflates the UI and restores saved settings.
- `savePrefs()` — writes all prefs to SharedPreferences.
- `startFlow()` / `requestNotifIfNeeded()` / `maybeRequestOverlay()` — permission chain.
- `launchOverlay()` — starts OverlayService.

**`OverlayService.kt`** — floating bubble + routing.
- `setupBubble()` — builds the bubble + target-label pill in a vertical `LinearLayout` overlay.
- `refreshTargetLabel()` — shows `→ ses3` / `→ 📋` / blank based on current mode.
- `pollRunnable` — Handler-based poller (every 4 s) that calls `SttClient.fetchActiveSession` when auto-detect is on.
- `attachTouchListener()` — tap/drag discrimination; tap toggles recording.
- `startRecording()` / `stopAndUpload()` — `WavRecorder` lifecycle + HTTP upload.
- Post-response branch: tmux mode → toast; clipboard mode → `ClipboardManager.setPrimaryClip` + optional broadcast to `SttAccessibilityService`; default → PC-paste toast.

**`SttAccessibilityService.kt`** — auto-paste for non-Termux apps.
- `onServiceConnected()` — registers a package-local broadcast receiver for `ACTION_PASTE`.
- `pasteIntoFocused(text, submit)` — finds focused editable node, performs `ACTION_SET_TEXT`, then either clicks a Send button (multilingual keyword match) or falls back to `ACTION_IME_ENTER`.
- `findSendButton(root)` — depth-first scan of the window tree for clickable nodes whose `contentDescription`/`text` matches known Send keywords.

**`WavRecorder.kt`** — raw PCM → WAV bytes.
- `start()` / `stop()` — `AudioRecord` on `VOICE_RECOGNITION`, 16 kHz mono PCM-16.
- `toWav()` — RIFF/WAVE header writer.

**`SttClient.kt`** — HTTP.
- `fetchActiveSession(baseUrl, token, onResult)` — GET `/active_session` for the live pill.
- `send(...)` — JSON POST to `/send`.
- `sendAudio(baseUrl, token, wav, submit, pasteOnPc, tmuxTarget, onResult)` — WAV POST to `/transcribe_and_send`, with headers selected from phone prefs.

**`Prefs.kt`** — SharedPreferences wrapper exposing `serverUrl`, `token`, `clipboardMode`, `clipboardAutoEnter`, and `autoDetectTmux`.

**`res/layout/activity_main.xml`** — settings screen fields + switches.

**`res/xml/accessibility_service_config.xml`** — accessibility service capabilities (can retrieve window content, reads focus/text-changed events).

**`res/drawable/bubble_idle.xml`, `bubble_listening.xml`** — blue/red translucent circles.

**`res/values/colors.xml`, `strings.xml`, `themes.xml`** — Material 3 theme + app name + accessibility service description.

**`build.gradle.kts`** (root) and **`app/build.gradle.kts`** — Gradle 8.9, AGP 8.5.2, Kotlin 1.9.24, `compileSdk = 34`, `minSdk = 26`, view binding enabled.

## Project layout

```
speech-to-text-app/
├── server.py                    # Flask + faster-whisper + tmux router
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
        │   ├── SttAccessibilityService.kt
        │   ├── WavRecorder.kt
        │   ├── SttClient.kt
        │   └── Prefs.kt
        └── res/
            ├── layout/activity_main.xml
            ├── xml/accessibility_service_config.xml
            ├── drawable/bubble_idle.xml
            ├── drawable/bubble_listening.xml
            └── values/{colors,strings,themes}.xml
```

## Related repo

If you want the tmux-session side of the workflow (named `ses1`, `ses2`, ... sessions pinned to project folders, one dark-mode GUI to configure them, cross-device mirroring), see [**claude-sessions-app**](https://github.com/kevinkicho/claude-sessions-app). STT Floater's `autoDetectTmux` mode pairs naturally with that tool.

## License

MIT.

## Credits

- [Claude Code](https://claude.com/claude-code) (Claude Opus 4.7) — wrote all of the code in this repo. I described the need, tested on real hardware (Galaxy S22 Ultra + Galaxy Tab S7 + Windows 11), and fed feedback.
- [faster-whisper](https://github.com/SYSTRAN/faster-whisper) — the actual speech recognizer.
- Whisper by OpenAI.
- Tailscale for the transport.
