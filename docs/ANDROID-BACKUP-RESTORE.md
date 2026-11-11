# ArbixG backup and restore — security design

## First implementation stage

The Android project now has a device-independent encrypted backup envelope in
`BackupCrypto.kt`. It uses:

- AES-256-GCM authenticated encryption.
- A random 128-bit salt and 96-bit nonce for every backup.
- PBKDF2-HMAC-SHA-256 with 310,000 iterations to derive the encryption key.
- Authenticated file header and tamper detection.
- A user-supplied password (minimum 12 characters); the password is not stored.

The encrypted file is intended to be exported/imported with Android's Storage
Access Framework, so the user can transfer it directly between phones without
GitHub, a PC, or a paid server.

## Important limits — not a finished backup screen

This is the cryptographic file format only. The export/import screen and data
serialization/restore wiring still need to be implemented and tested on a real
Android device. Do not describe backup/restore as available in the released app
until that work is complete.

The payload must contain only explicitly supported app data. Never include OKX
API secrets, API passphrases, private keys, Android Keystore keys, or tokens.
Those credentials must be entered again on a replacement phone. The public
GitHub repository must never be used to store private backups.

## Next integration steps

1. Define a versioned JSON schema for settings and trade history.
2. Add export/import through ACTION_CREATE_DOCUMENT and ACTION_OPEN_DOCUMENT.
3. Validate the entire backup before applying it; use a database transaction
   and explicit restore confirmation to avoid partial or destructive restores.
4. Add unit tests for wrong passwords, tampering, truncated files, and round-trip.
5. Run Android CI and test export/restore on two physical phones.
6. Consider optional encrypted cloud sync only after selecting a private storage
   provider and confirming its cost and access model.
