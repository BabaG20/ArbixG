# ArbixG

Android-first OKX Spot triangular-arbitrage research app.

## Current implementation

- Android fetches the full OKX public SPOT instrument catalogue and full SPOT ticker snapshot, filters instruments whose exchange state is `live`, and maps every valid ticker into a market graph.
- The Android triangular scanner enumerates directed three-market cycles across all currencies in that snapshot, not a fixed four-pair watchlist. It excludes cycles reusing a market, deduplicates rotations, and ranks positive gross top-of-book candidates.
- The dashboard shows the count of live markets scanned and the top 100 gross candidates from each complete snapshot. It refreshes every 15 seconds.
- Node engine foundation includes OKX REST client, reconnecting public WebSocket adapter, directed route scanner, and depth-aware three-leg simulator.
- Room local trade-history schema, DataStore settings foundation and Android Keystore AES-GCM credential vault.
- Android validates fresh public L2 order books for up to 10 selected USDT cycles (4 concurrent requests), floors base quantities to each live instrument's `lotSz` with `BigDecimal`, re-simulates all three legs, checks `minSz`/`tickSz`, and reports residual dust separately from proceeds.
- Read-only authenticated OKX demo account client signs requests with HMAC-SHA256, stores API credentials encrypted with Android Keystore, and retrieves account level, balances, account fee-charging mode (`feeType`) and per-instrument Spot maker/taker fee rates. Fee requests are batched within the documented endpoint limits. It can query current pending Spot orders; it does not submit or modify orders.
- Selected USDT depth cycles can apply fresh, complete Demo account taker rates per instrument. The simulator models OKX Spot `feeType=0` (fee in received currency) and `feeType=1` (fee in quote currency, reserved from quote input on buys and deducted from quote proceeds on sells). Incomplete or stale fee snapshots leave the cycle gross-only.
- Order-fill reconciliation handles cumulative partial fills, remaining quantity, fees, stale snapshots, overfills and terminal states. A Room-backed `tracked_orders` journal persists observed order states, and reconnect recovery refreshes locally stored non-terminal orders from the read-only OKX Demo API. Entity round-trip tests cover persisted decimal precision. This journal tracks orders discovered from the pending-order endpoint or previously saved locally; the app still does not create orders.
- GitHub Actions workflows for Node tests and Android debug APK assembly.

## Important interpretation

A candidate shown by the Android scanner is **gross top-of-book discovery only**. It is not a verified arbitrage and must not be treated as expected profit. Selected USDT candidates receive a separate public-book depth simulation with lot/minimum/tick validation. When a complete, recent Demo account snapshot is available, per-instrument taker rates and the account's Spot fee-charging mode are applied to produce a fee-adjusted estimate. This estimate is still not an executable or guaranteed net-profit result: balances are displayed but not reserved or used to gate cycle size, OKX Spot `minNotional` is not exposed in the loaded metadata, and latency, slippage beyond visible depth, and full partial-fill recovery are not yet modeled end-to-end. The UI explicitly labels these candidates unverified. A zero candidate count means none passed the current snapshot's gross-positive filter; it does not prove no opportunity existed between snapshots.

## Not implemented / execution remains locked

- Android WebSocket market feed integration and robust snapshot/sequence/checksum reconciliation.
- Android order-book cache and per-candidate depth validation against fresh books.
- Physical-device validation of the authenticated read-only Demo account client and fee-adjusted estimate against a user-created OKX Demo API key; verify returned `feeType`, per-instrument taker tiers, fee currency and simulated outputs against OKX account behavior.
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
