# ArbixG

A clean rebuild of the Android-first triangular arbitrage platform, starting with OKX Spot only.

## Current implementation status

- OKX public REST client: spot instruments, tickers, and order books.
- Directed three-market route scanner using top-of-book bid/ask conversion.
- Net profitability is deliberately unavailable until the user's actual OKX fee rate is supplied.
- Unit tests use synthetic fixtures only; fixtures are not live market data.
- No private API keys, account balances, order placement, or automated trading are implemented yet.

## Safety and correctness gates

1. Public market data first; no credentials required for this phase.
2. Validate instrument metadata and order-book depth before considering a route executable.
3. Include actual account fee tier, trading rules, precision, minimum sizes, and slippage.
4. Display stale/disconnected market data as unavailable, never as zero or fabricated values.
5. Demo trading and explicit user confirmation before any live order support.
6. Keep API secrets off the Android client; use Android Keystore-backed encryption.
7. Never request withdrawal permission. Prefer IP-restricted Read/Trade keys and start Read-only.

## Planned architecture

- android/: native Android app (Jetpack Compose) and secure local settings.
- src/okx/: OKX REST/WebSocket adapters.
- src/core/: route and execution calculations.
- test/: deterministic tests; fixtures are never treated as exchange data.

## Run current tests

Requires Node.js 20 or newer: npm test

## Important

The current scanner is a research foundation, not an executable arbitrage bot. Top-of-book estimates do not guarantee fills or profit. Full order-book simulation, fee retrieval, WebSocket sequence handling, Android UI, demo execution, and recovery logic remain required before live trading.
