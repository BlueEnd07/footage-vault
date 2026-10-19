# Footage Vault

- **Installing for a user?** Follow [AGENTS.md](AGENTS.md) step by step.
- User-facing setup guide: [SETUP.md](SETUP.md). Overview: [README.md](README.md).

## Working on the code

- Python 3.11+, FastAPI + SQLite (`vault/`), plain HTML/CSS/JS front end (`web/`, no build step).
- The schema lives in `vault/db.py` (`SCHEMA` + additive migrations in `init()`). Only add columns or tables, never drop
  them: users' databases are upgraded in place.
- Background work runs in `vault/worker.py` threads (previews, uploads, YouTube checks, album sync, YouTube import,
  watched folders) and `vault/faces.py`.
- Never test on the real library: set `VAULT_DATA` to a temp folder and use port 8799 (see AGENTS.md, Rules).
- Nothing in `data/`, `client_secret*.json`, `token.json` or `models/` may be committed.
