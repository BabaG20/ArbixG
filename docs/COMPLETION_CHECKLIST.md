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
- [ ] Complete Room v1-to-v4 migration tests and verify existing trade history and order journal are preserved.
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
- Existing work includes the full active Spot instrument catalogue/ticker scan, triangular-cycle discovery, public L2 depth simulation, basic instrument rounding/validation, authenticated read-only OKX account/fee/balance queries, fee-mode simulation foundation, fill reconciliation, Room tracked-order journal, and persistent risk controls.
- Room-backed risk reservations, daily-loss state, durable kill-switch storage, and the dashboard risk-control panel are implemented. Persistence and migration tests passed Android CI on prior commits.
- The strict OKX Demo-only Spot limit-order adapter has a fixed `https://openapi.okx.com` host and always sends `x-simulated-trading: 1`; it supports submit/query-by-client-ID/cancel and has no Live-mode parameter or fallback. Demo adapter and JSON parser tests now run under Robolectric.
- Room v4 includes durable `order_intents` with unique client IDs and cycle/leg identity. The submission coordinator persists intent before POST, blocks duplicate submissions, and reconciles ambiguous submissions by client ID without blind retry.
- Added strict parsing of full OKX order-query fill fields. Zero-fill open orders may legitimately have empty average-price/fee fields; the parser now handles that case while requiring price and fee evidence once fills exist.
- Added `CycleFillMath` to calculate fee-adjusted input consumed and output received from confirmed fills. Fees in input/output currency are handled exactly; fees in an unrelated third currency are rejected rather than guessed.
- Added `CycleOrderSizer` to size the next leg from actual preceding-leg output using BigDecimal, floor to lot size, and validate tick size, min size and min notional. Missing min notional blocks sizing; it is not assumed.
- These new fill-math and sizing components are pure/testable foundations only. They are not yet wired into a sequential three-leg Demo cycle coordinator, persistent cycle journal, risk reservation lifecycle, or UI. Therefore **three-leg execution remains incomplete and disabled**.
- CI for commit `6193d6c` passed both Android build and Node tests. Later pure fill-math and order-sizing tests passed Node CI on commits `f370eec`, `a4797eb`, and `acbdad3`; Android build checks were still running for the newest changes when this section was updated. Latest branch HEAD: `495b13d5fddf567449c4df50ba2dee4435422b02`; verify its Android and Node workflows before treating these additions as CI-verified.
- No order submission or cancellation has been invoked against OKX. Initial 10 USDT is a simulation/risk input only; no funds have been invested or traded.
- PR #3 remains open/draft; do not merge without explicit user approval.


## Work log — 2026-11-11 (implementation added; CI/device verification pending)

- Added OpportunityAssetFilter. It suppresses a candidate cycle when any leg's base currency is held in the Spot trading account or a supported Earn product, or when that exact instrument has an open Spot order. Quote-only balances such as USDT are not treated as held base assets, so they do not suppress every USDT market. Unknown instrument base-currency mapping fails closed.
- Added read-only Earn balance collection for Simple Earn flexible savings, active On-chain Earn investments, ETH staking (BETH), SOL staking (OKSOL), Stable Rewards, OKUSD and active Dual Investment order states (initial, live, pending_settle, pending_redeem). Dual Investment history is paginated with the documented order ID cursor and one-second request spacing. Any Earn endpoint failure prevents the app from claiming that Earn filtering is complete.
- Added Demo and Live read-only account selection. Live selection is only for reading actual balances, fee rates, Earn holdings and pending Spot orders; it does not enable Live order placement. Credentials remain read-only and encrypted locally. Switching environments clears the previous account/filter snapshot.
- The account filter snapshot is considered fresh for five minutes. Until a complete snapshot has been successfully retrieved, the UI keeps candidates visible but explicitly labels holdings/Earn filtering as unverified.
- Added an additional adverse slippage stress buffer of 10 bps per leg to the L2 depth simulation. The simulator already consumes the visible order-book levels; this buffer worsens bids/asks further. Gross return is shown after this buffer; net return is shown only when a complete per-instrument taker-fee set and valid OKX fee currency mode are present.
- Added tests for Spot/Earn/open-order exclusions, staking receipt-token mapping, Earn response parsing, active Dual Investment state parsing, and the slippage stress buffer. CI runs for the latest commits are queued; do not mark this work complete until both Android build and tests pass.
- Remaining limitations: OKX Spot metadata loaded here still does not provide a verified minimum-notional rule; available-balance reservation is not yet connected to this scanner; the supported Earn endpoint set must be checked against the user's account/product eligibility. A calculated net estimate is not a confirmed fill or executable profit.


## Work log — 2026-11-10 (Demo cycle, dashboard and APK pipeline work; verification pending)

- Added a Demo-only sequential three-leg cycle engine using the strict `OkxDemoOrderApi`. It writes/uses durable per-leg client IDs, reconciles ambiguous submissions by client ID, waits for exchange-confirmed fills before advancing, and calculates each next leg from fee-adjusted confirmed proceeds.
- Each leg requires a freshly refreshed executable limit price and an explicit order-book depth validation callback. It rechecks the available balance for the asset received from the preceding leg, and sizes with OKX lot/min-size/tick/min-notional rules. Missing minimum notional blocks execution.
- Partial/unfilled orders are canceled and queried again. A canceled/partial/rejected/non-terminal leg stops the cycle; no later leg is sent. No speculative unwind is attempted. Risk reservations remain held when an order may have reached the exchange.
- Added restart continuation logic for existing PREPARED/SUBMITTING/UNKNOWN/ACKNOWLEDGED/RECONCILED leg intents. Client order IDs are deterministic across app restarts. A completed cycle requires final account available balance to reconcile with confirmed fills before P&L is recorded.
- Added a Room-backed dashboard panel for recent cycle history, active tracked orders and recoverable order-intent count. Cycle outcomes are recorded as DEMO; no Live execution path was added.
- Added tests for exactly-three-leg closed routes and rejected route shapes. Corrected a holdings-filter test that had incorrectly expected a cycle containing a BTC-base leg to pass while BTC was held. Earn JSON parser tests now run under Robolectric instead of Android's unmocked local JSON stubs.
- **Not complete yet:** the new cycle engine is not connected to the dashboard's execution controls or a production callback provider for fresh executable price/depth and read-only available-balance retrieval. End-to-end OKX Demo cycles and failure/restart tests have not been performed. Live trading remains disabled.
- The Android workflow runs unit tests before assembling and uploading `ArbixG-debug-apk`; the latest verified run before these fixes failed in six asset/Earn parser tests. Fixes have been pushed, but a new successful Android CI run is required before claiming a build or APK artifact.
- A debug APK is not a signed release APK. A physical-phone install/update test still requires the user's Android device and must be evidenced separately. No signing key is stored in the repository.
