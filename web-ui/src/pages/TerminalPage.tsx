import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { failureKind } from '../api/client';
import { useNetworks } from '../api/queries';
import type { ResourceQuery } from '../api/resources';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { EmptyNotice, ErrorNotice, LoadingNotice, useErrorMessage } from '../components/StateNotice';
import { ResourceDetailPanel } from '../components/terminal/ResourceDetailPanel';
import { ResourceGrid } from '../components/terminal/ResourceGrid';
import { TerminalToolbar } from '../components/terminal/TerminalToolbar';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useResourceWindow } from '../hooks/useResourceWindow';
import { formatRelative } from '../lib/format';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';
import { DENSITIES, useTerminalPrefs } from '../stores/terminalPrefs';

/** The resource terminal (spec section 7): the primary interaction surface. */
export function TerminalPage() {
  const { t, i18n } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  const prefs = useTerminalPrefs();
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebouncedValue(search);
  const [selectedResource, setSelectedResource] = useState<string | null>(null);
  const [range, setRange] = useState({ start: 0, end: 0 });
  const onRangeChange = useCallback((start: number, end: number) => setRange({ start, end }), []);

  const query: ResourceQuery | null = selected
    ? {
        networkId: selected.id,
        search: debouncedSearch,
        sort: prefs.sort,
        descending: prefs.descending,
        type: prefs.type,
        locale: i18n.language,
      }
    : null;
  const resources = useResourceWindow(query, range.start, range.end);
  const message = useErrorMessage();

  if (networks.isPending) {
    return <LoadingNotice />;
  }
  if (networks.isError) {
    return <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />;
  }
  if (!selected) {
    return (
      <>
        <div className="page-header">
          <h1>{t('nav.terminal')}</h1>
        </div>
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      </>
    );
  }

  const tileSize = DENSITIES[prefs.density];
  const unavailable = resources.isError;

  return (
    <div className="terminal">
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.terminal')}</h1>
          <p>
            {selected.displayName}
            {resources.capturedAt ? ` · ${t('terminal.updated', { time: formatRelative(resources.capturedAt, i18n.language) })}` : ''}
          </p>
        </div>
        <NetworkPicker networks={networks.data} selectedId={selected.id} />
      </div>

      <TerminalToolbar
        search={search}
        onSearch={setSearch}
        sort={prefs.sort}
        descending={prefs.descending}
        onSort={prefs.setSort}
        type={prefs.type}
        onType={prefs.setType}
        density={prefs.density}
        onDensity={prefs.setDensity}
        total={resources.total}
      />

      <div className={`terminal-body${selectedResource ? ' terminal-body-detail' : ''}`}>
        {unavailable ? (
          <div className="terminal-state">
            <ErrorNotice
              title={['unavailable', 'notFound', 'networkOffline'].includes(failureKind(resources.error))
                ? t('terminal.unavailableTitle')
                : t('terminal.errorTitle')}
              error={resources.error}
              onRetry={resources.refetch}
            />
          </div>
        ) : resources.isPending ? (
          <div className="terminal-state">
            <LoadingNotice label={t('terminal.loading')} />
          </div>
        ) : resources.total === 0 ? (
          <div className="terminal-state">
            <EmptyNotice title={search ? t('terminal.noMatches') : t('terminal.empty')}>
              {search ? t('terminal.noMatchesHint') : t('terminal.emptyHint')}
            </EmptyNotice>
          </div>
        ) : (
          <ResourceGrid
            window={resources}
            tileSize={tileSize}
            selectedId={selectedResource}
            onSelect={setSelectedResource}
            onRangeChange={onRangeChange}
          />
        )}

        {selectedResource ? (
          <ResourceDetailPanel
            networkId={selected.id}
            resourceId={selectedResource}
            snapshotId={resources.snapshotId}
            onClose={() => setSelectedResource(null)}
          />
        ) : null}
      </div>
      {unavailable ? <span className="visually-hidden">{message(resources.error)}</span> : null}
    </div>
  );
}
