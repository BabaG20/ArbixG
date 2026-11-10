import test from 'node:test';
import assert from 'node:assert/strict';
import { simulateTriangularRoute } from '../src/core/order-book-simulator.js';
const now = 1_800_000_000_000;
const opportunity = { legs: [{ instId: 'BTC-USDT', side: 'buy-base' }, { instId: 'BTC-ETH', side: 'sell-base' }, { instId: 'ETH-USDT', side: 'sell-base' }] };
const instruments = ['BTC-USDT', 'BTC-ETH', 'ETH-USDT'].map(instId => ({ instId, lotSz: '0.0001', minSz: '0.0001', tickSz: '0.01' }));
const books = [
 { instId: 'BTC-USDT', ts: String(now), asks: [['100', '20']], bids: [['99', '20']] },
 { instId: 'BTC-ETH', ts: String(now), asks: [['16', '20']], bids: [['15', '20']] },
 { instId: 'ETH-USDT', ts: String(now), asks: [['7', '400']], bids: [['6', '400']] }
];
test('simulates three legs using book depth and confirmed fee', () => {
 const result = simulateTriangularRoute({ opportunity, books, instruments, startAmount: 1000, feeRate: 0.001, now });
 assert.equal(result.status, 'simulated'); assert.equal(result.executions.length, 3);
 assert.ok(Number.isFinite(result.finalAmount)); assert.ok(Number.isFinite(result.profitPct));
});
test('rejects stale order books', () => {
 const stale = books.map(book => ({ ...book, ts: String(now - 10_000) }));
 const result = simulateTriangularRoute({ opportunity, books: stale, instruments, startAmount: 1000, feeRate: 0.001, now });
 assert.equal(result.status, 'stale-order-book');
});
test('does not invent fees', () => assert.throws(() => simulateTriangularRoute({ opportunity, books, instruments, startAmount: 1000, feeRate: null, now }), /confirmed feeRate/));
test('rejects insufficient order-book depth', () => {
 const shallow = books.map(book => ({ ...book, asks: [['100', '0.01']], bids: [['99', '0.01']] }));
 const result = simulateTriangularRoute({ opportunity, books: shallow, instruments, startAmount: 1000, feeRate: 0.001, now });
 assert.equal(result.status, 'insufficient-depth');
});
test('requires instrument trading rules', () => {
 const result = simulateTriangularRoute({ opportunity, books, instruments: [], startAmount: 1000, feeRate: 0.001, now });
 assert.equal(result.status, 'missing-market-data');
});