# Triangular Arbitrage APK — Initial Technical Audit

## Artifact inspected
- Original filename: `TriangularArbitrage-original.apk`
- Package: `com.arbitri.app`
- Version: `1.3.0` (versionCode 4)
- APK size: 63,299 bytes
- SHA-256: `1458d04e565d943c0b8e2acb0350f11052f77e8758587ff7db9e93ce72d709de`
- Android manifest metadata reports minSdk 23 and targetSdk 35.

## Confirmed architecture
The APK is a small Android WebView application. It contains HTML, CSS and JavaScript assets:
- `assets/index.html`
- `assets/style.css`
- `assets/app.js`
- `assets/market-engine.js`
- `assets/execution-engine.js`
- `assets/risk-engine.js`
- `assets/navigation.js`

The DEX strings identify a Java `MainActivity` and `OkxBridge`. The bridge uses Android WebView JavaScript interfaces, Android Keystore, AES/GCM/NoPadding, HmacSHA256 and HTTP connections. This is evidence of encrypted credential storage and signed OKX requests in the inspected binary; it is not a full security audit.

## Confirmed exchange scope
The inspected implementation is OKX-specific. It calls OKX v5 endpoints for spot tickers, instruments, public order books, account balances/config/trade fees, orders, cancellations and fills. The market socket subscribes to the OKX `books5` channel.

No Binance, Bybit or multi-exchange implementation was found in the inspected assets/DEX strings. Do not advertise multi-exchange support until separate connectors are implemented and tested.

## Existing capabilities visible in the APK
- Spot market loading and WebSocket order-book updates.
- Three-leg route enumeration from a selected start asset.
- Depth-aware route simulation using received book levels.
- OKX account connection and balance display.
- Account-specific spot fee retrieval.
- IOC limit-order execution code.
- Pending-order persistence and reconciliation safeguards.
- Demo/live account mode selection.

## Issues and risks requiring correction/verification
1. A triangular cycle is three independent orders, not an atomic transaction. A partial fill or failure after leg one/two can leave residual assets and market exposure. This must remain a hard stop requiring reconciliation, never be presented as guaranteed execution.
2. The app uses only five order-book levels (`books5`). This can reject otherwise viable routes or fail to model deeper market impact. A production engine should make depth limits explicit and request/consume sufficient depth for the configured trade size.
3. The scanner's fee model assumes fees are deducted in the output currency. The APK checks OKX fee type `0` before automation, but this assumption must be revalidated against actual fills and fee currencies for every order.
4. The scanner's slippage display adds percentage deviations across legs. This is a heuristic display metric, not a mathematically exact end-to-end slippage percentage.
5. The APK contains repeated/legacy function declarations in `app.js`; JavaScript's later declarations override earlier ones. This makes the runtime behavior harder to review and should be consolidated.
6. The app's execution and risk code require independent tests for rounding, lot/tick sizes, minimum sizes, stale books, partial fills, fee currencies, order timeouts and interrupted cycles.
7. The APK does not include a normal Gradle source project or original Java source. The native bridge must be reconstructed or recovered from the original project before a maintainable Android build can be claimed.

## Data-integrity rule
The rebuild must show a clear disconnected/stale/error state and must never substitute demo, cached or fabricated values into a live-data view. Demo mode must be visibly and permanently distinguished from live trading. No live trading should be enabled by default.

## Next engineering steps
1. Establish a reproducible project structure and automated tests for the market/risk core.
2. Rebuild the UI under the ArbixG brand.
3. Reconstruct the Android bridge with strict endpoint allowlists, secure credential handling and request signing.
4. Add exchange connectors only after each connector has contract tests and live read-only validation.
5. Keep automated execution disabled until fill reconciliation and failure recovery are independently tested.
