import { describe, expect, it } from 'vitest';
import {
  formatBytes,
  formatDateTime,
  formatElapsed,
  formatEnergy,
  formatMillis,
  formatRelative,
  formatTime,
  formatUptime,
  percentOf,
  ticksPerSecond,
} from './format';

describe('formatUptime', () => {
  it.each([
    [0, '0s'],
    [59, '59s'],
    [61, '1m 1s'],
    [3_660, '1h 1m'],
    [93_784, '1d 2h 3m'],
    [-5, '0s'],
  ])('%i seconds -> %s', (input, expected) => {
    expect(formatUptime(input)).toBe(expected);
  });
});

describe('ticksPerSecond', () => {
  it('caps at 20', () => {
    expect(ticksPerSecond(10)).toBe(20);
    expect(ticksPerSecond(0)).toBe(20);
  });

  it('drops when ticks exceed 50 ms', () => {
    expect(ticksPerSecond(100)).toBe(10);
  });
});

describe('formatMillis', () => {
  it('uses more precision for small values', () => {
    expect(formatMillis(3.14159)).toBe('3.14');
    expect(formatMillis(42.25)).toBe('42.3');
  });
});

describe('formatEnergy', () => {
  it.each([
    [0, '0 AE'],
    [950, '950 AE'],
    [12_400, '12.4 kAE'],
    [1_600_000, '1.6 MAE'],
    [250_000_000, '250 MAE'],
  ])('%d -> %s', (input, expected) => {
    expect(formatEnergy(input)).toBe(expected);
  });
});

describe('percentOf', () => {
  it('refuses to invent a percentage without a denominator', () => {
    expect(percentOf(5, 0)).toBeNull();
    expect(percentOf(null, 10)).toBeNull();
    expect(percentOf(5, 10)).toBe(50);
  });
});

describe('formatRelative', () => {
  it('formats past times', () => {
    const now = Date.parse('2026-09-17T12:00:00Z');
    expect(formatRelative('2026-09-17T11:57:00Z', 'en_us', now)).toBe('3 minutes ago');
    expect(formatRelative('2026-09-17T12:00:00Z', 'en_us', now)).toBe('now');
  });
});

describe('formatBytes', () => {
  it('counts crafting storage in binary units like AE2', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(65_536)).toBe('64 KB');
    expect(formatBytes(1_536)).toBe('1.5 KB');
    expect(formatBytes(64 * 1024 * 1024)).toBe('64 MB');
  });
});

describe('formatElapsed', () => {
  it('shows a clock, with hours only when needed', () => {
    expect(formatElapsed(872_000)).toBe('14:32');
    expect(formatElapsed(3_723_000)).toBe('1:02:03');
    expect(formatElapsed(-5)).toBe('0:00');
  });
});

describe('formatDateTime', () => {
  it('accepts the UI locale codes, which are not BCP 47 tags', () => {
    // toLocaleString('zh_cn') throws a RangeError; this blanked the audit log pages.
    expect(() => formatDateTime('2026-09-18T12:00:00Z', 'zh_cn')).not.toThrow();
    expect(() => formatDateTime('2026-09-18T12:00:00Z', 'en_us')).not.toThrow();
    expect(() => formatTime(0, 'zh_cn')).not.toThrow();
  });
});
