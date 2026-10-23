# Android release key

Published APKs are signed with a dedicated key, not the debug key. This is a one-time setup.

## Files (never commit)

| File | What it is |
|---|---|
| `android/footage-vault-release.jks` | the RSA 4096 signing key, valid until 2054 |
| `android/keystore.properties` | store/key passwords and alias used by Gradle |

Both are ignored by git. **Back them up** somewhere safe (password manager + offline copy). If they are lost,
installed apps can never be updated again; users must uninstall and reinstall, and a new SHA-1 must be registered in
Google Cloud.

To create them from scratch (pick your own passwords):

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore android\footage-vault-release.jks `
  -alias footagevault -keyalg RSA -keysize 4096 -validity 10000
@'
storeFile=footage-vault-release.jks
storePassword=CHANGE_ME
keyAlias=footagevault
keyPassword=CHANGE_ME
'@ | Set-Content android\keystore.properties
```

## Google Cloud (one time per key)

1. https://console.cloud.google.com/auth/clients → **Create client** → **Android**.
2. Package name: `com.footagevault.app`.
3. SHA-1: `49:AA:78:6F:93:FF:A7:98:DB:F5:B5:04:BF:E8:8D:A2:1D:45:A5:BE`
   (verify with `keytool -list -v -keystore android/footage-vault-release.jks -alias footagevault`).
4. Keep the existing Android client (debug SHA-1) too, so development builds can still sign in.
5. Existing installs were signed with the debug key, so updating to a release-signed APK needs a one-time
   uninstall/reinstall. Their videos stay on YouTube; reconnecting Google is enough after reinstalling, and choosing
   **Only new videos** avoids uploading old ones again.

## Building and publishing

```powershell
powershell -ExecutionPolicy Bypass -File scripts\release-android.ps1            # build + verify
powershell -ExecutionPolicy Bypass -File scripts\release-android.ps1 -Publish   # GitHub release
```

The script:

- builds `:app:assembleRelease`,
- refuses the APK unless it is signed by the certificate in `keystore.properties`,
- writes `dist\FootageVault.apk` and `dist\FootageVault.apk.sha256`,
- with `-Publish`, creates tag `android-v<versionCode>` and attaches both files.

The app's updater checks GitHub's `sha256:` digest (or the `.sha256` asset), the package name, the version, and the
signing certificate before Android is asked to install anything.

`-Publish` is public and reaches every installed app. Only run it when a release is intended.
