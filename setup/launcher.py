"""VoxMagica Voice Setup - distributable Windows and Linux installer/launcher.

A stdlib-only Tkinter app that copies the VoxMagica mod jar into the mods
folder, lets the player pick a language + local whisper model (pre-downloading
it so the first in-game voice-cast isn't blocked on a multi-hundred-MB
download), and writes SttProvider=local into every save's
VoxMagicaVoiceConfig.json. Speech-to-text itself now runs fully in-process
inside the mod (whisper.cpp via whisper-jni) - no separate server, Docker, or
Python runtime needed. (The speaches/openai providers still work if you
hand-edit VoxMagicaVoiceConfig.json - see VoxMagicaVoiceConfig.java - but this
installer no longer sets either of them up; it's local-only.)

Built into a single self-contained platform binary with PyInstaller (see
build.bat on Windows, build.sh on Linux - PyInstaller output is never
cross-platform, so each OS needs its own build run).

Run with --selftest to exercise the non-GUI logic without opening a window.
"""

from __future__ import annotations

import hashlib
import json
import os
import queue
import shutil
import sys
import threading
import time
import urllib.request
from pathlib import Path

try:
    import tkinter as tk
    from tkinter import ttk, messagebox
    TK_AVAILABLE = True
except Exception:  # pragma: no cover - headless environments
    TK_AVAILABLE = False


APP_NAME = "VoxMagica Voice Setup"
APP_VERSION = "2.0.0"

IS_WINDOWS = sys.platform == "win32"
IS_LINUX = sys.platform.startswith("linux")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

STATE_DIR_NAME = "VoxMagicaVoice"
STATE_FILE_NAME = "state.json"

CONFIG_FILE_NAME = "VoxMagicaVoiceConfig.json"
MOD_NAMESPACE_DIR = "Ev0sMods_VoxMagica"

MOD_JAR_NAME = "VoxMagica-0.1.0.jar"
MOD_JAR_SOURCE = "VoxMagica-0.1.0-release-shaded.jar"

# Language code -> recommended model. Blank SttModel is written as "" so the mod
# resolves the matching whisper variant itself (keeps the installer honest with
# LocalWhisperModelCatalog.defaultModelFor on the Java side).
LANGUAGES = [
    ("", "Auto-detect"),
    ("en", "English"),
    ("es", "Español"),
    ("de", "Deutsch"),
    ("fr", "Français"),
    ("it", "Italiano"),
    ("pt", "Português"),
    ("nl", "Nederlands"),
    ("pl", "Polski"),
    ("ru", "Русский"),
    ("ja", "日本語"),
    ("zh", "中文"),
    ("ko", "한국어"),
]

