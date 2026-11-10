import test from "node:test";
import assert from "node:assert/strict";
import { scanTriangularOpportunities } from "../src/core/triangular-scanner.js";

const tickers = [
  { instId: "BTC-USDT", bidPx: "50000", askPx: "50010" },
  { instId: "ETH-BTC", bidPx: "0.05", askPx: "0.0501" },
  { instId: "ETH-USDT", bidPx: "2600", askPx: "2601" }
];

test("finds a three-market cycle and marks fees unknown when not supplied", () => {
  const routes = scanTriangularOpportunities(tickers, { startCurrencies: ["USDT"] });
  const route = routes.find(item => item.path.join(">") === "USDT>BTC>ETH>USDT");
  assert.ok(route);
  assert.equal(route.netProfitPct, null);
  assert.equal(route.status, "fee-required");
});

test("calculates net return only when an explicit fee rate is supplied", () => {
  const routes = scanTriangularOpportunities(tickers, { startCurrencies: ["USDT"], feeRate: 0.001 });
  const route = routes.find(item => item.path.join(">") === "USDT>BTC>ETH>USDT");
  assert.ok(route);
  assert.equal(route.feeRate, 0.001);
  assert.ok(Number.isFinite(route.netProfitPct));
});

test("rejects malformed and crossed ticker quotes", () => {
  const routes = scanTriangularOpportunities([
    ...tickers,
    { instId: "BAD-USDT", bidPx: "12", askPx: "10" },
    { instId: "NOPE-USDT", bidPx: "0", askPx: "1" }
  ], { startCurrencies: ["USDT"] });
  assert.ok(routes.every(route => route.legs.every(leg => !["BAD-USDT", "NOPE-USDT"].includes(leg.instId))));
});

test("does not invent routes from fewer than three distinct markets", () => {
  assert.deepEqual(scanTriangularOpportunities(tickers.slice(0, 2), { startCurrencies: ["USDT"] }), []);
});

test("rejects an invalid fee rate", () => {
  assert.throws(() => scanTriangularOpportunities(tickers, { feeRate: 1 }), /feeRate/);
});
