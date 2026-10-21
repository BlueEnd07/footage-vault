# Footage Vault

- **Installing for a user?** Follow [AGENTS.md](AGENTS.md) step by step.
- Docs: [README.md](README.md) (overview), [SETUP.md](SETUP.md) (PC setup), [docs/android.md](docs/android.md),
  [docs/pc-guide.md](docs/pc-guide.md), [docs/how-it-works.md](docs/how-it-works.md), [docs/development.md](docs/development.md).

## Working on the code

- Two apps, one YouTube library. The PC app is in `vault/` (Python, FastAPI, SQLite) and `web/` (plain JS, no build
  step). The Android app is in `android/` (Kotlin, Compose).
- Keep the two apps' shared rules in step: upload description format (`Original file:` + `fv:{…}`), date logic
  (`vault/timeguess.py` ↔ `android/.../data/TimeGuess.kt`), and matching by file name + length.
- PC schema: `vault/db.py`, with additive migrations only. Android schema: `android/.../data/Db.kt`.
- Never test on the real library: `VAULT_DATA` + port 8799. Check `counts.uploading` before restarting the PC app.
- Android releases: bump `versionCode`, build, then `gh release create android-v<versionCode> FootageVault.apk`
  ([docs/development.md](docs/development.md)). Releases reach every installed app, so only publish when asked.
- Nothing in `/data/`, `client_secret*.json`, `token.json`, `models/`, `local.properties`, keystores or `*.apk` may be
  committed.
