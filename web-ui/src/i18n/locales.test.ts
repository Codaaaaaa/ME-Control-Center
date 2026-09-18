import { describe, expect, it } from 'vitest';
import enUs from './locales/en_us.json';
import zhCn from './locales/zh_cn.json';

function keys(value: unknown, prefix = ''): string[] {
  if (value === null || typeof value !== 'object') {
    return [prefix];
  }
  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
    keys(child, prefix ? `${prefix}.${key}` : key),
  );
}

describe('locales', () => {
  it('en_us and zh_cn define exactly the same keys', () => {
    expect(keys(zhCn).sort()).toEqual(keys(enUs).sort());
  });
});
