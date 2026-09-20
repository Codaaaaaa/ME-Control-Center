import { describe, expect, it } from 'vitest';
import enUs from '../i18n/locales/en_us.json';
import { DEVICE_KINDS, deviceGroupSchema } from './explorer';

describe('explorer', () => {
  it('names every device kind the server can report', () => {
    const kinds = enUs.explorer.kind as Record<string, string>;
    for (const kind of DEVICE_KINDS) {
      expect(kinds[kind], kind).toBeTypeOf('string');
    }
  });

  it('keeps optional capabilities nullable, so "unknown" never reads as zero', () => {
    const group = deviceGroupSchema.parse({
      kind: 'STORAGE', item: null, count: 4, offline: 1, channels: null, idlePower: null, locations: [],
      truncated: false,
    });
    expect(group.channels).toBeNull();
    expect(group.idlePower).toBeNull();
  });
});
