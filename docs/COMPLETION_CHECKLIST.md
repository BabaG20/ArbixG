# ArbixG — Mandatory Completion Checklist

**Project rule:** Do not add unrelated features until every item below is implemented, tested, and evidenced. Work only on the existing scope. A feature is not complete merely because code exists; its tests and required device/Demo verification must pass.

## 1. Market scanner and opportunity validation
- [ ] Discover and scan all eligible active OKX Spot instruments and triangular cycles.
- [ ] Exclude suspended, unsupported, or otherwise ineligible instruments.
- [ ] Validate live ticker and order-book freshness for every leg.
- [ ] Simulate executable depth across all three legs; account for partial depth and residual dust.
- [ ] Enforce every applicable instrument rule: tick size, lot size, min/max size, min notional and other exchange filters.
- [ ] Calculate conservative net profit after per-instrument fees, fee currency, slippage, spread, rounding and residuals.
- [ ] Mark incomplete/stale fee or market data as non-tradable; never imply gross estimates are net profit.
- [ ] Rank opportunities using executable net return and available capital; suppress duplicates and stale opportunities.

## 2. Persistent account and risk management
- [ ] Read and validate available (not just total) balances from the authenticated account.
- [ ] Reserve required currencies for each cycle and prevent overlapping use of the same funds.
- [ ] Persist reservations, exposure, daily loss and risk state in Room; restore consistently after app/process/device restart.
- [ ] Enforce maximum cycle/order size and maximum aggregate open exposure.
- [ ] Enforce daily realized-loss limit and stop new cycles when reached.
- [ ] Enforce slippage, price deviation, liquidity and market-data age limits.
- [ ] Implement a durable kill switch that blocks new orders and has a defined safe response for open orders.
- [ ] Reconcile local balances and reservations against exchange state; never trust stale local state as exchange truth.

## 3. Three-leg OKX Demo execution engine
- [ ] Implement Demo-only order submission with explicit environment separation; no Live endpoint/key fallback.
- [ ] Execute legs sequentially; leg 2 only after leg 1's confirmed fills and leg 3 only after leg 2's confirmed fills.
- [ ] Size each next leg from actual filled quantity and actual received balance, not planned quantity.
- [ ] Use durable client order IDs/idempotency to prevent duplicate submissions after retries/restarts.
- [ ] Handle partial fills, unfilled remainder, cancel/reject, exchange timeout and network disconnect.
- [ ] On ambiguous submission result, query exchange by client/order ID before any retry.
- [ ] Recover after app/process/device restart and reconcile every open/non-terminal order.
- [ ] Define safe unwind/hedge or stop-and-alert behavior when a leg cannot complete; never assume a cycle can be reversed at the same price.
- [ ] Persist a full cycle journal with requested/filled quantities, average prices, fee amounts/currencies, timestamps and exchange IDs.
- [ ] Verify actual final balances and realized P&L after all fills and fees.

## 4. Demo validation and reliability
- [ ] Test complete three-leg cycles on OKX Demo with real exchange responses.
- [ ] Test partial fills, rejects, insufficient funds, stale books, price movement, rate limits and API errors.
- [ ] Test timeout after submission and before response; prove duplicate orders cannot occur.
- [ ] Test app force-stop, phone restart and network loss during each leg; prove safe recovery.
- [ ] Test fee currencies and account-specific fee tiers against exchange-reported fills.
- [ ] Verify order and balance reconciliation against OKX after every test.
- [ ] Keep evidence/logs with secrets redacted and no fabricated fills or profits.

## 5. Android app, security and release
- [ ] Complete dashboard: live scanner, net opportunity details, balances, active cycles/orders, history, daily P&L and risk status.
- [ ] Provide clear pause/stop controls and visible Demo/Live environment indicator.
- [ ] Keep API credentials in Android Keystore; require least-privilege API permissions and no withdrawal permission.
- [ ] Redact API keys, signatures, passphrases and sensitive account data from logs.
- [ ] Complete Room v1-to-v3 migration tests and verify existing trade history and order journal are preserved.
- [ ] Verify backup/restore and order journal recovery.
- [ ] Configure user-owned release signing securely; never commit signing keys or secrets to this public repository.
- [ ] Build signed release APK and verify install/update on a physical Android phone without USB dependency for future updates.
- [ ] Verify the legacy package `com.arbitri.app` remains untouched.

## 6. Final Live-readiness gate (separate explicit approval)
- [ ] All previous sections pass automated tests and physical-device Demo tests.
- [ ] Review API permissions, risk defaults, recovery and kill-switch behavior.
- [ ] Verify account fee tier, filters, minimums and available balances on the actual account.
- [ ] Perform a final security and failure-mode review.
- [ ] Obtain the user's explicit authorization before enabling any Live order submission. No Live trading before this gate.

## Current known status (as of 2026-11-10)
- Existing work includes market catalogue/cycle discovery, public depth simulation, basic instrument rounding/validation, authenticated read-only OKX account/fee/balance queries, fee-mode simulation foundation, fill reconciliation, Room order journal/migration foundation, and a prototype in-memory risk guard.
- Room-backed risk reservations, daily-loss state, durable kill switch storage, and the dashboard risk-control panel are implemented. Persistence and migration tests passed Android CI on commit `c83e35e`.
- The persistent risk manager is not yet wired into a complete cycle execution path and does not authorize execution.
- A strict OKX Demo-only Spot limit-order API adapter has been added. It has a fixed `https://openapi.okx.com` host, always sends `x-simulated-trading: 1`, supports submit/query-by-client-ID/cancel, and has no Live-mode parameter or fallback. Unit tests have been added but are pending CI verification.
- The Demo adapter is not yet connected to durable pre-submit intents, risk reservations, or a sequential three-leg cycle engine. No order submission or cancellation has been invoked; no Live trading exists.
- Initial 10 USDT is a simulation input only; no funds have been invested or traded.
- Latest verified CI commit before the Demo adapter work: `c83e35e988a2bc4c7e68241c25f7f8205fdc67f4` (Node tests and Android build passed).
- PR #3 remains open/draft; do not merge without explicit user approval.
