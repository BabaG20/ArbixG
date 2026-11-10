# ArbixG

Android-first OKX Spot triangular-arbitrage research app.

## Current implementation

- Android fetches the full OKX public SPOT instrument catalogue and full SPOT ticker snapshot, filters instruments whose exchange state is `live`, and maps every valid ticker into a market graph.
- The Android triangular scanner enumerates directed three-market cycles across all currencies in that snapshot, not a fixed four-pair watchlist. It excludes cycles reusing a market, deduplicates rotations, and ranks positive gross top-of-book candidates.
- The dashboard shows the count of live markets scanned and the top 100 gross candidates from each complete snapshot. It refreshes every 15 seconds.
- Node engine foundation includes OKX REST client, reconnecting public WebSocket adapter, directed route scanner, and depth-aware three-leg simulator.
- Room local trade-history schema, DataStore settings foundation and Android Keystore AES-GCM credential vault.
- Android validates fresh public L2 order books for up to 10 selected USDT cycles (4 concurrent requests), floors base quantities to each live instrument's `lotSz` with `BigDecimal`, re-simulates all three legs, checks `minSz`/`tickSz`, and reports residual dust separately from proceeds.
- Read-only authenticated OKX demo account client signs requests with HMAC-SHA256, stores API credentials encrypted with Android Keystore, and retrieves account level, balances and per-instrument Spot maker/taker fee rates. It can query current pending Spot orders; it does not submit or modify orders.
- Order-fill reconciliation handles cumulative partial fills, remaining quantity, fees, stale snapshots, overfills and terminal states. A Room-backed `tracked_orders` journal persists observed order states, and reconnect recovery refreshes locally stored non-terminal orders from the read-only OKX Demo API. Entity round-trip tests cover persisted decimal precision. This journal tracks orders discovered from the pending-order endpoint or previously saved locally; the app still does not create orders.
- GitHub Actions workflows for Node tests and Android debug APK assembly.

## Important interpretation

A candidate shown by the Android scanner is **gross top-of-book discovery only**. It is not a verified arbitrage and must not be treated as expected profit. Selected USDT candidates receive a separate public-book depth simulation with lot/minimum/tick validation, but the dashboard does not yet combine authenticated account fee currency/tier, balances and those simulations into an executable net-profit result. OKX Spot `minNotional` is not exposed in the loaded metadata and remains unverified. Fee currency behavior, latency, and full partial-fill recovery are not yet modeled end-to-end. The UI explicitly labels these candidates unverified. A zero candidate count means none passed the current snapshot's gross-positive filter; it does not prove no opportunity existed between snapshots.

## Not implemented / execution remains locked

- Android WebSocket market feed integration and robust snapshot/sequence/checksum reconciliation.
- Android order-book cache and per-candidate depth validation against fresh books.
- Validation of the authenticated read-only demo account client against a user-created OKX demo API key on a physical device; fee-currency behavior and account fee application are not yet verified end-to-end.
- Demo Trading order submission, cancel/replace, end-to-end lifecycle for app-created orders, idempotent execution intents, partial-fill recovery/unwind, and complete historical reconciliation of orders that disappear from the pending-order endpoint. The local journal recovers orders it has already observed, but does not yet provide an execution coordinator.
- Position/exposure controls, per-cycle and daily loss limits, execution-grade stale-data guard, slippage caps and kill switch. Local recovery of previously observed non-terminal orders is implemented, but full execution recovery is not.
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
