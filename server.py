import hmac
import ipaddress
import json
import logging
import os
import subprocess
import sys
import threading
import tempfile
import time
from pathlib import Path
from flask import Flask, request, jsonify, send_from_directory, Response
import pyperclip
import pyautogui

pyautogui.FAILSAFE = False

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

PROJECT_ROOT = Path(os.environ.get('STT_PROJECT_ROOT') or Path(__file__).resolve().parent)
TOOLS_DIR = Path(os.environ.get('STT_TOOLS_DIR') or (PROJECT_ROOT / 'tools'))

WSL_DISTRO = os.environ.get('WSL_DISTRO', 'Ubuntu')

TOKEN = os.environ.get('STT_TOKEN', 'change-me')
WHISPER_MODEL = os.environ.get('WHISPER_MODEL', 'base.en')
WHISPER_DEVICE = os.environ.get('WHISPER_DEVICE', 'cpu')
WHISPER_COMPUTE = os.environ.get('WHISPER_COMPUTE', 'int8')
WHISPER_LANGUAGE = os.environ.get('WHISPER_LANGUAGE', 'en')

# WSL invocations after a laptop resume can cold-start the distro for ~10s.
# 15s gives headroom without hanging requests forever.
WSL_TIMEOUT = int(os.environ.get('STT_WSL_TIMEOUT', '15'))

# SSH key rotation paths.
ROTATION_KEY_PATH = Path(os.environ.get('STT_ROTATION_KEY') or (TOOLS_DIR / 'ssh_key'))
ROTATION_TOKENS_PATH = Path(os.environ.get('STT_ROTATION_TOKENS') or (TOOLS_DIR / 'rotation-tokens.json'))

app = Flask(__name__, static_folder='static', static_url_path='')

# ---------------------------------------------------------------------------
# Logging — single source of truth so the watchdog log captures everything.
# ---------------------------------------------------------------------------

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger('stt')


def _redact(s: str) -> str:
    """Show a token's first 4 chars + ellipsis. Never log the full value."""
    if not s:
        return '(none)'
    return s[:4] + '…' if len(s) > 4 else '…'


# ---------------------------------------------------------------------------
# Origin gating: only Tailnet IPs and localhost are allowed near sensitive
# endpoints. Defense-in-depth so accidental localhost-bypass tunnels (or
# misconfigured firewalls) can't expose audio capture / tmux send-keys.
# ---------------------------------------------------------------------------

_TAILNET_NET = ipaddress.ip_network('100.64.0.0/10')
_LOOPBACK_NET_V4 = ipaddress.ip_network('127.0.0.0/8')


def _is_allowed_origin(ip_str: str) -> bool:
    """Tailnet (100.64/10), IPv4 loopback, or IPv6 loopback. Anything else
    is rejected. Loopback stays allowed so local curl/health checks work."""
    try:
        ip = ipaddress.ip_address(ip_str)
        if isinstance(ip, ipaddress.IPv4Address):
            return ip in _TAILNET_NET or ip in _LOOPBACK_NET_V4
        return ip.is_loopback  # ::1
    except Exception:
        return False


def _is_tailnet_ip(ip_str: str) -> bool:
    """Stricter check used only by /keyfile (no loopback)."""
    try:
        return ipaddress.ip_address(ip_str) in _TAILNET_NET
    except Exception:
        return False


def _gate_origin():
    """Returns a Flask response if origin should be rejected, else None."""
    remote = request.remote_addr or ''
    if not _is_allowed_origin(remote):
        log.warning(f'reject non-Tailnet origin {remote} path={request.path}')
        return jsonify({'error': 'forbidden'}), 403
    return None


def _gate_token():
    """Returns a Flask response if app token is missing/wrong, else None."""
    supplied = request.headers.get('X-Token') or (request.get_json(silent=True) or {}).get('token')
    if supplied != TOKEN:
        log.warning(f'reject bad token from {request.remote_addr} path={request.path} got={_redact(supplied or "")}')
        return jsonify({'error': 'bad token'}), 401
    return None


# ---------------------------------------------------------------------------
# Rotation tokens (separate from app token).
# ---------------------------------------------------------------------------

def _load_rotation_tokens() -> list[dict]:
    if not ROTATION_TOKENS_PATH.exists():
        return []
    try:
        data = json.loads(ROTATION_TOKENS_PATH.read_text(encoding='utf-8'))
        return [t for t in data if t.get('expires_at', 0) > time.time()]
    except Exception as e:
        log.error(f'rotation: failed to read tokens: {e}')
        return []


def _rotation_token_matches(supplied: str) -> bool:
    if not supplied:
        return False
    for t in _load_rotation_tokens():
        candidate = t.get('token', '')
        if candidate and hmac.compare_digest(candidate, supplied):
            return True
    return False


# ---------------------------------------------------------------------------
# Whisper.
# ---------------------------------------------------------------------------

_whisper_model = None
_whisper_lock = threading.Lock()


