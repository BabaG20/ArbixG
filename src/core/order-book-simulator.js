function finitePositive(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : null;
}

function decimalPlaces(step) {
  const text = String(step);
  if (text.includes('e-')) return Number(text.split('e-')[1]);
  return (text.split('.')[1] || '').length;
}

function floorToStep(value, step) {
  return Math.floor(value / step + 1e-9) * step;
}

/** Simulate a three-leg route against actual OKX order-book snapshots.
 * feeRate must be the account's confirmed taker fee as a decimal.
 * This is a pre-trade estimate, not a promise of execution or profit.
 */
export function simulateTriangularRoute({ opportunity, books, instruments, startAmount, feeRate, now = Date.now(), maxBookAgeMs = 2_000 }) {
  const amount = finitePositive(startAmount);
  if (!amount) throw new RangeError('startAmount must be positive');
  if (feeRate === null || feeRate === undefined || feeRate === '') throw new RangeError('A confirmed feeRate between 0 and 1 is required');
  const fee = Number(feeRate);
  if (!Number.isFinite(fee) || fee < 0 || fee >= 1 throw new RangeError('A confirmed feeRate between 0 and 1 is required');
  if (!opportunity || !Array.isArray(opportunity.legs) || opportunity.legs.length !== 3) throw new TypeError('opportunity must contain exactly three legs');
  const bookById = new Map((books || []).map(book => [book.instId, book]));
  const instrumentById = new Map((instruments || []).map(item => [item.instId, item]));
  let currentAmount = amount;
  const executions = [];
  for (const leg of opportunity.legs) {
    const book = bookById.get(leg.instId);
    const instrument = instrumentById.get(leg.instId);
    if (!book || !instrument) return { status: 'missing-market-data', executions };
    const timestamp = finitePositive(book.ts);
    if (!timestamp || now - timestamp > maxBookAgeMs || timestamp > now + 1_000) return { status: 'stale-order-book', executions };
    const lotSz = finitePositive(instrument.lotSz);
    const minSz = finitePositive(instrument.minSz);
    if (!lotSz || !minSz) return { status: 'invalid-instrument-rules', executions };
    const levels = leg.side === 'sell-base' ? book.bids : leg.side === 'buy-base' ? book.asks : null;
    if (!Array.isArray(levels) || levels.length === 0) return { status: 'missing-order-book-side', executions };
    let remaining = currentAmount;
    let outputBeforeFee = 0;
    let inputConsumed = 0;
    let baseFilled = 0;
    let quoteValue = 0;
    for (const level of levels) {
      const price = finitePositive(level[0]);
      const available = finitePositive(level[1]);
      if (!price || !available) continue;
      if (leg.side === 'sell-base') {
        const take = floorToStep(Math.min(remaining, available), lotSz);
        if (take <= 0) continue;
        const quote = take * price;
        remaining -= take; inputConsumed += take; baseFilled += take; quoteValue += quote; outputBeforeFee += quote;
      } else {
        const affordableBase = remaining / price;
        const take = Math.min(affordableBase, floorToStep(available, lotSz));
        const roundedBase = floorToStep(take, lotSz);
        if (roundedBase <= 0) continue;
        const quote = roundedBase * price;
        remaining -= quote; inputConsumed += quote; baseFilled += roundedBase; quoteValue += quote; outputBeforeFee += roundedBase;
      }
      if (remaining <= Math.max(1e-12, currentAmount * 1e-12)) break;
    }
    if (remaining > Math.max(1e-12, currentAmount * 1e-12)) return { status: 'insufficient-depth', executions: [...executions, { instId: leg.instId, side: leg.side, input: inputConsumed, outputBeforeFee, unfilledAmount: remaining }] };
    if (baseFilled < minSz) return { status: 'below-minimum-size', executions };
    const output = outputBeforeFee * (1 - fee);
    executions.push({ instId: leg.instId, side: leg.side, input: inputConsumed, baseFilled, quoteValue, outputBeforeFee, feeRate: fee, output });
    currentAmount = output;
  }
  const profit = currentAmount - amount;
  return { status: 'simulated', startAmount: amount, finalAmount: currentAmount, profit, profitPct: (profit / amount) * 100, feeRate: fee, executions };
}