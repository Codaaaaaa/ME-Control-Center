import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Order } from '../../api/crafting';
import { useOrder } from '../../api/queries';
import { formatDateTime, formatElapsed, formatRelative } from '../../lib/format';
import { Badge } from '../Card';
import { ConfirmButton } from '../ConfirmButton';
import { ORDER_TONE, ProgressBar, ResourceLabelView } from './CraftingBits';

/** One crafting order (spec section 10): what, how much, who, where, how far, and what can be done with it. */
export function OrderCard({
  order,
  assetVersion,
  onCancel,
  cancelling,
  onCraftAgain,
  onSavePreset,
}: {
  order: Order;
  assetVersion: string;
  onCancel?: () => void;
  cancelling?: boolean;
  onCraftAgain?: () => void;
  onSavePreset?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const [open, setOpen] = useState(false);
  const running = order.state === 'RUNNING' || order.state === 'SUBMITTING';

  return (
    <article className="order-card">
      <div className="order-main">
        <ResourceLabelView resource={order.target} assetVersion={assetVersion} size={36} amount={order.amount} />
        <Badge tone={ORDER_TONE[order.state]}>{t(`crafting.state.${order.state}`)}</Badge>
      </div>

      {running || order.state === 'COMPLETED' ? <ProgressBar progress={order.progress} active={running} /> : null}
      {order.state === 'RUNNING' && !order.observed ? <p className="form-hint">{t('crafting.unobserved')}</p> : null}
      {order.failure ? (
        <p className={order.state === 'FAILED' ? 'form-error' : 'form-hint'}>
          {i18n.exists(`errors.codes.${order.failure.code}`) ? t(`errors.codes.${order.failure.code}`) : order.failure.message}
        </p>
      ) : null}

      <dl className="order-facts">
        <div>
          <dt>{t('crafting.creator')}</dt>
          <dd>{order.creator.playerName ?? t('common.unknownPlayer')}</dd>
        </div>
        <div>
          <dt>{t('crafting.cpu')}</dt>
          <dd>{order.cpu ? order.cpu.name ?? t('cpus.unnamed') : '—'}</dd>
        </div>
        <div>
          <dt>{t('crafting.created')}</dt>
          <dd title={formatDateTime(order.createdAt, locale)}>{formatRelative(order.createdAt, locale)}</dd>
        </div>
        <div>
          <dt>{running ? t('crafting.elapsed') : t('crafting.duration')}</dt>
          <dd>{order.elapsedMillis !== null ? formatElapsed(order.elapsedMillis) : '—'}</dd>
        </div>
      </dl>

      <div className="order-actions">
        <button type="button" className="button button-quiet button-small" onClick={() => setOpen(!open)} aria-expanded={open}>
          {open ? t('crafting.hideHistory') : t('crafting.history')}
        </button>
        {onCraftAgain ? (
          <button type="button" className="button button-small" onClick={onCraftAgain}>
            {t('crafting.craftAgain')}
          </button>
        ) : null}
        {onSavePreset ? (
          <button type="button" className="button button-quiet button-small" onClick={onSavePreset}>
            {t('crafting.saved.saveAsPreset')}
          </button>
        ) : null}
        {order.cancellable && onCancel ? (
          <ConfirmButton label={t('crafting.cancel')} onConfirm={onCancel} disabled={cancelling} />
        ) : null}
      </div>

      {open ? <OrderHistory networkId={order.networkId} orderId={order.id} /> : null}
    </article>
  );
}

function OrderHistory({ networkId, orderId }: { networkId: string; orderId: string }) {
  const { t, i18n } = useTranslation();
  const detail = useOrder(networkId, orderId, i18n.language);
  if (!detail.data) {
    return <p className="footnote">{detail.isError ? t('crafting.historyError') : t('common.loading')}</p>;
  }
  return (
    <ol className="order-history">
      {detail.data.events.map((event, index) => (
        <li key={index}>
          <time dateTime={event.at}>{formatDateTime(event.at, i18n.language)}</time>
          <span>{t(`crafting.event.${event.type}`, { defaultValue: event.type })}</span>
          {event.actor ? <span className="muted">{event.actor.playerName ?? t('common.unknownPlayer')}</span> : null}
        </li>
      ))}
    </ol>
  );
}
