# ArbixG

**Real-Time Cryptocurrency Arbitrage Scanner & Automated Trading Platform**

ArbixG is being rebuilt from the supplied Triangular Arbitrage Android APK. The first inspected build is an Android WebView app with an OKX-only spot scanner and execution bridge.

## Current status

- [x] Original APK received and fingerprinted.
- [x] Web assets and JavaScript engines inspected.
- [x] OKX-only scope identified.
- [x] Initial technical audit recorded in [AUDIT/APK-INITIAL-AUDIT.md](AUDIT/APK-INITIAL-AUDIT.md).
- [x] Standalone depth-aware market engine and initial Node tests added.
- [ ] Rebuild full Android source project and secure native exchange bridge.
- [ ] Modernize the ArbixG mobile UI.
- [ ] Verify calculations against recorded exchange book/fill fixtures.
- [ ] Validate OKX read-only connectivity on-device.
- [ ] Add further exchanges only after independent connector tests.
- [ ] Enable live execution only after end-to-end fill and recovery tests.

## Data integrity

Live values must come from exchange responses and must be visibly marked stale/disconnected when unavailable. Demo data must never be shown as live. No fabricated opportunities, balances, prices, fees or P&L are permitted.

## Safety

A three-leg arbitrage cycle is not atomic. Partial fills can leave residual asset exposure. Live automated trading remains disabled until order-state recovery, fee-currency handling, balance reconciliation and risk limits are independently verified.

## Development

Requires Node.js 20 or later.

```sh
npm test
```
