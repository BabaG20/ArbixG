function positive(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function normalizeTicker(ticker) {
  const bid = positive(ticker.bidPx);
  const ask = positive(ticker.askPx);
  if (!ticker.instId || !bid || !ask || bid > ask) return null;
  const [base, quote] = ticker.instId.split("-");
  if (!base || !quote) return null;
  return { instId: ticker.instId, base, quote, bid, ask };
}

function buildEdges(tickers) {
  const edges = new Map();
  for (const raw of tickers) {
    const market = normalizeTicker(raw);
    if (!market) continue;
    const pair = [
      { from: market.base, to: market.quote, rate: market.bid, instId: market.instId, side: "sell-base" },
      { from: market.quote, to: market.base, rate: 1 / market.ask, instId: market.instId, side: "buy-base" }
    ];
    for (const edge of pair) {
      if (!edges.has(edge.from)) edges.set(edge.from, []);
      edges.get(edge.from).push(edge);
    }
  }
  return edges;
}

function cycleKey(edges) {
  return edges.map(edge => edge.from + ">" + edge.to).join("|");
}

/** Net return is intentionally unknown until an actual account fee rate is supplied. */
export function scanTriangularOpportunities(tickers, {
  startCurrencies = ["USDT"],
  feeRate = null,
  minNetProfitPct = 0,
  maxSpreadPct = 1
} = {}) {
  if (!Array.isArray(tickers)) throw new TypeError("tickers must be an array");
  const fee = feeRate === null ? null : Number(feeRate);
  if (fee !== null && (!Number.isFinite(fee) || fee < 0 || fee >= 1)) {
    throw new RangeError("feeRate must be a decimal between 0 and 1");
  }

  const edges = buildEdges(tickers);
  const found = new Map();
  for (const start of startCurrencies) {
    for (const first of edges.get(start) || []) {
      for (const second of edges.get(first.to) || []) {
        if (second.to === start || second.to === first.from) continue;
        for (const third of edges.get(second.to) || []) {
          if (third.to !== start) continue;
          const legs = [first, second, third];
          if (new Set(legs.map(edge => edge.instId)).size !== 3) continue;
          const key = cycleKey(legs);
          if (found.has(key)) continue;
          const grossMultiplier = legs.reduce((value, edge) => value * edge.rate, 1);
          const netMultiplier = fee === null ? null : grossMultiplier * (1 - fee) ** 3;
          const netProfitPct = netMultiplier === null ? null : (netMultiplier - 1) * 100;
          const spreads = legs.map(edge => {
            const ticker = tickers.find(item => item.instId === edge.instId);
            const bid = positive(ticker?.bidPx), ask = positive(ticker?.askPx);
            return bid && ask ? ((ask - bid) / ask) * 100 : 0;
          });
          const spreadPct = spreads.reduce((sum, value) => sum + value, 0);
          if (spreadPct > maxSpreadPct) continue;
          if (netProfitPct !== null && netProfitPct < minNetProfitPct) continue;
          found.set(key, {
            startCurrency: start,
            path: [start, first.to, second.to, start],
            legs,
            grossMultiplier,
            grossProfitPct: (grossMultiplier - 1) * 100,
            netMultiplier,
            netProfitPct,
            feeRate: fee,
            spreadPct,
            status: fee === null ? "fee-required" : "needs-orderbook-validation"
          });
        }
      }
    }
  }
  return [...found.values()].sort((a, b) =>
    (b.netProfitPct ?? b.grossProfitPct) - (a.netProfitPct ?? a.grossProfitPct)
  );
}
