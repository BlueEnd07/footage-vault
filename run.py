import threading
import webbrowser

import uvicorn

from vault.config import load_settings
from vault.main import app, lan_ip

if __name__ == "__main__":
    s = load_settings()
    port = s["port"]
    print("\n  Footage Vault is running")
    print(f"  On this PC:  http://localhost:{port}")
    print(f"  On phone:    http://{lan_ip()}:{port}   (same Wi-Fi, PIN {s['pin']})")
    print("  Keep this window open. Close it to stop.\n")
    threading.Timer(1.5, lambda: webbrowser.open(f"http://localhost:{port}")).start()
    # 0.0.0.0 so the phone on the same Wi-Fi can reach it; other devices need the PIN.
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")
