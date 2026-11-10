import test from 'node:test';
import assert from 'node:assert/strict';
import { OkxPublicStream } from '../src/okx/public-stream.js';
class FakeSocket {
  static instances = [];
  constructor(url) { this.url = url; this.readyState = 0; this.sent = []; FakeSocket.instances.push(this); }
  send(value) { this.sent.push(value); }
  open() { this.readyState = 1; this.onopen?.(); }
  message(data) { this.onmessage?.({ data }); }
  close(code = 1006, reason = 'closed') { this.readyState = 3; this.onclose?.({ code, reason }); }
}
function makeHarness() {
  const timers = []; const statuses = []; const data = [];
  const stream = new OkxPublicStream({ WebSocketImpl: FakeSocket, onStatus: value => statuses.push(value), onData: value => data.push(value), setTimer: (fn, ms) => { const t = { fn, ms, cancelled: false }; timers.push(t); return t; }, clearTimer: t => { if (t) t.cancelled = true; }, random: () => 0 });
  return { stream, timers, statuses, data };
}
test('connects, subscribes and forwards market data', () => {
  FakeSocket.instances = []; const h = makeHarness();
  h.stream.subscribe({ channel: 'books5', instId: 'BTC-USDT' }); h.stream.start();
  const socket = FakeSocket.instances.at(-1); socket.open();
  assert.equal(JSON.parse(socket.sent[0]).op, 'subscribe');
  socket.message(JSON.stringify({ arg: { channel: 'books5' }, data: [{ asks: [], bids: [] }] }));
  assert.equal(h.data.length, 1); assert.equal(h.statuses.at(-1).state, 'connected');
  h.stream.stop();
});
test('sends heartbeat ping after inactivity', () => {
  FakeSocket.instances = []; const h = makeHarness(); h.stream.start();
  const socket = FakeSocket.instances.at(-1); socket.open();
  h.stream.lastMessageAt = Date.now() - 30_000;
  const heartbeat = h.timers.find(timer => timer.ms === 25_000);
  assert.ok(heartbeat); heartbeat.fn(); assert.ok(socket.sent.includes('ping')); h.stream.stop();
});
test('reconnects after an unexpected close', () => {
  FakeSocket.instances = []; const h = makeHarness(); h.stream.start();
  const first = FakeSocket.instances.at(-1); first.open(); first.close();
  assert.ok(h.timers.some(timer => timer.ms > 0 && !timer.cancelled));
  h.stream.stop();
});
test('does not reconnect after explicit stop', () => {
  FakeSocket.instances = []; const h = makeHarness(); h.stream.start();
  const socket = FakeSocket.instances.at(-1); h.stream.stop(); socket.close();
  assert.equal(h.timers.some(timer => timer.ms > 0 && !timer.cancelled), false);
});