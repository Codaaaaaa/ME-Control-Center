import { useTranslation } from 'react-i18next';
import { useNetworks } from '../api/queries';
import { WatchlistPanel } from '../components/insights/WatchlistPanel';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { EmptyNotice, ErrorNotice, LoadingNotice } from '../components/StateNotice';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

/** Resource observation (spec section 21): the player's watchlist and its history. */
export function InsightsPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('insights.title')}</h1>
          <p>{t('insights.subtitle')}</p>
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
            <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
          </div>
        ) : selected ? (
          <WatchlistPanel key={selected.id} networkId={selected.id} />
        ) : (
          <div className="card-wide">
            <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
          </div>
        )}
      </div>
    </>
  );
}
