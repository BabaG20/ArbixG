# ArbixG Android one-tap update design

## User experience

1. The installed app checks a version manifest over HTTPS when the user opens the app (and optionally on an explicit Check for updates action).
2. The app compares the manifest's Android `versionCode` with the installed package.
3. If a newer compatible release exists, ArbixG shows the version, release notes and download size.
4. The user taps **Download update**. The app downloads the APK to app-private storage and verifies its SHA-256 against the manifest.
5. The native Android layer launches the Android package installer. Android displays its system confirmation UI; the user approves installation.
6. On next launch, ArbixG verifies the installed package/version and reports success.

This is one-tap to start the install flow, not silent installation. Ordinary Android apps cannot silently replace themselves. The system installer confirmation is mandatory unless the device is managed with privileged enterprise installation rights.

## Manifest contract

The HTTPS manifest is JSON with these required fields:

- `packageName`: must exactly match `com.arbitri.app` so the update cannot target another package.
- `versionName`: human-readable release version.
- `versionCode`: monotonically increasing Android integer.
- `minSupportedVersionCode`: minimum installed version allowed to update in-place.
- `apkUrl`: HTTPS URL for the signed APK.
- `sha256`: 64-character hexadecimal SHA-256 digest of the exact APK bytes.
- `releaseNotes`: optional plain-text summary.

The validation logic lives in `updates/update-client.js`; it does not fetch URLs, download APKs or install packages.

## Native Android integration still required

The current repository does not yet contain the original Gradle Android source project or the native `MainActivity`/WebView bridge source. The APK inspection found a Java activity and an OKX bridge, but a binary is not a maintainable Android source project. Therefore the following must be implemented and device-tested in the reconstructed Android host:

- Fetch the manifest using an HTTPS-only endpoint allowlist and strict timeouts.
- Download to app-private storage; enforce a maximum APK size and reject redirects to non-HTTPS hosts.
- Compute SHA-256 locally and compare in constant time before install.
- Check package name, signing certificate continuity and versionCode before launching the installer.
- Use Android's package installer flow (PackageInstaller or ACTION_VIEW with a narrowly scoped FileProvider URI) and handle the required user confirmation.
- Handle Android's per-app “Install unknown apps” permission only when required, with a clear explanation and settings deep-link.
- Re-check installed version after returning from the installer; never report success before Android confirms the new version.
- Keep the existing app data by retaining package ID `com.arbitri.app` and the same signing certificate.

## Release hosting and signing prerequisites

The GitHub repository is currently private. The Android app must not contain a GitHub personal access token or any other repository secret. Private GitHub release URLs are not a safe public updater endpoint for an installed app.

Before enabling production updates, choose a public HTTPS distribution endpoint (for example, a controlled object-storage/CDN bucket) and configure it as a protected GitHub Actions deployment secret. The APK signing key must be backed up securely and stored only as encrypted repository secrets or in a dedicated signing service. Never commit keystores, passwords, API tokens or signing credentials.

A new signing key cannot update the already-installed APK unless it matches the original signing certificate. The original APK's signing certificate must be extracted and compared first. If the matching private signing key is unavailable, Android will reject an in-place update; a migration/reinstall plan would be needed and could affect app data.

## Release gates

Do not publish an update until all of these pass:

- Reproducible Android release build.
- Package ID and signing-certificate continuity verified against the installed app.
- Increasing versionCode.
- APK SHA-256 matches the published manifest.
- Install/update tested on the user's Android device without clearing app data.
- Rollback and interrupted-download behavior tested.

Until these prerequisites are met, CI may test the update decision logic, but it must not claim that phone auto-updates are live.
