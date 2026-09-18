import { useTranslation } from 'react-i18next';
import type { OrderState, Progress, ResourceLabel } from '../../api/crafting';
import { useNetwork } from '../../api/queries';
import { exactAmount } from '../../lib/amount';
import type { Tone } from '../Card';
import { ResourceIcon } from '../terminal/ResourceIcon';
import { ResourceName } from '../terminal/ResourceName';

/** Icon and name of a referenced resource. */
export function ResourceLabelView({
  resource,
  assetVersion,
  size = 32,
  amount,
}: {
  resource: ResourceLabel;
  assetVersion: string;
  size?: number;
  amount?: number;
}) {
  const { i18n } = useTranslation();
  return (
    <span className="resource-label" title={resource.id}>
      <ResourceIcon iconKey={resource.iconKey} assetVersion={assetVersion} size={size} />
      <span className="resource-label-text">
        <span className="resource-label-name">
          <ResourceName resource={resource} />
        </span>
        {amount !== undefined ? (
          <span className="resource-label-amount">× {exactAmount({ amount, unit: resource.unit }, i18n.language)}</span>
        ) : null}
      </span>
    </span>
  );
}

/**
 * Progress as the crafting system reports it (spec section 11). Without a meaningful percentage an activity
 * indicator is shown instead of an invented bar.
 */
export function ProgressBar({ progress, active }: { progress: Progress | null; active: boolean }) {
  const { t } = useTranslation();
  const percent = progress?.percent ?? null;
  if (percent === null) {
    return (
      <div className="progress" role="status">
        <div className={`progress-track${active ? ' progress-indeterminate' : ''}`}>
          <div className="progress-fill" />
        </div>
        <span className="progress-label">{active ? t('crafting.progressUnknown') : '—'}</span>
      </div>
    );
  }
  return (
    <div className="progress">
      <div
        className="progress-track"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(percent)}
        title={progress?.confidence === 'ESTIMATED' ? t('crafting.estimated') : undefined}
      >
        <div className="progress-fill" style={{ width: `${percent}%` }} />
      </div>
      <span className="progress-label">
        {progress?.confidence === 'ESTIMATED' ? '~' : ''}
        {percent.toFixed(percent < 10 ? 1 : 0)}%
      </span>
    </div>
  );
}

export const ORDER_TONE: Record<OrderState, Tone> = {
  SUBMITTING: 'neutral',
  RUNNING: 'accent',
  COMPLETED: 'success',
  CANCELLED: 'neutral',
  FAILED: 'danger',
  UNKNOWN: 'warning',
};

/** Whether the caller holds a capability on a network, from the (already polled) network detail. */
export function useCapability(networkId: string | undefined, capability: string): boolean {
  const network = useNetwork(networkId);
  return network.data?.capabilities.includes(capability) ?? false;
}
