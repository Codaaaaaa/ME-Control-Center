import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import type { NetworkDetail, NetworkState } from '../../api/networks';
import { formatCount, formatEnergy, formatRelative, percentOf } from '../../lib/format';
import { Badge, Card, Field, Fields, type Tone } from '../Card';

export const STATE_TONE: Record<NetworkState, Tone> = {
  ONLINE: 'success',
  DEGRADED: 'warning',
  OFFLINE: 'danger',
  CONFLICT: 'warning',
};

/** "What is happening in my ME network right now?" (spec section 6.1). */
export function NetworkStatusCard({ detail }: { detail: NetworkDetail }) {
  const { t, i18n } = useTranslation();
  const { network, status, anchors } = detail;
  const locale = i18n.language;

  return (
    <Card
      className="card-wide network-card"
      title={
        <>
          {t('network.status')}: <span className="network-name">{network.displayName}</span>
        </>
      }
      badge={<Badge tone={STATE_TONE[network.state]}>{t(`network.state.${network.state}`)}</Badge>}
    >
      {network.stateReason ? <p className={`state-reason state-${network.state.toLowerCase()}`}>{t(`network.reason.${network.stateReason}`)}</p> : null}

      {status ? (
        <div className="metric-grid">
          <Metric label={t('network.energy')}>
            {status.storedEnergy !== null && status.energyCapacity !== null ? (
              <>
                <span className="metric">{formatEnergy(status.storedEnergy)}</span>
                <span className="muted"> / {formatEnergy(status.energyCapacity)}</span>
                <Meter percent={percentOf(status.storedEnergy, status.energyCapacity)} />
              </>
            ) : (
              <Unsupported />
            )}
          </Metric>
          <Metric label={t('network.energyUsage')}>
            {status.averageEnergyUsage !== null && status.averageEnergyInjection !== null ? (
              <span className="metric-small">
                {t('network.perTick', { value: formatEnergy(status.averageEnergyUsage) })}
                <span className="muted"> / {t('network.perTick', { value: formatEnergy(status.averageEnergyInjection) })}</span>
              </span>
            ) : (
              <Unsupported />
            )}
          </Metric>
          <Metric label={t('network.channels')}>
            {status.usedChannels !== null ? <span className="metric">{formatCount(status.usedChannels, locale)}</span> : <Unsupported />}
          </Metric>
          <Metric label={t('network.cpus')}>
            {status.craftingCpus !== null && status.busyCraftingCpus !== null ? (
              <span className="metric-small">{t('network.cpusValue', { total: status.craftingCpus, busy: status.busyCraftingCpus })}</span>
            ) : (
              <Unsupported />
            )}
          </Metric>
          <Metric label={t('network.activeJobs')}>
            {status.busyCraftingCpus !== null ? <span className="metric">{status.busyCraftingCpus}</span> : <Unsupported />}
          </Metric>
          <Metric label={t('network.resourceTypes')}>
            {status.storedResourceTypes !== null ? (
              <span className="metric">{formatCount(status.storedResourceTypes, locale)}</span>
            ) : (
              <Unsupported />
            )}
          </Metric>
          <Metric label={t('network.providers')}>
            {status.patternProviders !== null ? <span className="metric">{status.patternProviders}</span> : <Unsupported />}
          </Metric>
          <Metric label={t('network.nodes')}>
            <span className="metric">{formatCount(status.nodeCount, locale)}</span>
          </Metric>
        </div>
      ) : null}

      <Fields>
        <Field label={t('network.anchors')}>
          {t('network.anchorsValue', { active: anchors.filter((anchor) => anchor.active).length, total: anchors.length })}
        </Field>
        <Field label={t('network.role')}>
          {t(`roles.${network.role}`)}
          {network.adminOverride ? (
            <>
              {' '}
              <Badge tone="accent">{t('network.adminOverride')}</Badge>
            </>
          ) : null}
        </Field>
        <Field label={t('network.owner')}>{network.owner.playerName ?? t('common.unknownPlayer')}</Field>
        <Field label={t('network.lastSeen')}>
          {network.lastSeenAt ? formatRelative(network.lastSeenAt, locale) : t('common.never')}
        </Field>
      </Fields>

      <footer className="card-footer">
        <Link to={`/networks/${network.id}`} className="button button-quiet button-small">
          {t('network.manage')}
        </Link>
      </footer>
    </Card>
  );
}

function Metric({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="metric-cell">
      <div className="metric-label">{label}</div>
      <div className="metric-value">{children}</div>
    </div>
  );
}

function Meter({ percent }: { percent: number | null }) {
  if (percent === null) return null;
  return (
    <div className="meter" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(percent)}>
      <div className="meter-fill" style={{ width: `${percent}%` }} />
    </div>
  );
}

function Unsupported() {
  const { t } = useTranslation();
  return (
    <span className="muted" title={t('common.unsupportedHint')}>
      {t('common.unsupported')}
    </span>
  );
}
