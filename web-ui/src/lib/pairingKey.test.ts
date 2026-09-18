import { describe, expect, it } from 'vitest';
import { formatPairingKeyInput, isCompletePairingKey } from './pairingKey';

describe('formatPairingKeyInput', () => {
  it.each([
    ['ab7k3m2q9rxf', 'AB7K-3M2Q-9RXF'],
    ['AB7K-3M2Q-9RXF', 'AB7K-3M2Q-9RXF'],
    [' ab7k 3m2q ', 'AB7K-3M2Q'],
    ['ab7', 'AB7'],
    ['ab7k3m2q9rxfEXTRA', 'AB7K-3M2Q-9RXF'],
    ['o0l1i', ''],
  ])('%s -> %s', (input, expected) => {
    expect(formatPairingKeyInput(input)).toBe(expected);
  });

  it('detects complete keys', () => {
    expect(isCompletePairingKey('AB7K-3M2Q-9RXF')).toBe(true);
    expect(isCompletePairingKey('AB7K-3M2Q')).toBe(false);
  });
});
