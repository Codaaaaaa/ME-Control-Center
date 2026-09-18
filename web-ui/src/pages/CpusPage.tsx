import { useTranslation } from 'react-i18next';
import { useCpus, useCraftingMutations, useNetworks } from '../api/queries';
import { CpuCard } from '../components/crafting/CpuCard';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { useLiveNetwork } from '../hooks/useLiveNetwork';
import { formatRelative } from '../lib/format';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

/** Crafting CPUs (spec section 12). */
export function CpusPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.cpus')}</h1>
          <p>{selected ? selected.displayName : t('cpus.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>
      {networks.isPending ? (
        <LoadingNotice />
      ) : networks.isError ? (
        <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
      ) : selected ? (
        <Cpus networkId={selected.id} />
      ) : (
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      )}
    </>
  );
}

function Cpus({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const live = useLiveNetwork(networkId, locale);
  const cpus = useCpus(networkId, locale, live);
  const mutations = useCraftingMutations(networkId, locale);

  if (cpus.isPending) {
    return <LoadingNotice />;
  }
  if (cpus.isError) {
    // Never "No CPUs" when the request failed (spec section 47).
    return <ErrorNotice title={t('cpus.error')} error={cpus.error} onRetry={() => void cpus.refetch()} />;
  }
  if (cpus.data.cpus.length === 0) {
    return <EmptyNotice title={t('cpus.none')}>{t('cpus.noneHint')}</EmptyNotice>;
  }
  const busy = cpus.data.cpus.filter((cpu) => cpu.busy).length;
  return (
    <>
      <p className="muted section-note">
        {t('cpus.summary', { total: cpus.data.cpus.length, busy })} · {t('terminal.updated', { time: formatRelative(cpus.data.capturedAt, locale) })}
        {live ? ` · ${t('cpus.live')}` : ''}
      </p>
      <FormError error={mutations.cancelCpuJob.error} />
      <div className="cpu-grid">
        {cpus.data.cpus.map((cpu) => (
          <CpuCard
            key={cpu.id}
            cpu={cpu}
            assetVersion={cpus.data.assetVersion}
            cancelling={mutations.cancelCpuJob.isPending}
            onCancel={() => mutations.cancelCpuJob.mutate({ cpuId: cpu.id, jobId: cpu.job?.jobId ?? null })}
          />
        ))}
      </div>
    </>
  );
}
