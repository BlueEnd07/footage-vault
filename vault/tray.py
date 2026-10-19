"""System-tray icon: the app runs in the background with no console window."""
import sys
import threading
import time
import webbrowser
import winreg
from pathlib import Path

import pystray
from PIL import Image

from . import db, worker
from .config import ROOT, STATIC

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "FootageVault"


# ---------- start with Windows (per-user Run key, no admin needed) ----------

def _command() -> str:
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    return f'"{pythonw}" "{ROOT / "run.py"}"'


def autostart_enabled() -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            return winreg.QueryValueEx(k, RUN_NAME)[0] == _command()
    except OSError:
        return False


def set_autostart(on: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            winreg.SetValueEx(k, RUN_NAME, 0, winreg.REG_SZ, _command())
        else:
            try:
                winreg.DeleteValue(k, RUN_NAME)
            except FileNotFoundError:
                pass


# ---------- tray ----------

def _summary() -> str:
    if worker.state["user_paused"]:
        return "Uploads paused"
    up = db.row("SELECT name, progress FROM videos WHERE status='uploading' LIMIT 1")
    waiting = db.row("SELECT COUNT(*) n FROM videos WHERE status IN ('new','queued')")["n"]
    if up:
        return f"Uploading {int(up['progress'] * 100)}% · {waiting} waiting"
    if worker.state["paused_until"]:
        return f"Daily limit reached · resumes {worker.state['paused_until']:%H:%M}"
    if waiting:
        return f"{waiting} waiting to upload"
    return "All videos backed up"


def run(server, port: int) -> None:
    url = f"http://localhost:{port}"

    def open_app(icon=None, item=None):
        webbrowser.open(url)

    def toggle_pause(icon, item):
        worker.state["user_paused"] = not worker.state["user_paused"]
        icon.update_menu()

    def toggle_autostart(icon, item):
        set_autostart(not autostart_enabled())
        icon.update_menu()

    def quit_app(icon, item):
        server.should_exit = True
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem("Open Footage Vault", open_app, default=True),
        pystray.MenuItem(lambda item: _summary(), None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Pause uploads", toggle_pause, checked=lambda item: worker.state["user_paused"]),
        pystray.MenuItem("Start with Windows", toggle_autostart, checked=lambda item: autostart_enabled()),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Quit", quit_app),
    )
    icon = pystray.Icon("footage-vault", Image.open(STATIC / "icon-192.png"), "Footage Vault", menu)

    def refresh_tooltip():
        while True:
            try:
                icon.title = f"Footage Vault — {_summary()}"[:127]
                icon.update_menu()
            except Exception:
                pass
            time.sleep(5)

    threading.Thread(target=refresh_tooltip, daemon=True).start()
    icon.run()
