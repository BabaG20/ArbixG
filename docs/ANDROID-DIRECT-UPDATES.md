# ArbixG direct APK updates

## Current implementation

The native Android app now has an updater foundation in `android/app/src/main/java/com/arbixg/app/update/DirectUpdater.kt` and an **APP UPDATES** panel in the dashboard.

- Checks the HTTPS manifest on app launch and on demand.
- Compares the installed package/version with the release manifest.
- Downloads APKs into app-private cache only.
- Enforces a 100 MiB download cap and HTTPS-only connections/redirects.
- Verifies the APK SHA-256, package ID, versionCode and signing-certificate continuity before opening Android's installer.
- Uses FileProvider and Android's own install confirmation. No silent install is attempted.
- Does not ask for or embed a GitHub token.

## Public update feed required

The source repository `BabaG20/ArbixG` is public. The app is configured to read the manifest from the working branch:

`https://raw.githubusercontent.com/BabaG20/ArbixG/rebuild/okx-triangular-arbitrage-v1/updates/latest.json`

The production `updates/latest.json` and a signed APK release have **not** been published yet. Do not publish a placeholder checksum or a debug APK as a production update. When the first stable release is ready, publish the manifest and matching signed APK in this repository (preferably as a GitHub Release asset) and update the manifest URL to `main` only after the release branch is deliberately merged.

The manifest schema is shown in `updates/latest.example.json`. The `apkUrl` must be a public HTTPS URL for the exact APK whose SHA-256 is in the manifest.

## Signing requirement — do not skip

Android accepts an in-place update only when the new APK is signed with the same signing certificate as the installed APK. The updater explicitly compares signing certificate digests and refuses a mismatch. Do not distribute updates built with a newly generated CI debug key. A stable release keystore must be created/backed up and used for every release; store its credentials only as protected GitHub Actions secrets. Never commit the keystore or passwords.

If the currently installed test APK is debug-signed with an ephemeral CI key, a stable release build will not update it in place. First install the first stable, release-signed build deliberately; subsequent releases can update over the internet without USB while retaining app data.

## Not yet production-ready

- The production `updates/latest.json` and signed APK release are not published.
- Stable release signing key/secrets are not configured.
- The exact signing certificate of the APK currently installed on the user's phone has not been verified.
- Android CI must pass after the updater integration.
- A physical-device update test must verify same-package install, data retention, interrupted download, bad checksum rejection and Android confirmation.
- No release has been published, so the updater must currently report the feed unavailable. Do not claim automatic updates are live until these gates pass.

## Manifest fields

- `packageName`: exactly `com.arbixg.app`
- `versionName`: semantic version string
- `versionCode`: monotonically increasing Android integer
- `minSupportedVersionCode`: lowest installed version allowed to update in place
- `apkUrl`: public HTTPS URL of the signed APK
- `sha256`: SHA-256 of the exact APK bytes
- `releaseNotes`: plain text
