import { lazy, Suspense, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { overallChange, RANGES, type ChartMode, type Range, type WatchEntry } from '../../api/insights';
import { useSeries, useWatchlist, useWatchMutations } from '../../api/queries';
import { exactAmount } from '../../lib/amount';
import { formatRelative, toBcp47 } from '../../lib/format';
import { Badge, Card } from '../Card';
import { useCapability } from '../crafting/CraftingBits';
import { ResourceIcon } from '../terminal/ResourceIcon';
import { ResourceName } from '../terminal/ResourceName';
import { EmptyNotice, ErrorNotice, LoadingNotice, useErrorMessage } from '../StateNotice';
import type { TrendSeries } from './TrendChart';

// ECharts is only downloaded by pages that draw a chart.
const TrendChart = lazy(() => import('./TrendChart'));

/** Watched resources and their history for one network (spec sections 6.2-6.3 and 21). */
export function WatchlistPanel({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const [range, setRange] = useState<Range>('1d');
  const [mode, setMode] = useState<ChartMode>('quantity');
  const watchlist = useWatchlist(networkId, i18n.language);
  const series = useSeries(networkId, range);

  const chartSeries = useMemo<TrendSeries[]>(() => {
    const entries = new Map(watchlist.data?.entries.map((entry) => [entry.id, entry]));
    return (series.data?.series ?? []).flatMap((line) => {
      const entry = entries.get(line.entryId);
      return entry ? [{ id: line.entryId, resource: entry.resource, points: line.points }] : [];
    });
  }, [watchlist.data, series.data]);
  const changes = useMemo(
    () => new Map(chartSeries.map((line) => [line.id, overallChange(line.points)])),
    [chartSeries],
  );
  const hasData = chartSeries.some((line) => line.points.some((point) => point[1] !== null));
  const zeroStart = mode === 'change' && chartSeries.some((line) => line.points.length > 0 && changes.get(line.id) === null);

  if (watchlist.isPending) {
    return (
      <div className="card-wide">
        <LoadingNotice />
      </div>
    );
  }
  if (watchlist.isError) {
    return (
      <div className="card-wide">
        <ErrorNotice title={t('insights.watchlist')} error={watchlist.error} onRetry={() => void watchlist.refetch()} />
      </div>
    );
  }
  const { entries, assetVersion, capturedAt, limit } = watchlist.data;
  if (entries.length === 0) {
    return (
      <div className="card-wide">
        <EmptyNotice
          title={t('insights.emptyTitle')}
          action={
            <Link className="button" to="/terminal">
              {t('insights.openTerminal')}
            </Link>
          }
        >
          {t('insights.emptyBody')}
        </EmptyNotice>
      </div>
    );
  }

  return (
    <>
      <Card
        className="card-wide"
        title={t('insights.trend')}
        badge={
          <div className="chart-controls">
            <div className="segmented" role="group" aria-label={t('insights.mode')}>
              {(['quantity', 'change'] as const).map((value) => (
                <button
                  key={value}
                  type="button"
                  className={`segment${mode === value ? ' segment-active' : ''}`}
                  aria-pressed={mode === value}
                  onClick={() => setMode(value)}
                >
                  {t(`insights.modes.${value}`)}
                </button>
              ))}
            </div>
            <div className="segmented" role="group" aria-label={t('insights.range')}>
              {RANGES.map((value) => (
                <button
                  key={value}
                  type="button"
                  className={`segment${range === value ? ' segment-active' : ''}`}
                  aria-pressed={range === value}
                  onClick={() => setRange(value)}
                >
                  {t(`insights.ranges.${value}`)}
                </button>
              ))}
            </div>
          </div>
        }
      >
        {series.isError && !series.data ? (
          <ErrorNotice title={t('insights.trend')} error={series.error} onRetry={() => void series.refetch()} />
        ) : series.isPending ? (
          <LoadingNotice />
        ) : !hasData ? (
          <EmptyNotice title={t('insights.noDataTitle')}>
            {series.data?.sampling === false ? t('insights.samplingDisabled') : t('insights.noDataBody')}
          </EmptyNotice>
        ) : (
          <Suspense fallback={<LoadingNotice />}>
            <TrendChart series={chartSeries} mode={mode} locale={i18n.language} />
          </Suspense>
        )}
        {zeroStart ? <p className="footnote">{t('insights.zeroStart')}</p> : null}
      </Card>

      <Card
        className="card-wide"
        title={t('insights.watchlist')}
        badge={
          <span className="footnote">
            {capturedAt
              ? t('insights.updated', { time: formatRelative(capturedAt, i18n.language) })
              : t('insights.offlineAmounts')}
            {' · '}
            {t('insights.count', { used: entries.length, limit })}
          </span>
        }
      >
        <div className="watch-grid">
          {entries.map((entry) => (
            <WatchCard
              key={entry.id}
              networkId={networkId}
              entry={entry}
              assetVersion={assetVersion}
              change={changes.get(entry.id) ?? null}
              range={range}
            />
          ))}
        </div>
      </Card>
    </>
  );
}

function WatchCard({
  networkId,
  entry,
  assetVersion,
  change,
  range,
}: {
  networkId: string;
  entry: WatchEntry;
  assetVersion: string;
  change: number | null;
  range: Range;
}) {
  const { t, i18n } = useTranslation();
  const { unwatch } = useWatchMutations(networkId, i18n.language);
  const message = useErrorMessage();
  const percent = new Intl.NumberFormat(toBcp47(i18n.language), {
    maximumFractionDigits: 1,
    signDisplay: 'exceptZero',
  });
  return (
    <div className="watch-card">
      <ResourceIcon iconKey={entry.resource.iconKey} assetVersion={assetVersion} size={40} />
      <div className="watch-card-body">
        <div className="watch-card-name" title={entry.resource.id}>
          <ResourceName resource={entry.resource} />
        </div>
        <div className="watch-card-amount">
          {entry.amount === null ? '—' : exactAmount({ amount: entry.amount, unit: entry.resource.unit }, i18n.language)}
        </div>
        <div className="list-meta">
          {change !== null ? (
            <Badge tone={change > 0 ? 'success' : change < 0 ? 'danger' : 'neutral'} title={t(`insights.ranges.${range}`)}>
              {percent.format(change)}%
            </Badge>
          ) : null}
          {entry.resource.type !== 'item' ? <Badge tone="neutral">{entry.resource.type}</Badge> : null}
          {entry.craftable ? <Badge tone="accent">{t('terminal.craftable')}</Badge> : null}
          {entry.crafting !== null ? <Badge tone="warning">{t('terminal.crafting')}</Badge> : null}
        </div>
      </div>
      <div className="watch-card-actions">
        <Link className="button button-small" to={`/terminal?resource=${encodeURIComponent(entry.resource.id)}`}>
          {t('insights.open')}
        </Link>
        <button
          type="button"
          className="button button-quiet button-small"
          onClick={() => unwatch.mutate(entry.id)}
          disabled={unwatch.isPending}
          title={unwatch.isError ? message(unwatch.error) : undefined}
        >
          {t('insights.unwatch')}
        </button>
      </div>
    </div>
  );
}

/** Watch toggle and a 6-hour preview for the resource detail panel (spec section 7.5). */
export function ResourceWatch({ networkId, resourceId }: { networkId: string; resourceId: string }) {
  const { t, i18n } = useTranslation();
  const canWatch = useCapability(networkId, 'MANAGE_WATCHLIST');
  const watchlist = useWatchlist(networkId, i18n.language);
  const { watch, unwatch } = useWatchMutations(networkId, i18n.language);
  const message = useErrorMessage();
  const entry = watchlist.data?.entries.find((candidate) => candidate.resource.id === resourceId);
  const failed = watch.isError ? watch.error : unwatch.isError ? unwatch.error : null;

  return (
    <div className="detail-watch">
      {canWatch && watchlist.data ? (
        <button
          type="button"
          className={`button${entry ? '' : ' button-primary'}`}
          disabled={watch.isPending || unwatch.isPending}
          onClick={() => (entry ? unwatch.mutate(entry.id) : watch.mutate(resourceId))}
        >
          {entry ? `★ ${t('insights.unwatch')}` : `☆ ${t('insights.watch')}`}
        </button>
      ) : null}
      {failed ? <p className="footnote footnote-danger">{message(failed)}</p> : null}
      {entry ? <ResourceHistory networkId={networkId} entry={entry} /> : null}
    </div>
  );
}

function ResourceHistory({ networkId, entry }: { networkId: string; entry: WatchEntry }) {
  const { t, i18n } = useTranslation();
  const series = useSeries(networkId, '6h', entry.resource.id);
  const points = series.data?.series[0]?.points ?? [];
  if (!points.some((point) => point[1] !== null)) {
    return <p className="footnote">{series.isPending ? t('common.loading') : t('insights.noDataBody')}</p>;
  }
  const change = overallChange(points);
  return (
    <div className="detail-history">
      <div className="metric-label">
        {t('insights.change6h')}
        {change !== null ? `: ${new Intl.NumberFormat(toBcp47(i18n.language), { maximumFractionDigits: 1, signDisplay: 'exceptZero' }).format(change)}%` : ''}
      </div>
      <Suspense fallback={<LoadingNotice />}>
        <TrendChart
          series={[{ id: entry.id, resource: entry.resource, points }]}
          mode="quantity"
          locale={i18n.language}
          height={140}
          showLegend={false}
        />
      </Suspense>
    </div>
  );
}
