const DEFAULT_BASE_URL = "https://www.okx.com";

export class OkxPublicClient {
  constructor({ baseUrl = DEFAULT_BASE_URL, fetchImpl = fetch, timeoutMs = 10_000 } = {}) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
    this.fetchImpl = fetchImpl;
    this.timeoutMs = timeoutMs;
  }

  async get(path, params = {}) {
    const url = new URL(this.baseUrl + path);
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await this.fetchImpl(url, {
        method: "GET",
        headers: { Accept: "application/json" },
        signal: controller.signal
      });
      if (!response.ok) throw new Error(`OKX HTTP ${response.status} for ${path}`);
      const payload = await response.json();
      if (payload.code !== "0") throw new Error(`OKX API error ${payload.code}: ${payload.msg || "unknown error"}`);
      return payload.data;
    } finally {
      clearTimeout(timer);
    }
  }

  getSpotInstruments() {
    return this.get("/api/v5/public/instruments", { instType: "SPOT" });
  }

  getSpotTickers() {
    return this.get("/api/v5/market/tickers", { instType: "SPOT" });
  }

  getOrderBook(instId, depth = 50) {
    if (!instId) throw new TypeError("instId is required");
    return this.get("/api/v5/market/books", { instId, sz: depth });
  }
}
