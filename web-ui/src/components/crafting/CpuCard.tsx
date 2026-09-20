import { useTranslation } from 'react-i18next';
import type { Cpu } from '../../api/crafting';
import { formatBytes, formatElapsed } from '../../lib/format';
import { Badge } from '../Card';
import { ConfirmButton } from '../ConfirmButton';
import { ProgressBar, ResourceLabelView } from './CraftingBits';

/** One crafting CPU (spec section 12). Values the platform cannot report are left out, not shown as zero. */
export function CpuCard({
  cpu,
  assetVersion,
  onCancel,
  cancelling,
  onShowTree,
}: {
  cpu: Cpu;
  assetVersion: string;
  onCancel?: () => void;
  cancelling?: boolean;
  onShowTree?: () => void;
}) {
  const { t } = useTranslation();
  const offline = cpu.online === false;
  const job = cpu.job;

  return (
    <section className={`cpu-card${cpu.busy ? ' cpu-card-busy' : ''}`}>
      <header className="cpu-header">
        <h2 className="cpu-name">{cpu.name ?? t('cpus.unnamed')}</h2>
        <Badge tone={offline ? 'danger' : cpu.busy ? 'accent' : 'neutral'}>
          {offline ? t('cpus.offline') : cpu.busy ? t('cpus.busy') : t('cpus.idle')}
        </Badge>
      </header>
      {cpu.location ? (
        <p className="cpu-location muted">
          {cpu.location.dimension} · {cpu.location.x}, {cpu.location.y}, {cpu.location.z}
        </p>
      ) : null}

      <dl className="order-facts">
        <div>
          <dt>{t('cpus.storage')}</dt>
          <dd>{formatBytes(cpu.storageBytes)}</dd>
        </div>
        <div>
          <dt>{t('cpus.coProcessors')}</dt>
          <dd>{cpu.coProcessors}</dd>
        </div>
        <div>
          <dt>{t('cpus.selectionMode')}</dt>
          <dd>{t(`cpus.mode.${cpu.selectionMode}`, { defaultValue: cpu.selectionMode })}</dd>
        </div>
      </dl>

      {job ? (
        <div className="cpu-job">
          <ResourceLabelView resource={job.output} assetVersion={assetVersion} size={32} amount={job.amount} />
          <ProgressBar progress={job.progress} active />
          <dl className="order-facts">
            <div>
              <dt>{t('crafting.elapsed')}</dt>
              <dd>{job.elapsedMillis !== null ? formatElapsed(job.elapsedMillis) : '—'}</dd>
            </div>
            <div>
              <dt>{t('cpus.initiator')}</dt>
              <dd className="cpu-initiator">
                <span>{job.initiator ? job.initiator.playerName ?? t('common.unknownPlayer') : t('cpus.unknownInitiator')}</span>
                <Badge tone={job.origin === 'WEB' ? 'accent' : 'neutral'}>{t(`cpus.origin.${job.origin}`)}</Badge>
                {job.origin === 'IN_GAME' && !job.paired ? <Badge tone="neutral">{t('cpus.unpaired')}</Badge> : null}
              </dd>
            </div>
          </dl>
          <div className="order-actions">
            {onShowTree ? (
              <button type="button" className="button button-small" onClick={onShowTree}>{t('tree.open')}</button>
            ) : null}
            {job.cancellable && onCancel ? (
              <ConfirmButton label={t('crafting.cancel')} onConfirm={onCancel} disabled={cancelling} />
            ) : null}
          </div>
        </div>
      ) : (
        <p className="muted cpu-idle">{offline ? t('cpus.offlineHint') : t('cpus.idleHint')}</p>
      )}
    </section>
  );
}
