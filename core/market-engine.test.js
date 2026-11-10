'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { walkRoute } = require('./market-engine');

const now = 1_700_000_000_000;
function market(instId, bids, asks) {
  return { instId, bids, asks, updated: now };
}

test('walks a three-leg route using executable depth and signed fees', () => {
  const route = [
    { from: 'USDT', to: 'BTC', side: 'buy', market: market('BTC-USDT', [[49900, 2]], [[50000, 2]]) },
    { from: 'BTC', to: 'ETH', side: 'sell', market: market('ETH-BTC', [[0.06, 2]], [[0.061, 2]]) },
    { from: 'ETH', to: 'USDT', side: 'sell', market: market('ETH-USDT', [[3100, 2]], [[3110, 2]]) }
  ];
  const result = walkRoute(route, 1000, () => -0.001, now, 5000);
  assert.ok(result);
  assert.equal(result.legs.length, 3);
  assert.ok(result.final > 0);
  assert.equal(result.fees.length, 3);
});

test('rejects stale books', () => {
  const route = [
    { side: 'buy', to: 'BTC', market: { ...market('BTC-USDT', [], [[50000, 1]]), updated: now - 5001 } },
    { side: 'sell', to: 'ETH', market: market('ETH-BTC', [[0.06, 1]], []) },
    { side: 'sell', to: 'USDT', market: market('ETH-USDT', [[3000, 1]], []) }
  ];
  assert.equal(walkRoute(route, 100, () => -0.001, now, 5000), null);
});

test('rejects insufficient order-book depth', () => {
  const route = [
    { side: 'buy', to: 'BTC', market: market('BTC-USDT', [], [[50000, 0.001]]) },
    { side: 'sell', to: 'ETH', market: market('ETH-BTC', [[0.06, 1]], []) },
    { side: 'sell', to: 'USDT', market: market('ETH-USDT', [[3000, 1]], []) }
  ];
  assert.equal(walkRoute(route, 1000, () => -0.001, now, 5000), null);
});

test('rejects invalid fee rates', () => {
  const route = [
    { side: 'buy', to: 'BTC', market: market('BTC-USDT', [], [[50000, 1]]) },
    { side: 'sell', to: 'ETH', market: market('ETH-BTC', [[0.06, 1]], []) },
    { side: 'sell', to: 'USDT', market: market('ETH-USDT', [[3000, 1]], []) }
  ];
  assert.equal(walkRoute(route, 100, () => -1, now, 5000), null);
});
