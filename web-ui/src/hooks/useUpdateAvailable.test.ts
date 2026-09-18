import { describe, expect, it } from 'vitest';
import { entryScript } from './useUpdateAvailable';

describe('entryScript', () => {
  it('finds the bundle a page loads', () => {
    expect(entryScript('<script type="module" crossorigin src="/assets/index-BpNXg1br.js"></script>'))
      .toBe('/assets/index-BpNXg1br.js');
    expect(entryScript('<html></html>')).toBeNull();
  });
});
