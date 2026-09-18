import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useRef } from 'react';
import { changePercent, type ChartMode, type Point } from '../../api/insights';
import type { ResourceLabel } from '../../api/crafting';
import { exactAmount } from '../../lib/amount';
import { toBcp47 } from '../../lib/format';

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

/** Distinct on the dark background; the AE2 accent comes first. */
const PALETTE = ['#2fd4c8', '#f2b544', '#9d8cff', '#4fd08a', '#f06a6a', '#5aa9ff', '#ff8fc7', '#c9d46a'];

export interface TrendSeries {
  id: string;
  resource: ResourceLabel;
  points: Point[];
}

/**
 * Multi-series history (spec section 6.3). One line per resource, toggled from the legend; gaps stay gaps;
 * the tooltip shows time, amount, and change, and opens on tap as well as hover.
 */
export default function TrendChart({
  series,
  mode,
  locale,
  height = 320,
  showLegend = true,
}: {
  series: TrendSeries[];
  mode: ChartMode;
  locale: string;
  height?: number;
  showLegend?: boolean;
}) {
  const element = useRef<HTMLDivElement>(null);
  const chart = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    if (!element.current) return;
    const instance = echarts.init(element.current, undefined, { renderer: 'canvas' });
    chart.current = instance;
    const observer = new ResizeObserver(() => instance.resize());
    observer.observe(element.current);
    return () => {
      observer.disconnect();
      instance.dispose();
      chart.current = null;
    };
  }, []);

  useEffect(() => {
    const instance = chart.current;
    if (!instance) return;
    const language = toBcp47(locale);
    const percentFormat = new Intl.NumberFormat(language, { maximumFractionDigits: 1, signDisplay: 'exceptZero' });
    const timeFormat = new Intl.DateTimeFormat(language, { dateStyle: 'short', timeStyle: 'short' });
    const prepared = series.map((entry) => {
      const perUnit = entry.resource.unit?.amountPerUnit ?? 1;
      return {
        entry,
        quantity: entry.points.map(([at, avg]): [number, number | null] => [at, avg === null ? null : avg / perUnit]),
        change: changePercent(entry.points),
      };
    });

    instance.setOption(
      {
        color: PALETTE,
        animation: false,
        grid: { left: 8, right: 16, top: showLegend ? 40 : 12, bottom: 8, containLabel: true },
        legend: showLegend
          ? { type: 'scroll', top: 0, textStyle: { color: '#a8b5c6' }, pageTextStyle: { color: '#a8b5c6' } }
          : { show: false },
        tooltip: {
          trigger: 'axis',
          triggerOn: 'mousemove|click',
          confine: true,
          backgroundColor: '#101826',
          borderColor: '#1d2a3d',
          textStyle: { color: '#e6edf5' },
          formatter: (raw: unknown) => {
            const items = (Array.isArray(raw) ? raw : [raw]) as { seriesIndex: number; dataIndex: number; marker: string }[];
            const head = items[0];
            if (!head) return '';
            const at = prepared[head.seriesIndex]?.entry.points[head.dataIndex]?.[0];
            const lines = items.flatMap(({ seriesIndex, dataIndex, marker }) => {
              const line = prepared[seriesIndex];
              if (!line) return [];
              const { entry, change } = line;
              const avg = entry.points[dataIndex]?.[1];
              if (avg === null || avg === undefined) return [];
              const percent = change?.[dataIndex]?.[1];
              const amount = exactAmount({ amount: Math.round(avg), unit: entry.resource.unit }, locale);
              const delta = percent === null || percent === undefined ? '' : ` (${percentFormat.format(percent)}%)`;
              return [`${marker}${escapeHtml(entry.resource.name)}: <b>${amount}</b>${delta}`];
            });
            return [at === undefined ? '' : timeFormat.format(at), ...lines].join('<br/>');
          },
        },
        xAxis: {
          type: 'time',
          axisLine: { lineStyle: { color: '#1d2a3d' } },
          axisLabel: { color: '#6b7a8f', hideOverlap: true },
          splitLine: { show: false },
        },
        yAxis: {
          type: 'value',
          scale: true,
          axisLabel: {
            color: '#6b7a8f',
            formatter: (value: number) =>
              mode === 'change'
                ? `${percentFormat.format(value)}%`
                : new Intl.NumberFormat(language, { notation: 'compact', maximumFractionDigits: 2 }).format(value),
          },
          splitLine: { lineStyle: { color: '#1d2a3d' } },
        },
        series: prepared.map(({ entry, quantity, change }) => ({
          id: entry.id,
          name: entry.resource.name,
          type: 'line',
          showSymbol: false,
          connectNulls: false,
          lineStyle: { width: 2 },
          data: mode === 'change' ? (change ?? []).map(([at, value]) => [at, value]) : quantity,
        })),
      },
      // Replace the lines but keep the legend, so series the user hid stay hidden across refreshes.
      { replaceMerge: ['series'] },
    );
  }, [series, mode, locale, showLegend]);

  return <div ref={element} className="trend-chart" style={{ height }} />;
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (character) => `&#${character.charCodeAt(0)};`);
}
