'use strict';

/**
 * Deterministic depth-aware conversion for one three-leg arbitrage path.
 * Market quantities are base-asset quantities, as returned by OKX spot books.
 * feeFor(market) returns a signed rate: OKX fee rates are normally negative.
 */
function walkRoute(route, initialAmount, feeFor, now = Date.now(), maxAgeMs = 5000) {
  if (!Array.isArray(route) || route.length !== 3) return null;
  if (!Number.isFinite(initialAmount) || initialAmount <= 0) return null;
  if (typeof feeFor !== 'function') throw new TypeError('feeFor must be a function');

  let running = initialAmount;
  let totalSlippageFactor = 1;
  const fees = [];
  const legs = [];

  for (const leg of route) {
    const market = leg && leg.market;
    if (!market || !['buy', 'sell'].includes(leg.side)) return null;
    if (!Number.isFinite(market.updated) || now - market.updated < 0 || now - market.updated > maxAgeMs) return null;

    const levels = leg.side === 'sell' ? market.bids : market.asks;
    if (!Array.isArray(levels) || levels.length === 0) return null;

    let remaining = running;
    let outputGross = 0;
    let consumed = 0;
    let bestPrice = null;

    for (const level of levels) {
      const price = Number(level?.[0]);
      const quantity = Number(level?.[1]);
      if (!(price > 0) || !(quantity > 0) || !Number.isFinite(price) || !Number.isFinite(quantity)) continue;
      if (bestPrice === null) bestPrice = price;

      if (leg.side === 'sell') {
        const baseUsed = Math.min(remaining, quantity);
        outputGross += baseUsed * price;
        remaining -= baseUsed;
        consumed += baseUsed;
      } else {
        const baseBought = Math.min(remaining / price, quantity);
        const quoteUsed = baseBought * price;
        outputGross += baseBought;
        remaining -= quoteUsed;
        consumed += quoteUsed;
      }
      if (remaining <= Math.max(1e-12, running * 1e-12)) break;
    }

    if (remaining > Math.max(1e-10, running * 1e-8) || !(outputGross > 0) || !(consumed > 0) || !(bestPrice > 0)) return null;

    const averagePrice = leg.side === 'sell' ? outputGross / consumed : consumed / outputGross;
    if (!(averagePrice > 0) || !Number.isFinite(averagePrice)) return null;

    const legSlippage = Math.abs(averagePrice - bestPrice) / bestPrice;
    totalSlippageFactor *= 1 - Math.min(legSlippage, 0.999999);
    const feeRate = Number(feeFor(market));
    if (!Number.isFinite(feeRate) || feeRate <= -1 || feeRate > 0.1) return null;

    const feeAmount = -outputGross * feeRate;
    const outputNet = outputGross * (1 + feeRate);
    if (!(outputNet > 0) || !Number.isFinite(outputNet)) return null;

    fees.push({ currency: leg.to, amount: feeAmount });
    legs.push({
      side: leg.side,
      from: leg.from,
      to: leg.to,
      marketId: market.instId,
      input: running,
      grossOutput: outputGross,
      feeRate,
      feeAmount,
      netOutput: outputNet,
      averagePrice,
      bestPrice,
      slippage: legSlippage
    });
    running = outputNet;
  }

  return {
    initial: initialAmount,
    final: running,
    profit: running - initialAmount,
    netEdge: (running / initialAmount - 1) * 100,
    slippage: (1 - totalSlippageFactor) * 100,
    fees,
    legs
  };
}

module.exports = { walkRoute };
