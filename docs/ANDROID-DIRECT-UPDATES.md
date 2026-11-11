# ArbixG direct APK updates and permanent release signing

## App updater
- Reads a public HTTPS update manifest.
- Downloads APK privately and verifies SHA-256, package ID, versionCode and signing-certificate continuity.
- Opens Android package installer for user confirmation; Android does not permit this flow to silently install.

## Permanent signing
The release workflow requires four protected GitHub Actions secrets:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Generate the keystore once, keep a secure offline backup, and reuse it for every release. Never commit the keystore or passwords. The release workflow fails closed if a secret is missing; it never falls back to a temporary debug key.

## Publishing
After setting the four secrets and confirming package/version compatibility, update `versionName` and increment `versionCode` in `android/app/build.gradle.kts`, then push a tag such as `v1.0.0`. The workflow runs tests, builds a signed release APK, publishes the stable asset `ArbixG.apk`, computes SHA-256, and updates the public manifest.

Stable APK URL:
`https://github.com/BabaG20/ArbixG/releases/latest/download/ArbixG.apk`

Manifest URL:
`https://raw.githubusercontent.com/BabaG20/ArbixG/release/secure-signing-bootstrap/updates/latest.json`

## Migration safety
This branch uses package ID `com.arbixg.app`. Before replacing an existing installation, inspect its exact package name, versionCode and signing-certificate SHA-256. A different package ID or signing certificate prevents in-place update. Do not uninstall until app data has been backed up and the migration plan is confirmed.

## Not live yet
The pipeline is prepared but not live until the keystore is created, the four GitHub secrets are added, a signed release is published, and an update is tested on a physical phone.