"""Starts Footage Vault in the system tray.  `python run.py --console` runs it in a console instead."""
import logging
import socket
import sys
import threading
import webbrowser

from vault.config import DATA, load_settings, save_settings

# pythonw.exe (no console) has no stdout/stderr; send them to a log file.
if sys.stdout is None or sys.stderr is None:
    sys.stdout = sys.stderr = open(DATA / "vault.log", "a", encoding="utf-8", buffering=1)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                    handlers=[logging.FileHandler(DATA / "vault.log", encoding="utf-8")])

import uvicorn  # noqa: E402

from vault.main import app, lan_ip  # noqa: E402


def already_running(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def main():
    s = load_settings()
    port = s["port"]
    url = f"http://localhost:{port}"
    if already_running(port):
        webbrowser.open(url)  # second launch just opens the app
        return

    config = uvicorn.Config(app, host="0.0.0.0", port=port, log_level="warning", log_config=None)
    server = uvicorn.Server(config)

    if "--console" in sys.argv:
        print(f"\n  Footage Vault: {url}   phone (same Wi-Fi): http://{lan_ip()}:{port}  PIN {s['pin']}\n")
        server.run()
        return

    from vault import tray
    if not s.get("autostart_initialized"):  # first run: start with Windows by default
        tray.set_autostart(True)
        s["autostart_initialized"] = True
        save_settings(s)
        threading.Timer(2, lambda: webbrowser.open(url)).start()

    threading.Thread(target=server.run, daemon=True, name="server").start()
    tray.run(server, port)


if __name__ == "__main__":
    main()
