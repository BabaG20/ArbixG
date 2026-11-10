# ArbixG

An Android-first triangular arbitrage research platform, initially targeting OKX Spot.

## Implemented foundations

- OKX public REST client for spot instruments, tickers, and order books.
- Native Android dashboard polling verified OKX public tickers for BTC-USDT, ETH-USDT, SOL-USDT, and XRP-USDT every 15 seconds. It displays exchange-returned last/bid/ask values and timestamps, and reports disconnection/errors instead of substituting sample prices.
- Reconnecting OKX public WebSocket adapter with subscription replay, heartbeat, status reporting, and bounded exponential reconnect. The Android screen currently uses REST polling; this WebSocket adapter is not yet wired into the Android UI.
- Directed three-market route scanner using top-of-book bid/ask conversion.
- Depth-aware three-leg simulator that consumes order-book levels, applies an explicitly supplied fee, and rejects stale or insufficient books.
- Room local trade-history schema and DataStore settings foundation.
- Android Keystore AES-GCM credential vault foundation.
- CI workflows for Node tests and Android debug APK assembly.

## Not implemented — do not treat this as a trading bot

- The Android app does not yet scan and display executable triangular routes.
- No authenticated account balances, fee-tier retrieval, order submission, demo order engine, live trading, or automatic execution.
- No WebSocket snapshot/sequence reconciliation or robust order-book cache.
- No partial-fill recovery, idempotent order state machine, kill switch, exposure limits, or exchange-side reconciliation.
- The current depth simulator applies a simplified output-asset fee deduction. OKX fee-currency behavior, actual account fee tier, and exact rounding/precision rules must be verified before using its result for trade decisions.
- Unit tests use deterministic synthetic fixtures; they are not live exchange tests and do not prove market profitability.
- GitHub Actions can build an APK artifact, but automatic installation/update on a phone is not implemented.

## Safety principles

1. Public market data only until the data pipeline is verified.
2. Never display missing/stale market data as zero or fabricate an opportunity.
3. Require actual fee tier, instrument rules, depth, slippage, and balance checks.
4. Keep API secrets encrypted with Android Keystore; never commit keys.
5. Never request withdrawal permission. Use IP restrictions and least-privilege API keys.
6. Live execution remains disabled until order lifecycle, recovery, and risk controls are tested.

## Development

Requires Node.js 20 or newer:

```sh
npm test
```

Android debug build:

```sh
gradle :android:app:assembleDebug
```

The Android application ID is `com.arbixg.app`, intentionally distinct from the legacy `com.arbitri.app` package so the rebuilt app can be installed side-by-side without replacing the old installation.
