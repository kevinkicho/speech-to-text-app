import os
import subprocess
import threading
import tempfile
import time
from flask import Flask, request, jsonify, send_from_directory
import pyperclip
import pyautogui

pyautogui.FAILSAFE = False

WSL_DISTRO = os.environ.get('WSL_DISTRO', 'Ubuntu')

app = Flask(__name__, static_folder='static', static_url_path='')

TOKEN = os.environ.get('STT_TOKEN', 'change-me')
WHISPER_MODEL = os.environ.get('WHISPER_MODEL', 'base.en')
WHISPER_DEVICE = os.environ.get('WHISPER_DEVICE', 'cpu')
WHISPER_COMPUTE = os.environ.get('WHISPER_COMPUTE', 'int8')
WHISPER_LANGUAGE = os.environ.get('WHISPER_LANGUAGE', 'en')

_whisper_model = None
_whisper_lock = threading.Lock()


def get_whisper():
    global _whisper_model
    if _whisper_model is None:
        with _whisper_lock:
            if _whisper_model is None:
                from faster_whisper import WhisperModel
                print(f'[whisper] loading {WHISPER_MODEL} ({WHISPER_DEVICE}/{WHISPER_COMPUTE}) — first run downloads ~150 MB')
                t0 = time.time()
                _whisper_model = WhisperModel(
                    WHISPER_MODEL,
                    device=WHISPER_DEVICE,
                    compute_type=WHISPER_COMPUTE,
                )
                print(f'[whisper] ready in {time.time() - t0:.1f}s')
    return _whisper_model


def paste_into_focused_window(text: str, submit: bool) -> None:
    time.sleep(0.15)
    pyperclip.copy(text)
    pyautogui.hotkey('ctrl', 'v')
    if submit:
        time.sleep(0.05)
        pyautogui.press('enter')


def resolve_tmux_target(target: str) -> str:
    """Resolve 'auto' to the most-recently-attached tmux session name.
    Returns the input unchanged if it's a specific name, or '' on failure."""
    target = (target or '').strip()
    if target.lower() != 'auto':
        return target
    try:
        out = subprocess.run(
            ['wsl', '-d', WSL_DISTRO, '--', 'tmux', 'list-sessions',
             '-F', '#{session_last_attached} #{session_name}'],
            capture_output=True, text=True, timeout=5,
        )
        if out.returncode != 0:
            return ''
        lines = [l for l in out.stdout.splitlines() if l.strip()]
        if not lines:
            return ''
        def ts(line: str) -> int:
            first = line.split(None, 1)[0]
            return int(first) if first.isdigit() else 0
        best = max(lines, key=ts)
        parts = best.split(None, 1)
        return parts[1].strip() if len(parts) > 1 else ''
    except Exception as e:
        print(f'[tmux] resolve auto failed: {e}')
        return ''


def tmux_send(session_name: str, text: str) -> tuple[bool, str]:
    """Send literal text + Enter to the named tmux session. Returns (ok, msg)."""
    if not session_name:
        return False, 'no session'
    try:
        # -l makes send-keys treat the argument as literal text (no key-name
        # interpretation), then a separate Enter keypress submits.
        r1 = subprocess.run(
            ['wsl', '-d', WSL_DISTRO, '--', 'tmux', 'send-keys', '-t', session_name, '-l', text],
            capture_output=True, text=True, timeout=5,
        )
        if r1.returncode != 0:
            return False, (r1.stderr or r1.stdout or 'tmux send-keys -l failed').strip()
        r2 = subprocess.run(
            ['wsl', '-d', WSL_DISTRO, '--', 'tmux', 'send-keys', '-t', session_name, 'Enter'],
            capture_output=True, text=True, timeout=5,
        )
        if r2.returncode != 0:
            return False, (r2.stderr or r2.stdout or 'tmux Enter failed').strip()
        return True, 'ok'
    except Exception as e:
        return False, str(e)


@app.route('/')
def index():
    return send_from_directory('static', 'index.html')


@app.route('/health')
def health():
    return jsonify({'ok': True, 'whisper_loaded': _whisper_model is not None})


@app.route('/active_session')
def active_session():
    """Lets the phone overlay ask 'which tmux session would auto route to
    right now?' for a live label under the mic. Returns '' if none attached."""
    if request.headers.get('X-Token') != TOKEN:
        return jsonify({'error': 'bad token'}), 401
    return jsonify({'ok': True, 'session': resolve_tmux_target('auto')})


@app.route('/send', methods=['POST'])
def send():
    data = request.get_json(silent=True) or {}
    if data.get('token') != TOKEN:
        return jsonify({'error': 'bad token'}), 401
    text = (data.get('text') or '').strip()
    if not text:
        return jsonify({'error': 'empty'}), 400
    paste_into_focused_window(text, bool(data.get('submit', False)))
    return jsonify({'ok': True, 'chars': len(text)})


@app.route('/transcribe_and_send', methods=['POST'])
def transcribe_and_send():
    if request.headers.get('X-Token') != TOKEN:
        return jsonify({'error': 'bad token'}), 401

    submit = request.headers.get('X-Submit', 'false').lower() == 'true'
    paste = request.headers.get('X-Paste', 'true').lower() == 'true'

    audio_bytes = request.get_data()
    if not audio_bytes or len(audio_bytes) < 1000:
        return jsonify({'error': 'audio too short'}), 400

    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
        tmp.write(audio_bytes)
        tmp_path = tmp.name

    try:
        model = get_whisper()
        segments, _info = model.transcribe(
            tmp_path,
            beam_size=5,
            vad_filter=True,
            language=WHISPER_LANGUAGE or None,
        )
        text = ' '.join(s.text.strip() for s in segments).strip()
    except Exception as e:
        print('[whisper] transcribe failed:', repr(e))
        return jsonify({'error': f'whisper failed: {e}'}), 500
    finally:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass

    # Highest-priority target: tmux send-keys if the phone specified a session.
    # 'auto' resolves to the most-recently-attached tmux session.
    tmux_target_raw = request.headers.get('X-Tmux-Session', '').strip()
    if tmux_target_raw and text:
        resolved = resolve_tmux_target(tmux_target_raw)
        if resolved:
            ok, msg = tmux_send(resolved, text)
            if ok:
                return jsonify({'ok': True, 'text': text, 'chars': len(text),
                                'tmux_target': resolved})
            # If tmux failed, fall through to clipboard/paste so the utterance
            # isn't lost — the phone will get the text in its response JSON.
            print(f'[tmux] send failed to {resolved!r}: {msg}')
        else:
            print(f'[tmux] could not resolve target {tmux_target_raw!r}')

    if paste and text:
        paste_into_focused_window(text, submit)

    return jsonify({'ok': True, 'text': text, 'chars': len(text)})


def _preload():
    try:
        get_whisper()
    except Exception as e:
        print('[whisper] preload failed:', repr(e))


if __name__ == '__main__':
    port = int(os.environ.get('STT_PORT', '8080'))
    threading.Thread(target=_preload, daemon=True).start()
    app.run(host='0.0.0.0', port=port, debug=False, threaded=True)