def get_whisper():
    global _whisper_model
    if _whisper_model is None:
        with _whisper_lock:
            if _whisper_model is None:
                from faster_whisper import WhisperModel
                log.info(f'whisper: loading {WHISPER_MODEL} ({WHISPER_DEVICE}/{WHISPER_COMPUTE}) — first run downloads ~150 MB')
                t0 = time.time()
                _whisper_model = WhisperModel(
                    WHISPER_MODEL,
                    device=WHISPER_DEVICE,
                    compute_type=WHISPER_COMPUTE,
                )
                log.info(f'whisper: ready in {time.time() - t0:.1f}s')
    return _whisper_model


# ---------------------------------------------------------------------------
# Delivery — pyautogui paste and tmux send-keys both touch single-threaded
# OS surfaces. Serialize so two phones speaking at once can't interleave
# keystrokes. Whisper transcription itself can still run in parallel.
# ---------------------------------------------------------------------------

_deliver_lock = threading.Lock()


def paste_into_focused_window(text: str, submit: bool) -> None:
    with _deliver_lock:
        time.sleep(0.15)
        pyperclip.copy(text)
        pyautogui.hotkey('ctrl', 'v')
        if submit:
            time.sleep(0.05)
            pyautogui.press('enter')


# ---------------------------------------------------------------------------
# WSL / tmux.
# ---------------------------------------------------------------------------

def _wsl_warmup():
    """Wake the WSL distro so the first tmux call doesn't time out after a
    laptop resume. Runs in a background thread on server boot."""
    try:
        t0 = time.time()
        r = subprocess.run(
            ['wsl', '-d', WSL_DISTRO, '--', 'true'],
            capture_output=True, text=True, timeout=30,
        )
        log.info(f'wsl: warmup rc={r.returncode} in {time.time() - t0:.1f}s (distro={WSL_DISTRO})')
    except Exception as e:
        log.warning(f'wsl: warmup failed: {e}')


