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
}: {
  cpu: Cpu;
  assetVersion: string;
  onCancel?: () => void;
  cancelling?: boolean;
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
              <dd>{job.initiator ? job.initiator.playerName ?? t('common.unknownPlayer') : t('cpus.inGame')}</dd>
            </div>
          </dl>
          {job.cancellable && onCancel ? (
            <div className="order-actions">
              <ConfirmButton label={t('crafting.cancel')} onConfirm={onCancel} disabled={cancelling} />
            </div>
          ) : null}
        </div>
      ) : (
        <p className="muted cpu-idle">{offline ? t('cpus.offlineHint') : t('cpus.idleHint')}</p>
      )}
    </section>
  );
}
