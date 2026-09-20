import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DEVICE_KINDS, fetchNetworkMap, type DeviceGroup, type DeviceKind } from '../api/explorer';
import { useNetworks } from '../api/queries';
import { Badge, Card } from '../components/Card';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { EmptyNotice, ErrorNotice, LoadingNotice } from '../components/StateNotice';
import { ResourceIcon } from '../components/terminal/ResourceIcon';
import { formatRelative } from '../lib/format';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

const REFRESH_MS = 10_000;

/** Network Explorer (spec section 26): what the network is built from, and what is offline. */
export function ExplorerPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.explorer')}</h1>
          <p>{selected ? selected.displayName : t('explorer.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>
      {networks.isPending ? (
        <LoadingNotice />
      ) : networks.isError && !networks.data ? (
        <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
      ) : selected ? (
        <Devices networkId={selected.id} />
      ) : (
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      )}
    </>
  );
}

function Devices({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [filter, setFilter] = useState<DeviceKind | 'ALL'>('ALL');
  const map = useQuery({
    queryKey: ['explorer', networkId, locale],
    queryFn: ({ signal }) => fetchNetworkMap(networkId, locale, signal),
    refetchInterval: REFRESH_MS,
  });

  if (map.isPending) return <LoadingNotice />;
  if (map.isError) return <ErrorNotice title={t('nav.explorer')} error={map.error} onRetry={() => void map.refetch()} />;
  const { devices, status, nodes, offlineNodes, capturedAt, assetVersion } = map.data;
  const kinds = DEVICE_KINDS.filter((kind) => devices.some((group) => group.kind === kind));
  const shown = filter === 'ALL' ? devices : devices.filter((group) => group.kind === filter);

  return (
    <>
      <p className="muted section-note">
        {t('explorer.summary', { nodes, offline: offlineNodes })}
        {status.usedChannels !== null ? ` · ${t('explorer.channelsUsed', { count: status.usedChannels })}` : ''}
        {status.channelMode ? ` · ${status.channelMode}` : ''} · {t('terminal.updated', { time: formatRelative(capturedAt, locale) })}
      </p>
      <div className="tabs" role="tablist">
        {(['ALL', ...kinds] as const).map((kind) => (
          <button
            key={kind}
            type="button"
            role="tab"
            aria-selected={filter === kind}
            className={`tab${filter === kind ? ' tab-active' : ''}`}
            onClick={() => setFilter(kind)}
          >
            {kind === 'ALL' ? t('explorer.kind.ALL') : t(`explorer.kind.${kind}`)}{' '}
            {kind === 'ALL' ? devices.length : devices.filter((group) => group.kind === kind).length}
          </button>
        ))}
      </div>
      <div className="grid">
        {shown.map((group) => (
          <DeviceCard key={`${group.kind}/${group.item?.id ?? 'unknown'}`} group={group} assetVersion={assetVersion} />
        ))}
      </div>
    </>
  );
}

function DeviceCard({ group, assetVersion }: { group: DeviceGroup; assetVersion: string }) {
  const { t } = useTranslation();
  const [showAll, setShowAll] = useState(false);
  const locations = showAll ? group.locations : group.locations.slice(0, 5);
  return (
    <Card
      title={
        <span className="list-title">
          {group.item ? <ResourceIcon iconKey={group.item.iconKey} assetVersion={assetVersion} size={24} /> : null}
          {group.item?.name ?? t('explorer.unknownDevice')} × {group.count}
        </span>
      }
      badge={group.offline > 0 ? <Badge tone="warning">{t('explorer.offline', { count: group.offline })}</Badge> : undefined}
    >
      <p className="muted-text">
        {t(`explorer.kind.${group.kind}`)}
        {group.channels !== null ? ` · ${t('explorer.channels', { count: group.channels })}` : ''}
        {group.idlePower !== null ? ` · ${t('explorer.idlePower', { power: group.idlePower.toFixed(2) })}` : ''}
      </p>
      {locations.length > 0 ? (
        <ul className="list list-compact">
          {locations.map((location) => (
            <li key={`${location.dimension}/${location.x},${location.y},${location.z}`} className="list-row">
              <span className="list-meta">
                {location.dimension} {location.x}, {location.y}, {location.z}
              </span>
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted-text">{t('explorer.noLocations')}</p>
      )}
      {group.locations.length > 5 && !showAll ? (
        <button type="button" className="button button-quiet button-small" onClick={() => setShowAll(true)}>
          {t('explorer.showAll', { count: group.locations.length })}
        </button>
      ) : null}
      {group.truncated ? <p className="muted-text">{t('explorer.truncated')}</p> : null}
    </Card>
  );
}
