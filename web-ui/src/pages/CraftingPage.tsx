import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { ORDER_FILTERS, type Order, type OrderFilter } from '../api/crafting';
import { useCraftingMutations, useNetworks, useOrders } from '../api/queries';
import { CraftDialog } from '../components/crafting/CraftDialog';
import { useCapability } from '../components/crafting/CraftingBits';
import { OrderCard } from '../components/crafting/OrderCard';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { useLiveNetwork } from '../hooks/useLiveNetwork';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

/** Crafting orders (spec section 10): active, completed, failed, and cancelled. */
export function CraftingPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.crafting')}</h1>
          <p>{selected ? selected.displayName : t('crafting.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>
      {networks.isPending ? (
        <LoadingNotice />
      ) : networks.isError && !networks.data ? (
        <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
      ) : selected ? (
        <Orders networkId={selected.id} />
      ) : (
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      )}
    </>
  );
}

function Orders({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [params, setParams] = useSearchParams();
  const filter: OrderFilter = (ORDER_FILTERS as readonly string[]).includes(params.get('tab') ?? '')
    ? (params.get('tab') as OrderFilter)
    : 'ACTIVE';
  const live = useLiveNetwork(networkId, locale);
  const orders = useOrders(networkId, filter, locale, live);
  const mutations = useCraftingMutations(networkId, locale);
  const canCraft = useCapability(networkId, 'SUBMIT_CRAFT');
  const [again, setAgain] = useState<{ order: Order; assetVersion: string } | null>(null);

  const pages = orders.data?.pages ?? [];
  const list = pages.flatMap((page) => page.orders.map((order) => ({ order, assetVersion: page.assetVersion })));

  return (
    <>
      <div className="tabs" role="tablist">
        {ORDER_FILTERS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={filter === name}
            className={`tab${filter === name ? ' tab-active' : ''}`}
            onClick={() => setParams({ tab: name }, { replace: true })}
          >
            {t(`crafting.tabs.${name}`)}
          </button>
        ))}
      </div>

      <FormError error={mutations.cancelOrder.error} />

      {orders.isPending ? (
        <LoadingNotice />
      ) : orders.isError ? (
        <ErrorNotice title={t('crafting.ordersError')} error={orders.error} onRetry={() => void orders.refetch()} />
      ) : list.length === 0 ? (
        <EmptyNotice title={t(`crafting.empty.${filter}`)}>{filter === 'ACTIVE' ? t('crafting.emptyHint') : null}</EmptyNotice>
      ) : (
        <div className="order-list">
          {list.map(({ order, assetVersion }) => (
            <OrderCard
              key={order.id}
              order={order}
              assetVersion={assetVersion}
              cancelling={mutations.cancelOrder.isPending}
              onCancel={() => mutations.cancelOrder.mutate(order.id)}
              onCraftAgain={canCraft ? () => setAgain({ order, assetVersion }) : undefined}
            />
          ))}
        </div>
      )}

      {orders.hasNextPage ? (
        <div className="load-more">
          <button type="button" className="button" onClick={() => void orders.fetchNextPage()} disabled={orders.isFetchingNextPage}>
            {orders.isFetchingNextPage ? t('common.loading') : t('crafting.loadMore')}
          </button>
        </div>
      ) : null}

      {again ? (
        <CraftDialog
          networkId={networkId}
          resource={again.order.target}
          assetVersion={again.assetVersion}
          initialAmount={again.order.amount}
          onClose={() => setAgain(null)}
        />
      ) : null}
    </>
  );
}
