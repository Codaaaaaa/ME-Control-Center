import { useTranslation } from 'react-i18next';
import { useNetwork, useNetworks } from '../api/queries';
import { ActiveCraftingCard } from '../components/crafting/ActiveCraftingCard';
import { ClaimNetworks } from '../components/network/ClaimNetworks';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { NetworkStatusCard } from '../components/network/NetworkStatusCard';
import { ServerStatusCards } from '../components/ServerStatusCards';
import { EmptyNotice, ErrorNotice, LoadingNotice } from '../components/StateNotice';
import { useStatusQuery } from '../hooks/useStatusQuery';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

export function OverviewPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('overview.title')}</h1>
          <p>{t('overview.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>

      <div className="grid">
        {networks.isPending ? (
          <div className="card-wide">
            <LoadingNotice />
          </div>
        ) : networks.isError && !networks.data ? (
          <div className="card-wide">
            <ErrorNotice
              title={t('networks.title')}
              error={networks.error}
              onRetry={() => void networks.refetch()}
              retrying={networks.isFetching}
            />
          </div>
        ) : selected ? (
          <SelectedNetwork id={selected.id} />
        ) : (
          <>
            <div className="card-wide">
              <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
            </div>
            <ClaimNetworks />
          </>
        )}

        <h2 className="section-title card-wide">{t('overview.serverSection')}</h2>
        <ServerSection />
      </div>
    </>
  );
}

function SelectedNetwork({ id }: { id: string }) {
  const { t } = useTranslation();
  const detail = useNetwork(id);
  if (detail.isPending) {
    return (
      <div className="card-wide">
        <LoadingNotice />
      </div>
    );
  }
  if (detail.isError) {
    return (
      <div className="card-wide">
        <ErrorNotice title={t('network.status')} error={detail.error} onRetry={() => void detail.refetch()} retrying={detail.isFetching} />
      </div>
    );
  }
  return (
    <>
      <NetworkStatusCard detail={detail.data} />
      {detail.data.status ? <ActiveCraftingCard networkId={id} /> : null}
    </>
  );
}

function ServerSection() {
  const { t } = useTranslation();
  const { data, error, isPending, refetch, isFetching } = useStatusQuery();
  if (isPending) {
    return (
      <div className="card-wide">
        <LoadingNotice label={t('status.loading')} />
      </div>
    );
  }
  if (!data) {
    return (
      <div className="card-wide">
        <ErrorNotice title={t('status.errorTitle')} error={error} onRetry={() => void refetch()} retrying={isFetching} />
      </div>
    );
  }
  return <ServerStatusCards status={data} />;
}
