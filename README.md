# ArbixG

Production-first triangular arbitrage platform built as an installable Progressive Web App (PWA).

## Principles
- No demo accounts, simulated balances, sample trades, or fabricated market prices.
- Market data must be sourced from connected exchanges and clearly show connection/staleness status.
- Exchange credentials must never be stored in browser code or committed to Git.
- Trading remains disabled until live data, fee calculations, risk controls, and order execution are independently validated.

## Development
```bash
npm install
npm run dev
npm run build
```

## Deployment
Connect this repository to Lovable, configure the production environment, and publish the web app. The PWA can then be installed from a supported mobile browser.

## Current status
This is the clean PWA foundation. Exchange integrations and production services are not connected yet; the UI must show that honestly rather than inventing data.
