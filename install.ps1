<#
  Footage Vault installer for Windows 10/11. Safe to run again (it only adds what's missing).

    powershell -ExecutionPolicy Bypass -File install.ps1            # install, then start the app
    powershell -ExecutionPolicy Bypass -File install.ps1 -NoStart   # install only

  What it does:
    1. Makes sure Python 3.11+ and ffmpeg are installed (via winget, per-user, no admin).
    2. Creates .venv and installs requirements.txt.
    3. Downloads the two face-recognition models into models\ (once, ~40 MB, from OpenCV's model zoo).
    4. Checks for client_secret.json (the user's own Google OAuth key; see SETUP.md section 2).
    5. Starts the app in the system tray (unless -NoStart). First start opens http://localhost:8765.

  Exit codes: 0 = ready (client_secret.json may still be missing: see the last message), 1 = failed.
#>
param([switch]$NoStart)
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "    OK  $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "    !!  $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "    XX  $msg" -ForegroundColor Red; exit 1 }

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [Environment]::GetEnvironmentVariable("Path", "User") + ";" +
                "$env:LOCALAPPDATA\Microsoft\WinGet\Links"
}

function Find-Python {
    # Real interpreters only (skips the Microsoft Store "python" stub that opens the Store).
    foreach ($cmd in @("py -3", "python", "python3")) {
        $exe, $arg = $cmd.Split(" ", 2)
        if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { continue }
        try {
            $v = & $exe $arg -c "import sys; print('%d.%d' % sys.version_info[:2])" 2>$null
            if ($LASTEXITCODE -eq 0 -and $v -and [version]$v -ge [version]"3.11") { return @($exe, $arg, $v) }
        } catch {}
    }
    return $null
}

function Winget-Install($id, $what) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Fail "$what is missing and winget isn't available. Install $what yourself, then run this again."
    }
    Write-Host "    installing $what with winget ($id)..."
    winget install --id $id -e --scope user --silent --accept-package-agreements --accept-source-agreements | Out-Host
    if ($LASTEXITCODE -ne 0) {
        # some packages don't offer a per-user install; try the default scope
        winget install --id $id -e --silent --accept-package-agreements --accept-source-agreements | Out-Host
    }
    Refresh-Path
}

# ---------------------------------------------------------------- 1. Python + ffmpeg
Step "Checking Python 3.11+"
$py = Find-Python
if (-not $py) { Winget-Install "Python.Python.3.12" "Python 3.12"; $py = Find-Python }
if (-not $py) { Fail "Python 3.11+ still not found. Install it from https://www.python.org/downloads/ (tick 'Add to PATH') and run this again." }
Ok "Python $($py[2])"

Step "Checking ffmpeg"
Refresh-Path
if (-not ((Get-Command ffmpeg -ErrorAction SilentlyContinue) -and (Get-Command ffprobe -ErrorAction SilentlyContinue))) {
    Winget-Install "Gyan.FFmpeg" "ffmpeg"
}
if (-not (Get-Command ffprobe -ErrorAction SilentlyContinue)) {
    Fail "ffmpeg still not found. Install it (winget install Gyan.FFmpeg), open a new terminal and run this again."
}
Ok ((ffmpeg -hide_banner -version | Select-Object -First 1) -replace "Copyright.*", "")

# ---------------------------------------------------------------- 2. venv + packages
Step "Setting up the Python environment (.venv)"
$venvPy = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPy)) {
    if ($py[1]) { & $py[0] $py[1] -m venv .venv } else { & $py[0] -m venv .venv }
    if ($LASTEXITCODE -ne 0) { Fail "Couldn't create .venv" }
}
& $venvPy -m pip install --disable-pip-version-check -q --upgrade pip | Out-Null
& $venvPy -m pip install --disable-pip-version-check -q -r requirements.txt
if ($LASTEXITCODE -ne 0) { Fail "pip install -r requirements.txt failed (see above)" }
Ok "packages installed"

# ---------------------------------------------------------------- 3. face models
Step "Downloading face-recognition models (one time)"
& $venvPy -c "from vault import faces; import sys; sys.exit(0 if faces.ensure_models() else 1)"
if ($LASTEXITCODE -eq 0) { Ok "models ready in models\" }
else { Warn "Couldn't download the face models now. The app retries by itself later; everything else works." }

# ---------------------------------------------------------------- 4. Google key
Step "Checking for the Google key (client_secret.json)"
$hasKey = Test-Path (Join-Path $PSScriptRoot "client_secret.json")
if (-not $hasKey) {
    $dl = Get-ChildItem "$env:USERPROFILE\Downloads" -Filter "client_secret_*.apps.googleusercontent.com.json" -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($dl) {
        Copy-Item $dl.FullName (Join-Path $PSScriptRoot "client_secret.json")
        $hasKey = $true
        Ok "found $($dl.Name) in Downloads and copied it as client_secret.json"
    }
}
if ($hasKey) { Ok "client_secret.json present" }
else { Warn "client_secret.json is missing. Follow SETUP.md section 2 (about 15 minutes), save the file in this folder, then click Connect YouTube in the app." }

# ---------------------------------------------------------------- 5. start
if ($NoStart) {
    Step "Installed. Start it any time by double-clicking start.bat"
} else {
    Step "Starting Footage Vault (tray icon near the clock)"
    Start-Process -FilePath (Join-Path $PSScriptRoot ".venv\Scripts\pythonw.exe") -ArgumentList "run.py" -WorkingDirectory $PSScriptRoot
    Ok "running: open http://localhost:8765"
}
if (-not $hasKey) { Write-Host "`nNEXT: create your Google key (SETUP.md section 2), then click Connect YouTube." -ForegroundColor Yellow }
else { Write-Host "`nNEXT: open http://localhost:8765 and click Connect YouTube." -ForegroundColor Green }
exit 0
