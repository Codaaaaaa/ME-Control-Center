import { useTranslation } from 'react-i18next';
import type { StatusReport } from '../api/status';
import { formatMillis, formatUptime, ticksPerSecond } from '../lib/format';
import { Badge, Card, Field, Fields, type Tone } from './Card';

const STATE_TONE: Record<StatusReport['state'], Tone> = {
  RUNNING: 'success',
  BUSY: 'warning',
  UNAVAILABLE: 'danger',
};

export function ServerStatusCards({ status }: { status: StatusReport }) {
  const { t } = useTranslation();
  const { server, platform, mecc, ae2, gateway } = status;

  return (
    <>
      <Card title={t('server.title')} badge={<Badge tone={STATE_TONE[status.state]}>{t(`server.state.${status.state}`)}</Badge>}>
        {server ? (
          <Fields>
            <Field label={t('server.players')}>
              <span className="metric">{server.playersOnline}</span>
              <span className="muted"> / {server.maxPlayers}</span>
            </Field>
            <Field label={t('server.tps')}>
              <span className="metric">{ticksPerSecond(server.averageTickMillis).toFixed(1)}</span>
            </Field>
            <Field label={t('server.mspt')}>{formatMillis(server.averageTickMillis)} ms</Field>
            <Field label={t('server.type')}>{server.dedicated ? t('server.dedicated') : t('server.integrated')}</Field>
          </Fields>
        ) : (
          <p className="muted">{status.state === 'BUSY' ? t('server.busyHint') : t('server.unavailableHint')}</p>
        )}
      </Card>

      <Card
        title={t('ae2.title')}
        badge={<Badge tone={ae2.loaded ? 'success' : 'danger'}>{ae2.loaded ? t('ae2.loaded') : t('ae2.notLoaded')}</Badge>}
      >
        <Fields>
          <Field label={t('ae2.version')}>
            <code>{ae2.version ?? '—'}</code>
          </Field>
          {ae2.loaded ? (
            <Field label={t('ae2.state')}>
              {ae2.tested ? (
                <Badge tone="success">{t('ae2.tested')}</Badge>
              ) : (
                <Badge tone="warning">{t('ae2.untested', { version: ae2.testedVersion })}</Badge>
              )}
            </Field>
          ) : null}
          <Field label={t('platform.loader')}>
            <code>
              {platform.minecraftVersion} · {platform.loader} {platform.loaderVersion}
            </code>
          </Field>
        </Fields>
      </Card>

      <Card title={t('mecc.title')}>
        <Fields>
          <Field label={t('mecc.version')}>
            <code>{mecc.version}</code>
          </Field>
          <Field label={t('mecc.uptime')}>{formatUptime(mecc.uptimeSeconds)}</Field>
          <Field label={t('mecc.roundTrip')}>
            {gateway.roundTripMillis === null ? (
              <span className="muted">{gateway.errorCode ?? '—'}</span>
            ) : (
              `${formatMillis(gateway.roundTripMillis)} ms`
            )}
          </Field>
          <Field label={t('mecc.pending')}>{gateway.pendingTasks}</Field>
        </Fields>
      </Card>
    </>
  );
}
