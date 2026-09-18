import { describe, expect, it } from 'vitest';
import { changePercent, overallChange, seriesSetSchema, type Point } from './insights';

describe('changePercent', () => {
  it('measures change against the first valid value and keeps gaps', () => {
    const points: Point[] = [
      [0, null, null, null],
      [1, 200, 100, 300],
      [2, null, null, null],
      [3, 150, 150, 150],
    ];
    expect(changePercent(points)).toEqual([
      [0, null, null, null],
      [1, 0, -50, 50],
      [2, null, null, null],
      [3, -25, -25, -25],
    ]);
    expect(overallChange(points)).toBe(-25);
  });

  it('refuses to divide by a zero start', () => {
    expect(changePercent([[1, 0, 0, 0], [2, 10, 10, 10]])).toBeNull();
    expect(overallChange([[1, 0, 0, 0], [2, 10, 10, 10]])).toBeNull();
  });

  it('has nothing to show without data', () => {
    expect(changePercent([])).toEqual([]);
    expect(overallChange([])).toBeNull();
  });
});

describe('seriesSetSchema', () => {
  it('accepts gap markers from the server', () => {
    const parsed = seriesSetSchema.parse({
      range: '1h',
      from: '2026-09-18T10:00:00Z',
      to: '2026-09-18T11:00:00Z',
      stepSeconds: 15,
      resolution: 'RAW',
      sampling: true,
      series: [{ entryId: 'e', resourceId: 'item:minecraft:iron_ingot', points: [[1, 2.5, 2, 3], [2, null, null, null]] }],
    });
    expect(parsed.series[0]?.points[1]?.[1]).toBeNull();
  });
});
