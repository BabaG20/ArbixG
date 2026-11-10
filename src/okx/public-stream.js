const DEFAULT_URL = 'wss://ws.okx.com/ws/v5/public';

/**
 * Small reconnecting OKX public WebSocket client.
 * WebSocketImpl, timers and randomness are injectable for deterministic tests.
 * Market messages are passed through unchanged; consumers must validate timestamps/sequences.
 */
export class OkxPublicStream {
  constructor({
    url = DEFAULT_URL,
    WebSocketImpl,
    onData = () => {},
    onStatus = () => {},
    heartbeatMs = 25_000,
    reconnectBaseMs = 500,
    reconnectMaxMs = 30_000,
    setTimer = setTimeout,
    clearTimer = clearTimeout,
    random = Math.random
  }) {
    if (typeof WebSocketImpl !== 'function') throw new TypeError('WebSocketImpl constructor is required');
    this.url = url;
    this.WebSocketImpl = WebSocketImpl;
    this.onData = onData;
    this.onStatus = onStatus;
    this.heartbeatMs = heartbeatMs;
    this.reconnectBaseMs = reconnectBaseMs;
    this.reconnectMaxMs = reconnectMaxMs;
    this.setTimer = setTimer;
    this.clearTimer = clearTimer;
    this.random = random;
    this.socket = null;
    this.heartbeatTimer = null;
    this.reconnectTimer = null;
    this.lastMessageAt = 0;
    this.attempt = 0;
    this.stopped = true;
    this.subscriptions = [];
  }

  subscribe(args) {
    if (!args || !args.channel || !args.instId) throw new TypeError('channel and instId are required');
    const key = args.channel + ':' + args.instId;
    if (!this.subscriptions.some(item => item.channel + ':' + item.instId === key)) this.subscriptions.push(args);
    if (this.socket?.readyState === 1) this.socket.send(JSON.stringify({ op: 'subscribe', args: [args] }));
  }

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    this.#connect();
  }

  stop() {
    this.stopped = true;
    this.#clearHeartbeat();
    if (this.reconnectTimer) this.clearTimer(this.reconnectTimer);
    this.reconnectTimer = null;
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < 2) socket.close(1000, 'client shutdown');
    this.onStatus({ state: 'stopped' });
  }

  #connect() {
    if (this.stopped) return;
    this.onStatus({ state: 'connecting', attempt: this.attempt });
    let socket;
    try { socket = new this.WebSocketImpl(this.url); }
    catch (error) { this.#scheduleReconnect(error); return; }
    this.socket = socket;
    socket.onopen = () => {
      if (this.stopped || this.socket !== socket) return;
      this.attempt = 0;
      this.lastMessageAt = Date.now();
      this.onStatus({ state: 'connected' });
      if (this.subscriptions.length) socket.send(JSON.stringify({ op: 'subscribe', args: this.subscriptions }));
      this.#startHeartbeat(socket);
    };
    socket.onmessage = event => {
      if (this.socket !== socket || this.stopped) return;
      this.lastMessageAt = Date.now();
      const raw = typeof event.data === 'string' ? event.data : String(event.data);
      if (raw === 'pong') return;
      let message;
      try { message = JSON.parse(raw); } catch { this.onStatus({ state: 'invalid-message' }); return; }
      if (message.event === 'error') this.onStatus({ state: 'exchange-error', code: message.code, message: message.msg });
      else if (message.data) this.onData(message);
    };
    socket.onerror = error => this.onStatus({ state: 'socket-error', error: String(error?.message || error || 'unknown') });
    socket.onclose = event => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.#clearHeartbeat();
      this.onStatus({ state: 'disconnected', code: event?.code ?? null, reason: event?.reason ?? '' });
      this.#scheduleReconnect();
    };
  }

  #startHeartbeat(socket) {
    this.#clearHeartbeat();
    const beat = () => {
      if (this.stopped || this.socket !== socket || socket.readyState !== 1) return;
      if (Date.now() - this.lastMessageAt >= this.heartbeatMs) socket.send('ping');
      this.heartbeatTimer = this.setTimer(beat, this.heartbeatMs);
    };
    this.heartbeatTimer = this.setTimer(beat, this.heartbeatMs);
  }

  #clearHeartbeat() {
    if (this.heartbeatTimer) this.clearTimer(this.heartbeatTimer);
    this.heartbeatTimer = null;
  }

  #scheduleReconnect(error) {
    if (this.stopped || this.reconnectTimer) return;
    this.#clearHeartbeat();
    if (error) this.onStatus({ state: 'connect-error', error: String(error.message || error) });
    const ceiling = Math.min(this.reconnectMaxMs, this.reconnectBaseMs * (2 ** this.attempt));
    const delay = Math.floor(ceiling * (0.5 + this.random() * 0.5));
    this.attempt += 1;
    this.onStatus({ state: 'reconnecting', delayMs: delay, attempt: this.attempt });
    this.reconnectTimer = this.setTimer(() => {
      this.reconnectTimer = null;
      this.#connect();
    }, delay);
  }
}

export { DEFAULT_URL as OKX_PUBLIC_WS_URL };