def resolve_tmux_target(target: str) -> str:
    """Resolve 'auto' to the most-recently-attached tmux session name.
    Returns the input unchanged if it's a specific name, or '' on failure.

    The tmux -F format is wrapped in `bash -lc '...'` because passing
    `#{session_name}` literally via `wsl.exe -- tmux ... -F #{...}` mangles
    the format arg on Windows (wsl.exe's arg parser strips it). Letting bash
    inside the distro receive the whole command as one string sidesteps this.
    """
    target = (target or '').strip()
    if target.lower() != 'auto':
        return target
    try:
        out = subprocess.run(
            ['wsl', '-d', WSL_DISTRO, '--', 'bash', '-lc',
             "tmux list-sessions -F '#{session_last_attached} #{session_name}'"],
            capture_output=True, text=True, timeout=WSL_TIMEOUT,
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
        log.warning(f'tmux: resolve auto failed: {e}')
        return ''


def tmux_send(session_name: str, text: str) -> tuple[bool, str]:
    """Send literal text + Enter to the named tmux session. Returns (ok, msg).

    Held under _deliver_lock so two concurrent transcriptions to the same
    session can't interleave the -l text and the Enter keypress.
    """
    if not session_name:
        return False, 'no session'
    with _deliver_lock:
        try:
            r1 = subprocess.run(
                ['wsl', '-d', WSL_DISTRO, '--', 'tmux', 'send-keys', '-t', session_name, '-l', text],
                capture_output=True, text=True, timeout=WSL_TIMEOUT,
            )
            if r1.returncode != 0:
                return False, (r1.stderr or r1.stdout or 'tmux send-keys -l failed').strip()
            r2 = subprocess.run(
                ['wsl', '-d', WSL_DISTRO, '--', 'tmux', 'send-keys', '-t', session_name, 'Enter'],
                capture_output=True, text=True, timeout=WSL_TIMEOUT,
            )
            if r2.returncode != 0:
                return False, (r2.stderr or r2.stdout or 'tmux Enter failed').strip()
            return True, 'ok'
        except Exception as e:
            return False, str(e)


# ---------------------------------------------------------------------------
# Routes.
# ---------------------------------------------------------------------------

@app.route('/')
def index():
    return send_from_directory('static', 'index.html')


@app.route('/health')
def health():
    # Open intentionally — liveness probe shouldn't require a token.
    return jsonify({'ok': True, 'whisper_loaded': _whisper_model is not None})


@app.route('/active_session')
def active_session():
    """Live label for the phone overlay's pill."""
    if (resp := _gate_origin()): return resp
    if (resp := _gate_token()): return resp
    return jsonify({'ok': True, 'session': resolve_tmux_target('auto')})


@app.route('/sessions')
def sessions():
    """All tmux session names for the phone's tap-to-pick menu."""
    if (resp := _gate_origin()): return resp
    if (resp := _gate_token()): return resp
    try:
        out = subprocess.run(
            ['wsl', '-d', WSL_DISTRO, '--', 'bash', '-lc',
             "tmux list-sessions -F '#{session_name}'"],
            capture_output=True, text=True, timeout=WSL_TIMEOUT,
        )
        names = [l.strip() for l in out.stdout.splitlines() if l.strip()] \
                if out.returncode == 0 else []
        return jsonify({'ok': True, 'sessions': names})
    except Exception as e:
        log.warning(f'sessions: tmux list failed: {e}')
        return jsonify({'ok': False, 'sessions': [], 'error': str(e)})


@app.route('/keyfile')
def keyfile():
    """Serves the current SSH private key. Tailnet-only (stricter than other
    endpoints — explicitly excludes loopback)."""
    remote = request.remote_addr or ''
    token = request.headers.get('X-Rotation-Token', '')

    if not _is_tailnet_ip(remote):
        log.warning(f'keyfile: REJECT non-Tailnet origin {remote} token={_redact(token)}')
        return jsonify({'error': 'forbidden'}), 403

    if not _rotation_token_matches(token):
        log.warning(f'keyfile: REJECT bad/expired token from {remote} token={_redact(token)}')
        return jsonify({'error': 'unauthorized'}), 401

    if not ROTATION_KEY_PATH.exists():
        log.error(f'keyfile: no key file at {ROTATION_KEY_PATH}')
        return jsonify({'error': 'no key staged'}), 503

    log.info(f'keyfile: OK serving key to {remote} token={_redact(token)}')
    body = ROTATION_KEY_PATH.read_bytes()
    return Response(body, mimetype='application/x-pem-file')


def _route_text(text: str, tmux_target_raw: str, paste: bool, submit: bool) -> dict:
    """Shared routing logic used by /transcribe_and_send and /send. Tries
    tmux first if a target was supplied, then PC paste if explicitly enabled.
    Returns the JSON dict to send back to the client."""
    tmux_failed_reason = ''
    if tmux_target_raw and text:
        resolved = resolve_tmux_target(tmux_target_raw)
        if resolved:
            ok, msg = tmux_send(resolved, text)
            if ok:
                return {'ok': True, 'text': text, 'chars': len(text), 'tmux_target': resolved}
            tmux_failed_reason = f'send to {resolved!r}: {msg}'
            log.warning(f'tmux: {tmux_failed_reason}')
        else:
            tmux_failed_reason = f'could not resolve {tmux_target_raw!r}'
            log.warning(f'tmux: {tmux_failed_reason}')

    delivered = False
    if paste and text:
        paste_into_focused_window(text, submit)
        delivered = True

    return {
        'ok': True,
        'text': text,
        'chars': len(text),
        'delivered': delivered,
        'tmux_failed': tmux_failed_reason or None,
    }


@app.route('/send', methods=['POST'])
def send():
    """Deliver an already-transcribed text. Used by the phone's confirm-tap
    flow: first POST audio with X-Transcribe-Only, get text, show preview,
    then POST that text here for routing only after the user confirms.

    Accepts JSON: {token, text, tmux_target?, paste?, submit?}.
    """
    if (resp := _gate_origin()): return resp
    if (resp := _gate_token()): return resp
    data = request.get_json(silent=True) or {}
    text = (data.get('text') or '').strip()
    if not text:
        return jsonify({'error': 'empty'}), 400
    return jsonify(_route_text(
        text=text,
        tmux_target_raw=(data.get('tmux_target') or '').strip(),
        paste=bool(data.get('paste', False)),
        submit=bool(data.get('submit', True)),
    ))


@app.route('/transcribe_and_send', methods=['POST'])
def transcribe_and_send():
    if (resp := _gate_origin()): return resp
    if (resp := _gate_token()): return resp

    submit = request.headers.get('X-Submit', 'false').lower() == 'true'
    # PC-paste fallback is OPT-IN. Default to false so a tmux-routing failure
    # never silently types into a random Windows window (Slack, browser bar,
    # ...). Phone explicitly sends X-Paste: true if user enabled the toggle.
    paste = request.headers.get('X-Paste', 'false').lower() == 'true'
    # When set, just transcribe and return the text — don't route. Phone uses
    # this for the confirm-tap flow (preview before sending).
    transcribe_only = request.headers.get('X-Transcribe-Only', 'false').lower() == 'true'

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
        log.error(f'whisper: transcribe failed: {e!r}')
        return jsonify({'error': f'whisper failed: {e}'}), 500
    finally:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass

    if transcribe_only:
        return jsonify({'ok': True, 'text': text, 'chars': len(text)})

    return jsonify(_route_text(
        text=text,
        tmux_target_raw=request.headers.get('X-Tmux-Session', '').strip(),
        paste=paste,
        submit=submit,
    ))


# ---------------------------------------------------------------------------
# Boot.
# ---------------------------------------------------------------------------

def _preload():
    try:
        get_whisper()
    except Exception as e:
        log.error(f'whisper: preload failed: {e!r}')


if __name__ == '__main__':
    port = int(os.environ.get('STT_PORT', '8080'))
    log.info(f'boot: project_root={PROJECT_ROOT}')
    log.info(f'boot: token={_redact(TOKEN)} wsl={WSL_DISTRO} port={port}')
    threading.Thread(target=_preload, daemon=True).start()
    threading.Thread(target=_wsl_warmup, daemon=True).start()
    app.run(host='0.0.0.0', port=port, debug=False, threaded=True)
