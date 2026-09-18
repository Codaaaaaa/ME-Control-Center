import { describe, expect, it } from 'vitest';
import { compactAmount, exactAmount, tileAmount } from './amount';

describe('compactAmount', () => {
  it.each([
    [0, '0'],
    [999, '999'],
    [1000, '1.00K'],
    [1234, '1.23K'],
    [45_678, '45.6K'],
    [123_456, '123K'],
    [1_500_000, '1.50M'],
    [19_652, '19.6K'],
    [2_400_000_000, '2.40B'],
  ])('%d -> %s', (input, expected) => {
    expect(compactAmount(input)).toBe(expected);
  });

  it('never rounds up beyond what is stored', () => {
    expect(compactAmount(1999)).toBe('1.99K');
    expect(compactAmount(999_999)).toBe('999K');
  });
});

describe('tileAmount', () => {
  it('shows fluids in buckets and small amounts in milli-units', () => {
    const bucket = { amountPerUnit: 1000, symbol: 'B' };
    expect(tileAmount({ amount: 16_000, unit: bucket })).toBe('16B');
    expect(tileAmount({ amount: 1_250_000, unit: bucket })).toBe('1.25KB');
    expect(tileAmount({ amount: 250, unit: bucket })).toBe('250mB');
    expect(tileAmount({ amount: 64, unit: null })).toBe('64');
  });
});

describe('exactAmount', () => {
  it('spells out counts and unit conversions', () => {
    expect(exactAmount({ amount: 19_652, unit: null }, 'en_us')).toBe('19,652');
    expect(exactAmount({ amount: 16_500, unit: { amountPerUnit: 1000, symbol: 'B' } }, 'en_us')).toBe(
      '16,500 mB (16.5 B)',
    );
  });
});
