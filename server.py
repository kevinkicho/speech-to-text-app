import os
import threading
import tempfile
import time
from flask import Flask, request, jsonify, send_from_directory
import pyperclip
import pyautogui

pyautogui.FAILSAFE = False

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


@app.route('/')
def index():
    return send_from_directory('static', 'index.html')


@app.route('/health')
def health():
    return jsonify({'ok': True, 'whisper_loaded': _whisper_model is not None})


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
