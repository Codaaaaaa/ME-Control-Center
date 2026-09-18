import type { Resource } from '../api/resources';
import { toBcp47 } from './format';

const SUFFIXES = ['', 'K', 'M', 'B', 'T'];

/**
 * Compact amount for a terminal tile, in the AE2 style: exact below 1000, then 3 significant digits with
 * a K/M/B/T suffix (1234 -> "1.23K", 45678 -> "45.6K", 1500000 -> "1.50M").
 */
export function compactAmount(amount: number): string {
  if (!Number.isFinite(amount)) return '0';
  const sign = amount < 0 ? '-' : '';
  let value = Math.abs(amount);
  if (value < 1000) return `${sign}${Math.floor(value)}`;
  let unit = 0;
  while (value >= 1000 && unit < SUFFIXES.length - 1) {
    value /= 1000;
    unit++;
  }
  const digits = value < 10 ? 2 : value < 100 ? 1 : 0;
  // Truncate rather than round, so a tile never claims more than the network holds.
  const truncated = Math.floor(value * 10 ** digits) / 10 ** digits;
  return `${sign}${truncated.toFixed(digits)}${SUFFIXES[unit]}`;
}

/** Tile label: counts are compacted; unit-based resources (fluids) are shown in their unit. */
export function tileAmount(resource: Pick<Resource, 'amount' | 'unit'>): string {
  if (!resource.unit) {
    return compactAmount(resource.amount);
  }
  const units = resource.amount / resource.unit.amountPerUnit;
  if (units < 1) {
    return `${Math.floor(resource.amount)}m${resource.unit.symbol}`;
  }
  return `${compactAmount(units)}${resource.unit.symbol}`;
}

/** Full amount for the detail panel, e.g. "16,000 mB (16 B)". */
export function exactAmount(resource: Pick<Resource, 'amount' | 'unit'>, locale: string): string {
  const formatted = new Intl.NumberFormat(toBcp47(locale)).format(resource.amount);
  if (!resource.unit) {
    return formatted;
  }
  const units = resource.amount / resource.unit.amountPerUnit;
  const unitsFormatted = new Intl.NumberFormat(toBcp47(locale), { maximumFractionDigits: 3 }).format(units);
  return `${formatted} m${resource.unit.symbol} (${unitsFormatted} ${resource.unit.symbol})`;
}
