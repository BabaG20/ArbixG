# ArbixG

Android-first OKX Spot triangular-arbitrage research app.

## Current implementation

- Android fetches the full OKX public SPOT instrument catalogue and full SPOT ticker snapshot, filters instruments whose exchange state is `live`, and maps every valid ticker into a market graph.
- The Android triangular scanner enumerates directed three-market cycles across all currencies in that snapshot, not a fixed four-pair watchlist. It excludes cycles reusing a market, deduplicates rotations, and ranks positive gross top-of-book candidates.
- The dashboard shows the count of live markets scanned and the top 100 gross candidates from each complete snapshot. It refreshes every 15 seconds.
- Node engine foundation includes OKX REST client, reconnecting public WebSocket adapter, directed route scanner, and depth-aware three-leg simulator.
- Room local trade-history schema, DataStore settings foundation and Android Keystore AES-GCM credential vault.
- GitHub Actions workflows for Node tests and Android debug APK assembly.

## Important interpretation

A candidate shown by the Android scanner is **gross top-of-book discovery only**. It is not a verified arbitrage and must not be treated as expected profit. It has not yet deducted the user's confirmed account-specific fee schedule, validated three current order books, checked depth/lot/minimum/notional constraints, modeled exact fee currency and rounding, checked balances, or accounted for latency and partial fills. The UI explicitly labels these candidates unverified. A zero candidate count means none passed the current snapshot's gross-positive filter; it does not prove no opportunity existed between snapshots.

## Not implemented / execution remains locked

- Android WebSocket market feed integration and robust snapshot/sequence/checksum reconciliation.
- Android order-book cache and per-candidate depth validation against fresh books.
- Authenticated account balance retrieval, actual account fee-tier retrieval and exact exchange fee-currency behavior.
- Demo Trading authenticated order submission, idempotent order state machine, fill reconciliation, cancellation and partial-fill recovery.
- Position/exposure controls, per-cycle and daily loss limits, stale-data guard, slippage caps, kill switch and restart recovery.
- Production signing, release distribution and a verified automatic phone update mechanism.
- Physical-device network, performance and install/update tests.

## Safety

No API credentials are committed. No orders are sent. Automatic trading is disabled. Do not enable live trading based on gross candidates. Use API keys without withdrawal permission, with IP restrictions and least privilege. Live execution must remain disabled until exchange order lifecycle and all risk/recovery controls are implemented and tested.

## Development

Node.js 20+:

```sh
npm test
```

Android debug build:

```sh
gradle :android:app:assembleDebug
```

The new Android application ID is `com.arbixg.app`, intentionally distinct from legacy `com.arbitri.app` to allow side-by-side installation without overwriting the old app.
