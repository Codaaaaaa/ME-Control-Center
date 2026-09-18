import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useCpus } from '../../api/queries';
import { useLiveNetwork } from '../../hooks/useLiveNetwork';
import { formatElapsed } from '../../lib/format';
import { Badge, Card } from '../Card';
import { ErrorNotice, LoadingNotice } from '../StateNotice';
import { ProgressBar, ResourceLabelView } from './CraftingBits';

/** Overview card: what the network is crafting right now (spec section 6.4). */
export function ActiveCraftingCard({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const live = useLiveNetwork(networkId, locale);
  const cpus = useCpus(networkId, locale, live);
  const running = cpus.data?.cpus.filter((cpu) => cpu.job !== null) ?? [];

  return (
    <Card
      className="card-wide"
      title={t('crafting.activeTitle')}
      badge={cpus.data ? <Badge tone={running.length > 0 ? 'accent' : 'neutral'}>{running.length}</Badge> : undefined}
    >
      {cpus.isPending ? (
        <LoadingNotice />
      ) : cpus.isError ? (
        <ErrorNotice title={t('cpus.error')} error={cpus.error} onRetry={() => void cpus.refetch()} />
      ) : running.length === 0 ? (
        <p className="muted">{t('crafting.activeNone')}</p>
      ) : (
        <div className="active-jobs">
          {running.map((cpu) =>
            cpu.job ? (
              <div key={cpu.id} className="active-job">
                <ResourceLabelView resource={cpu.job.output} assetVersion={cpus.data.assetVersion} size={32} amount={cpu.job.amount} />
                <ProgressBar progress={cpu.job.progress} active />
                <span className="muted active-job-meta">
                  {cpu.name ?? t('cpus.unnamed')}
                  {cpu.job.elapsedMillis !== null ? ` · ${formatElapsed(cpu.job.elapsedMillis)}` : ''}
                </span>
              </div>
            ) : null,
          )}
        </div>
      )}
      <div className="card-footer">
        <Link to="/crafting">{t('crafting.viewOrders')}</Link>
        <Link to="/cpus">{t('cpus.viewAll')}</Link>
      </div>
    </Card>
  );
}
