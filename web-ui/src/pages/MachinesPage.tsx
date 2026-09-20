import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchMachines, MACHINE_STATUSES, type Machine, type MachineStatus } from '../api/machines';
import { useAlertRuleMutations, useAlertRules, useNetworks } from '../api/queries';
import { Badge, type Tone } from '../components/Card';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { ResourceIcon } from '../components/terminal/ResourceIcon';
import { formatRelative } from '../lib/format';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

const MACHINES_REFRESH_MS = 5_000;

const TONE: Record<MachineStatus, Tone> = {
  STUCK: 'danger', WAITING: 'warning', WORKING: 'success', IDLE: 'neutral', DISABLED: 'neutral',
};

/**
 * The machines behind the network's pattern providers and buffers, and whether they keep up: a machine a crafting job
 * waits for that holds its inputs but does not change is flagged as stuck.
 */
export function MachinesPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.machines')}</h1>
          <p>{selected ? selected.displayName : t('machines.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>
      {networks.isPending ? (
        <LoadingNotice />
      ) : networks.isError && !networks.data ? (
        <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
      ) : selected ? (
        <Machines networkId={selected.id} />
      ) : (
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      )}
    </>
  );
}

function Machines({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [filter, setFilter] = useState<MachineStatus | 'ALL'>('ALL');
  const machines = useQuery({
    queryKey: ['machines', networkId, locale],
    queryFn: ({ signal }) => fetchMachines(networkId, locale, signal),
    refetchInterval: MACHINES_REFRESH_MS,
  });

  if (machines.isPending) return <LoadingNotice />;
  if (machines.isError) {
    return <ErrorNotice title={t('nav.machines')} error={machines.error} onRetry={() => void machines.refetch()} />;
  }
  const { machines: list, assetVersion, stuckAfterSeconds, capturedAt } = machines.data;
  const count = (status: MachineStatus) => list.filter((machine) => machine.status === status).length;
  const shown = filter === 'ALL' ? list : list.filter((machine) => machine.status === filter);

  return (
    <>
      <p className="muted section-note">
        {t('machines.summary', { total: list.length, minutes: Math.round(stuckAfterSeconds / 60) })} ·{' '}
        {t('terminal.updated', { time: formatRelative(capturedAt, locale) })}
      </p>
      <StuckAlertToggle networkId={networkId} minutes={Math.round(stuckAfterSeconds / 60)} />
      <div className="tabs" role="tablist">
        {(['ALL', ...MACHINE_STATUSES] as const).map((name) => (
          <button key={name} type="button" role="tab" aria-selected={filter === name}
            className={`tab${filter === name ? ' tab-active' : ''}`} onClick={() => setFilter(name)}>
            {t(`machines.filter.${name}`)} {name === 'ALL' ? list.length : count(name)}
          </button>
        ))}
      </div>
      {list.length === 0 ? (
        <EmptyNotice title={t('machines.none')}>{t('machines.noneHint')}</EmptyNotice>
      ) : (
        <div className="provider-grid">
          {shown.map((machine) => <MachineCard key={machine.id} machine={machine} assetVersion={assetVersion} />)}
        </div>
      )}
    </>
  );
}

function MachineCard({ machine, assetVersion }: { machine: Machine; assetVersion: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  return (
    <article className={`card provider-card machine-card machine-${machine.status.toLowerCase()}`}>
      <header className="card-header">
        <h2 className="card-title provider-title">
          {machine.block ? <ResourceIcon iconKey={machine.block.iconKey} assetVersion={assetVersion} size={28} /> : null}
          <span>{machine.block?.name ?? t('machines.unknownBlock')}</span>
        </h2>
        <Badge tone={TONE[machine.status]}>{t(`machines.status.${machine.status}`)}</Badge>
      </header>
      {machine.reason ? (
        <p className={machine.status === 'STUCK' ? 'form-error' : 'form-hint'}>
          {t(`machines.reason.${machine.reason}`)}
          {/* The machine's own wording beats any guess we could print. */}
          {machine.waitingReason ? ` ${machine.waitingReason}` : ''}
        </p>
      ) : null}
      {machine.progress !== null ? (
        <div className="progress-track" role="progressbar" aria-valuenow={Math.round(machine.progress * 100)}
          aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-fill" style={{ width: `${machine.progress * 100}%` }} />
        </div>
      ) : null}
      <dl className="fields">
        {machine.location ? (
          <div className="field">
            <dt>{t('patterns.providers.location')}</dt>
            <dd><code>{machine.location.dimension} {machine.location.x}, {machine.location.y}, {machine.location.z}</code></dd>
          </div>
        ) : null}
        <div className="field">
          <dt>{t('machines.lastChange')}</dt>
          <dd>{formatRelative(machine.lastChange, locale)}</dd>
        </div>
        {machine.reported ? (
          <div className="field">
            <dt>{t('machines.reported')}</dt>
            <dd>{t(`machines.reportedStatus.${machine.reported}`, { defaultValue: machine.reported })}</dd>
          </div>
        ) : null}
        <div className="field">
          <dt>{t('machines.providers')}</dt>
          <dd>{machine.providers}</dd>
        </div>
      </dl>
      <div className="list-meta">
        {machine.awaited ? <Badge tone="accent">{t('machines.awaited')}</Badge> : null}
        {machine.pendingSends > 0 ? <Badge tone="warning">{t('machines.pending', { count: machine.pendingSends })}</Badge> : null}
      </div>
    </article>
  );
}

/** One click to be notified when a machine gets stuck (a personal alert rule). */
function StuckAlertToggle({ networkId, minutes }: { networkId: string; minutes: number }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const rules = useAlertRules(networkId, locale);
  const mutations = useAlertRuleMutations(networkId, locale);
  const existing = rules.data?.rules.find((rule) => rule.type === 'MACHINE_STUCK' && rule.resource === null);
  if (!rules.data) return null;
  return (
    <div className="order-actions">
      {existing ? (
        <span className="muted">{t('machines.alertOn', { minutes: existing.threshold ?? minutes })}</span>
      ) : (
        <button type="button" className="button button-small" disabled={mutations.create.isPending}
          onClick={() => mutations.create.mutate({ type: 'MACHINE_STUCK', resourceId: null, threshold: minutes,
            windowMinutes: null, cooldownMinutes: 0 })}>
          {t('machines.alertAdd', { minutes })}
        </button>
      )}
      <FormError error={mutations.create.error} />
    </div>
  );
}