# whisper.cpp GGML model names, sizes and sha256 hashes - MUST stay in sync with
# LocalWhisperModelCatalog.java; there is no compiler to catch drift between the
# two. Fetched from the Hugging Face API (ggerganov/whisper.cpp), not fabricated.
GGML_DOWNLOAD_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
LOCAL_MODELS = {
    "tiny.en": (77_704_715, "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f"),
    "tiny": (77_691_713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
    "base.en": (147_964_211, "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"),
    "base": (147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
    "small.en": (487_614_201, "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d"),
    "small": (487_601_967, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
    "medium.en": (1_533_774_781, "cc37e93478338ec7700281a7ac30a10128929eb8f427dda2e865faa8f6da4356"),
    "medium": (1_533_763_059, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
    "large-v3": (3_095_033_483, "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2"),
}

# "Recommended (auto)" writes a blank SttModel so the mod picks a matching variant.
AUTO_MODEL = ("", "Recommended (auto)")
MODELS = [
    ("tiny.en", "tiny.en (English only, fastest, ~75 MB)"),
    ("tiny", "tiny (multilingual, fastest, ~75 MB)"),
    ("base.en", "base.en (English only, fast, ~150 MB)"),
    ("base", "base (multilingual, fast, ~150 MB)"),
    ("small.en", "small.en (English only, ~490 MB)"),
    ("small", "small (multilingual, ~490 MB)"),
    ("medium.en", "medium.en (English only, ~1.5 GB)"),
    ("medium", "medium (multilingual, ~1.5 GB)"),
    ("large-v3", "large-v3 (multilingual, best, ~3.1 GB)"),
]

CANONICAL_CONFIG = {
    "SttProvider": "local",
    "SttModel": "",
    "SttLanguage": "",
    "MultiCastDelayMs": 250,
}

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------


def app_data_dir() -> Path:
    """Small/roaming-style config dir: Windows AppData\\Roaming, Linux XDG_CONFIG_HOME."""
    if IS_WINDOWS:
        base = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config")
    return Path(base) / STATE_DIR_NAME


def state_file() -> Path:
    return app_data_dir() / STATE_FILE_NAME


def bundled_root() -> Path:
    # PyInstaller onefile unpacks to sys._MEIPASS/resources; source layout uses
    # the sibling `resources` folder next to this script.
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        return Path(meipass) / "resources"
    return Path(__file__).resolve().parent / "resources"


def hytale_userdata() -> Path:
    base = os.environ.get("HYTALE_USERDATA")
    if base:
        path = Path(base)
        if path.exists():
            return path
    if IS_WINDOWS:
        apdata = os.environ.get("APPDATA") or str(Path.home() / "AppData" / "Roaming")
        return Path(apdata) / "Hytale" / "UserData"
    # Best-effort XDG-style guess for a native Linux install - not verified
    # against a real Linux Hytale client. If yours lives elsewhere (e.g. a
    # Steam/Proton compatdata prefix), set HYTALE_USERDATA to override.
    data_home = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
    guessed = Path(data_home) / "Hytale" / "UserData"
    if not guessed.exists():
        print(f"[hytale] NOTE: guessing UserData at {guessed} (not found yet) - "
              f"set the HYTALE_USERDATA env var if your install lives elsewhere.",
              file=sys.stderr)
    return guessed


def mods_dir() -> Path:
    return hytale_userdata() / "Mods"


def saves_dir() -> Path:
    return hytale_userdata() / "Saves"


def local_models_dir() -> Path:
    """Must match LocalTranscriber.modelsDir() on the Java side exactly - confirmed live
    against a real server (JavaPlugin.getFile() resolves to <UserData>/Mods/<jar>), not just
    inferred: <UserData>/VoxMagicaData/whisper-models/, sibling to Mods/ and Saves/."""
    return hytale_userdata() / "VoxMagicaData" / "whisper-models"


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def sha256_of(path: Path) -> str:
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 16), b""):
                digest.update(chunk)
        return digest.hexdigest()
    except OSError:
        return ""


def download(url: str, dest: Path, log) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    log(f"Downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "VoxMagica-Launcher"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if total:
                log(f"  {done // 1024} / {total // 1024} KiB", progress=True)
    log(f"Saved {dest}")


# ---------------------------------------------------------------------------
# Installer state (json)
# ---------------------------------------------------------------------------


def default_state() -> dict:
    return {
        "language": "",
        "model": "",
        "configured_at": "",
    }


def load_state() -> dict:
    state = default_state()
    try:
        with open(state_file(), "r", encoding="utf-8") as handle:
            loaded = json.load(handle)
        if isinstance(loaded, dict):
            state.update(loaded)
    except (OSError, ValueError):
        pass
    return state


def save_state(state: dict) -> None:
    state_file().parent.mkdir(parents=True, exist_ok=True)
    with open(state_file(), "w", encoding="utf-8") as handle:
        json.dump(state, handle, indent=2, ensure_ascii=False)


# ---------------------------------------------------------------------------
# Jars in the mods folder
# ---------------------------------------------------------------------------


def ensure_mod_jars(log) -> list:
    """Copy the bundled VoxMagica jar into the mods folder when missing or
    different from what we ship. Returns list of issues.
    Hexcode is intentionally NOT distributed/bundled."""
    issues = []
    mod_dir = mods_dir()
    mod_dir.mkdir(parents=True, exist_ok=True)
    src_root = bundled_root()

    targets = [
        (MOD_JAR_SOURCE, MOD_JAR_NAME),
    ]
    for source_name, target_name in targets:
        source = src_root / source_name
        target = mod_dir / target_name
        if not source.exists():
            issues.append(f"bundled resource missing: {source_name}")
            log(f"[jar] MISSING bundled resource {source_name}")
            continue
        if target.exists() and sha256_of(target) == sha256_of(source):
            log(f"[jar] {target_name} already installed and current")
            continue
        try:
            shutil.copy2(source, target)
            log(f"[jar] Installed {target_name} -> {mod_dir}")
        except OSError as exc:
            issues.append(f"{target_name}: {exc}")
            log(f"[jar] FAILED to copy {target_name}: {exc}")
    return issues


# ---------------------------------------------------------------------------
# Local whisper model prefetch
# ---------------------------------------------------------------------------


def ggml_model_url(name: str) -> str:
    return GGML_DOWNLOAD_BASE + f"ggml-{name}.bin"


def prefetch_local_model(state: dict, log) -> None:
    """Downloads (if missing or hash-mismatched) the selected/default local whisper model into
    the same cache directory LocalTranscriber.java looks in, so the first in-game voice-cast
    isn't blocked on a multi-hundred-MB-to-multi-GB download. Best-effort/non-fatal: if this
    fails, or the configured model name isn't in this installer's catalog, the plugin's own
    downloader still covers it on first use - see LocalTranscriber.ensureDownloaded."""
    model = (state.get("model") or "").strip()
    if not model:
        lang = (state.get("language") or "").strip()
        model = "base.en" if lang == "en" else "base"

    entry = LOCAL_MODELS.get(model)
    if entry is None:
        log(f"[model] '{model}' is not in this installer's catalog; "
            f"the mod will fetch it on first use instead.")
        return
    size_bytes, sha256_hex = entry

    models_dir = local_models_dir()
    models_dir.mkdir(parents=True, exist_ok=True)
    target = models_dir / f"ggml-{model}.bin"
    if target.exists() and sha256_of(target) == sha256_hex:
        log(f"[model] '{model}' already present and verified.")
        return

    log(f"[model] Downloading '{model}' ({size_bytes // (1024 * 1024)} MB) ...")
    tmp = target.with_name(target.name + ".part")
    try:
        download(ggml_model_url(model), tmp, log)
    except OSError as exc:
        log(f"[model] Download failed: {exc}")
        return

    if sha256_of(tmp) != sha256_hex:
        log(f"[model] Downloaded '{model}' failed sha256 verification; discarding.")
        try:
            tmp.unlink()
        except OSError:
            pass
        return

    tmp.replace(target)
    log(f"[model] '{model}' downloaded and verified.")


# ---------------------------------------------------------------------------
# Config writer
# ---------------------------------------------------------------------------


def find_voice_configs(log) -> list:
    """Locate every existing VoxMagicaVoiceConfig.json under Saves + any
    save dir that already carries the Ev0sMods_VoxMagica mod folder."""
    found = []
    base = saves_dir()
    if not base.exists():
        log(f"[config] No saves found under {base}")
        return found
    for save in sorted(base.iterdir()):
        if not save.is_dir():
            continue
        mod_folder = save / "mods" / MOD_NAMESPACE_DIR
        cfg = mod_folder / CONFIG_FILE_NAME
        if not mod_folder.exists():
            continue
        if cfg.exists():
            found.append(cfg)
            log(f"[config] Found {cfg}")
        else:
            # The mod node is present in this save but not configured yet.
            found.append(cfg)
            log(f"[config] Mod present in '{save.name}' but unconfigured -> {cfg}")
    return found


def write_voice_config(cfg_path: Path, state: dict, log) -> None:
    cfg_path.parent.mkdir(parents=True, exist_ok=True)
    canonical = dict(CANONICAL_CONFIG)
    canonical["SttModel"] = (state.get("model") or "").strip()
    canonical["SttLanguage"] = (state.get("language") or "").strip()
    try:
        existing = {}
        if cfg_path.exists():
            with open(cfg_path, "r", encoding="utf-8-sig") as handle:
                existing = json.load(handle)
            if not isinstance(existing, dict):
                existing = {}
    except (OSError, ValueError):
        existing = {}
    merged = dict(existing)
    merged.update({k: v for k, v in canonical.items() if v is not None})
    # This installer only manages the local provider, so it always pins
    # SttProvider - but unlike the old speaches-only installer, it does NOT
    # touch SttBaseUrl/SttApiKey, which are meaningless for local and might
    # still be in use by a save someone previously configured for speaches/openai.
    merged["SttProvider"] = "local"
    try:
        with open(cfg_path, "w", encoding="utf-8") as handle:
            json.dump(merged, handle, indent=2, ensure_ascii=False)
        log(f"[config] Wrote {cfg_path}")
    except OSError as exc:
        log(f"[config] FAILED to write {cfg_path}: {exc}")


def configure_all_saves(state: dict, log) -> None:
    paths = find_voice_configs(log)
    for path in paths:
        write_voice_config(path, state, log)
    if not paths:
        log("[config] No VoxMagica saves to configure; install the mod in a save first.")


# ---------------------------------------------------------------------------
# Orchestrator used by the GUI (runs on a worker thread)
# ---------------------------------------------------------------------------


class Installer:
    def __init__(self, state: dict, log):
        self.state = state
        self.log = log

    def install(self) -> None:
        """Pre-downloads the selected local whisper model. Named to match the GUI's
        "Download / Update model" button and the older install() naming this replaces."""
        self.state["configured_at"] = time.strftime("%Y-%m-%d %H:%M:%S")
        prefetch_local_model(self.state, self.log)
        save_state(self.state)

    def configure(self) -> None:
        configure_all_saves(self.state, self.log)

    def ensure_jars(self) -> None:
        ensure_mod_jars(self.log)


# ---------------------------------------------------------------------------
# GUI
# ---------------------------------------------------------------------------


def build_ui() -> None:
    import tkinter as tk
    from tkinter import ttk, messagebox

    state = load_state()
    log_q = queue.Queue()

    def emit(text: str) -> None:
        try:
            log_text.insert(tk.END, text + "\n")
            log_text.see(tk.END)
        except Exception:
            pass

    def pump_queue() -> None:
        try:
            while True:
                text = log_q.get_nowait()
                emit(text)
        except queue.Empty:
            pass
        root.after(100, pump_queue)

    def log(text: str, progress: bool = False) -> None:
        log_q.put(text)

    root = tk.Tk()
    root.title(f"{APP_NAME} v{APP_VERSION}")
    root.geometry("640x520")
    root.minsize(560, 440)

    main = ttk.Frame(root, padding=12)
    main.pack(fill=tk.BOTH, expand=True)

    ttk.Label(main, text="Speech-to-text runs in-process inside the mod - no server, "
                        "Docker, or Python needed.", foreground="#666").pack(anchor=tk.W)

    # --- language + model
    lang_labels = {code: label for code, label in LANGUAGES}
    lang_to_code = {label: code for code, label in LANGUAGES}
    pick_frame = ttk.LabelFrame(main, text="Language & voice model", padding=8)
    pick_frame.pack(fill=tk.X, pady=(8, 0))

    ttk.Label(pick_frame, text="Spoken language:").grid(row=0, column=0, sticky=tk.W)
    lang_var = tk.StringVar(value=lang_labels.get(state.get("language") or "", lang_labels[""]))
    lang_combo = ttk.Combobox(pick_frame, textvariable=lang_var, state="readonly", width=22,
                              values=[label for _, label in LANGUAGES])
    lang_combo.grid(row=0, column=1, sticky=tk.W, padx=(4, 12))
    ttk.Label(pick_frame, text="(Auto-detect = mixed-language transcription)", foreground="#666").grid(
        row=0, column=2, sticky=tk.W)

    ttk.Label(pick_frame, text="Whisper model:").grid(row=1, column=0, sticky=tk.W, pady=(8, 0))
    model_labels = [(f"[{code}] {desc}") for code, desc in MODELS] + [AUTO_MODEL[1]]
    model_var = tk.StringVar()
    model_combo = ttk.Combobox(pick_frame, textvariable=model_var, state="readonly",
                               width=48, values=model_labels)
    model_combo.grid(row=1, column=1, columnspan=2, sticky=tk.W, padx=(4, 12), pady=(8, 0))
    ttk.Label(pick_frame, text="Bigger models are more accurate but slower and use more memory. "
                               "\"Recommended (auto)\" lets the mod choose a matching variant.",
              foreground="#666", wraplength=560, justify=tk.LEFT).grid(
        row=2, column=0, columnspan=3, sticky=tk.W, pady=(4, 0))

    manual_model = {"flag": False}

    def set_model_by_code(code: str) -> None:
        if not code:
            manual_model["flag"] = False
            model_var.set(model_labels[-1])
            return
        for i, (model_code, _) in enumerate(MODELS):
            if model_code == code:
                manual_model["flag"] = True
                model_var.set(model_labels[i])
                return
        manual_model["flag"] = False
        model_var.set(model_labels[-1])

    base_en_index = next(i for i, (code, _) in enumerate(MODELS) if code == "base.en")

    def on_language(*_):
        if manual_model["flag"]:
            return  # user explicitly picked a model; don't override
        code = lang_to_code.get(lang_combo.get(), "")
        model_var.set(model_labels[base_en_index] if code == "en" else model_labels[-1])

    lang_combo.bind("<<ComboboxSelected>>", on_language)
    model_combo.bind("<<ComboboxSelected>>",
                     lambda _e: manual_model.update(flag=True))
    set_model_by_code(state.get("model") or "")

    # --- actions
    actions = ttk.Frame(main)
    actions.pack(fill=tk.X, pady=(12, 8))
    btn = {}
    btn["install"] = ttk.Button(actions, text="Download / Update model",
                                command=lambda: worker("install"))
    btn["configure"] = ttk.Button(actions, text="Configure VoxMagica",
                                  command=lambda: worker("configure"))
    btn["jars"] = ttk.Button(actions, text="Ensure mod jar",
                             command=lambda: worker("ensure_jars"))
    for i, key in enumerate(["install", "configure", "jars"]):
        btn[key].grid(row=0, column=i, padx=4, pady=4, sticky=tk.EW)
        actions.columnconfigure(i, weight=1)

    # --- log
    ttk.Label(main, text="Log").pack(anchor=tk.W)
    log_frame = ttk.Frame(main)
    log_frame.pack(fill=tk.BOTH, expand=True)
    scroll = ttk.Scrollbar(log_frame, orient=tk.VERTICAL)
    log_text = tk.Text(log_frame, height=12, wrap=tk.WORD, yscrollcommand=scroll.set)
    scroll.config(command=log_text.yview)
    scroll.pack(side=tk.RIGHT, fill=tk.Y)
    log_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

    def snapshot_state() -> dict:
        sel = model_combo.get()
        model = ""
        for code, _ in MODELS:
            if f"[{code}]" in sel:
                model = code
                break
        new_state = dict(state)
        new_state["language"] = lang_to_code.get(lang_var.get(), "")
        new_state["model"] = model
        return new_state

    def worker(action: str) -> None:
        for key in btn:
            btn[key].state(["disabled"])
        if action in ("install", "configure"):
            state.update(snapshot_state())
        try:
            if action == "install":
                Installer(snapshot_state(), log).install()
            elif action == "configure":
                Installer(snapshot_state(), log).configure()
            elif action == "ensure_jars":
                Installer(snapshot_state(), log).ensure_jars()
            log("DONE")
        except Exception as exc:  # noqa: BLE001
            log(f"ERROR: {exc}")
        root.after(0, lambda: _reenable(btn))

    def _reenable(btn_dict):
        for key in btn_dict:
            btn_dict[key].state(["!disabled"])

    # run actions off the UI thread so long downloads don't freeze the window
    def thread(action):
        threading.Thread(target=worker, args=(action,), daemon=True).start()

    for key in btn:
        btn[key].config(command=(lambda a=key: thread(a)))

    root.after(100, pump_queue)
    emit("Ready. Choose a language and model, then Download / Update model.")
    root.mainloop()


# ---------------------------------------------------------------------------
# Selftest (headless) path - exercises logic without opening a window
# ---------------------------------------------------------------------------


def selftest() -> int:
    print("== VoxMagica launcher selftest ==")
    state = default_state()
    state["language"] = "es"
    state["model"] = "base"

    def log(text, progress=False):
        print(text)

    # 1. jar bundling source exists (Hexcode is not distributed with the app)
    for name in (MOD_JAR_SOURCE,):
        src = bundled_root() / name
        print(f"[jars] {name}: {'OK' if src.exists() else 'MISSING'} -> {src}")

    # 2. model catalog sanity - every entry has a plausible size and a 64-hex-char sha256,
    # and every GUI-listed model code exists in the catalog (catches copy-paste drift).
    for name, (size_bytes, sha256_hex) in LOCAL_MODELS.items():
        assert size_bytes > 0, name
        assert len(sha256_hex) == 64 and all(c in "0123456789abcdef" for c in sha256_hex), name
    for code, _ in MODELS:
        assert code in LOCAL_MODELS, f"MODELS entry '{code}' missing from LOCAL_MODELS"
    print(f"[model] catalog OK: {len(LOCAL_MODELS)} models")

    # 3. config writer over a temp structure
    import tempfile
    tmp = Path(tempfile.mkdtemp(prefix="voxmagica-selftest-"))
    save_a = tmp / "Saves" / "VoxMagica" / "mods" / MOD_NAMESPACE_DIR
    save_a.mkdir(parents=True)
    cfg = save_a / CONFIG_FILE_NAME
    cfg.write_text(json.dumps({"SttProvider": "speaches", "SttModel": "old",
                               "SttBaseUrl": "http://localhost:8000"}), encoding="utf-8")

    # Point the writer at the temp tree by patching module-level path fns.
    global saves_dir, mods_dir, hytale_userdata  # noqa: PLW0603
    _orig_saves = saves_dir
    _orig_mods = mods_dir
    _orig_userdata = hytale_userdata

    def fake_userdata():
        return tmp

    hytale_userdata = fake_userdata  # noqa: F811
    found = find_voice_configs(log)
    for path in found:
        write_voice_config(path, state, log)
    written = json.loads(cfg.read_text(encoding="utf-8"))
    assert written["SttProvider"] == "local", written
    assert written["SttModel"] == "base", written
    assert written["SttLanguage"] == "es", written
    assert written["MultiCastDelayMs"] == 250, written
    # SttBaseUrl must survive untouched from the pre-existing file - local doesn't use it,
    # but a save previously configured for speaches shouldn't lose that value silently.
    assert written["SttBaseUrl"] == "http://localhost:8000", written
    print("[config] writer OK:", written)

    # 4. local_models_dir() resolves under the (faked) UserData tree without error, on both
    # platform branches - pure path logic, doesn't need a real Linux runtime to sanity-check.
    global IS_WINDOWS, IS_LINUX  # noqa: PLW0603
    _orig_is_windows, _orig_is_linux = IS_WINDOWS, IS_LINUX
    for is_windows in (True, False):
        IS_WINDOWS, IS_LINUX = is_windows, not is_windows
        models_dir = local_models_dir()
        assert models_dir == tmp / "VoxMagicaData" / "whisper-models", models_dir
    IS_WINDOWS, IS_LINUX = _orig_is_windows, _orig_is_linux
    print("[paths] local_models_dir() OK on both platform branches")

    # restore
    saves_dir = _orig_saves  # noqa: F811
    mods_dir = _orig_mods  # noqa: F811
    hytale_userdata = _orig_userdata  # noqa: F811
    print("[selftest] PASS")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    if not TK_AVAILABLE:
        print("Tkinter is not available in this Python; cannot open the GUI.",
              file=sys.stderr)
        return 2
    build_ui()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
