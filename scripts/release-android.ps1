<#
  Builds the Android APK, checks it is signed with the release key, and (with -Publish)
  uploads it to GitHub Releases together with its SHA-256.

    powershell -ExecutionPolicy Bypass -File scripts\release-android.ps1            # build + verify only
    powershell -ExecutionPolicy Bypass -File scripts\release-android.ps1 -Publish   # also create the release

  One-time setup (never commit these files):
    android\keystore.properties   storeFile / storePassword / keyAlias / keyPassword
    android\footage-vault-release.jks

  Publishing reaches every installed app, so only run -Publish when that is intended.
#>
param([switch]$Publish)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"
$gradle = Join-Path $android "gradlew.bat"
$propsFile = Join-Path $android "keystore.properties"
function Find-Sdk {
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    $localProps = Join-Path $android "local.properties"
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $path = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            $path = $path -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path $path) { return $path }
        }
    }
    $default = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $default) { return $default }
    return $null
}
$sdk = Find-Sdk

function Fail($msg) { Write-Host "XX  $msg" -ForegroundColor Red; exit 1 }
function Ok($msg) { Write-Host "OK  $msg" -ForegroundColor Green }

if (-not (Test-Path $propsFile)) {
    Fail "android\keystore.properties is missing. See docs\release.md (one-time setup)."
}
if (-not $env:JAVA_HOME) {
    foreach ($candidate in @("$env:ProgramFiles\Android\Android Studio\jbr",
                             "$env:LOCALAPPDATA\Programs\Android Studio\jbr")) {
        if (Test-Path (Join-Path $candidate "bin\keytool.exe")) { $env:JAVA_HOME = $candidate; break }
    }
}
if (-not $env:JAVA_HOME) { Fail "JAVA_HOME is not set. Install JDK 17 or Android Studio." }
# Gradle's test/worker loopback fails on long Windows temp paths.
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\short\tmp"

$props = @{}
Get-Content $propsFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$store = $props["storeFile"]
if (-not $store) { Fail "keystore.properties has no storeFile. See docs\release.md." }
if (-not [IO.Path]::IsPathRooted($store)) { $store = Join-Path $android $store }
if (-not (Test-Path $store)) { Fail "Signing key not found: $store" }

$buildFile = Join-Path $android "app\build.gradle.kts"
$text = Get-Content -Raw $buildFile
$versionCode = [int]([regex]::Match($text, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$versionName = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$tag = "android-v$versionCode"
if (-not $versionCode) { Fail "Couldn't read versionCode from app\build.gradle.kts" }

Write-Host "`n==> Building Footage Vault $versionName ($versionCode)" -ForegroundColor Cyan
Push-Location $android
try {
    & $gradle :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed" }
} finally { Pop-Location }

$built = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $built)) { Fail "APK not found at $built" }

$outDir = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$apk = Join-Path $outDir "FootageVault.apk"
Copy-Item $built $apk -Force

# 1. The APK must carry the dedicated release certificate, not the debug one.
if (-not $sdk) { Fail "Android SDK not found. Set ANDROID_HOME or install the SDK." }
$apksigner = Get-ChildItem (Join-Path $sdk "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apksigner) { Fail "apksigner not found. Is the Android SDK installed?" }
# Native tools write warnings to stderr; don't let PowerShell treat that as a failure.
$ErrorActionPreference = "Continue"
$certs = (& $apksigner verify --print-certs $apk 2>&1) -join "`n"
$apkExit = $LASTEXITCODE
$keyInfo = (& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore $store -alias $props["keyAlias"] `
    -storepass $props["storePassword"] 2>&1) -join "`n"
$ErrorActionPreference = "Stop"
if ($apkExit -ne 0) { Fail "APK signature check failed: $certs" }
$apkSha1 = [regex]::Match($certs, 'SHA-1 digest:\s*([0-9a-fA-F:]+)').Groups[1].Value.ToUpper()
$normalize = { param($h) ($h -replace '[^0-9A-Fa-f]', '').ToUpper() }
$keySha1 = [regex]::Match($keyInfo, 'SHA1:\s*([0-9A-Fa-f:]+)').Groups[1].Value.ToUpper()
if (-not $apkSha1 -or (& $normalize $apkSha1) -ne (& $normalize $keySha1)) {
    Fail "APK is not signed with the release key (APK $apkSha1, key $keySha1)"
}
Ok "signed with the release key ($apkSha1)"

# 2. SHA-256 for the app's updater (it refuses an APK without a matching checksum).
$sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLower()
$shaFile = "$apk.sha256"
"$sha  FootageVault.apk" | Set-Content -LiteralPath $shaFile -Encoding ascii
Ok "SHA-256 $sha"
Ok "APK: $apk"

if (-not $Publish) {
    Write-Host "`nBuilt and verified. Re-run with -Publish to create the GitHub release." -ForegroundColor Yellow
    exit 0
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail "GitHub CLI (gh) is not installed or not signed in." }
Ok "publishing $tag"
& gh release create $tag $apk $shaFile --title "Footage Vault $versionName (Android)" --generate-notes
if ($LASTEXITCODE -ne 0) { Fail "gh release create failed" }
Ok "published $tag"
