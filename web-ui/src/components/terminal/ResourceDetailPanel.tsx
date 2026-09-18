import { useQuery } from '@tanstack/react-query';
import { useState, type ComponentProps } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchResourceDetail } from '../../api/resources';
import { exactAmount } from '../../lib/amount';
import { formatRelative } from '../../lib/format';
import { Badge, Field, Fields } from '../Card';
import { CraftDialog } from '../crafting/CraftDialog';
import { useCapability } from '../crafting/CraftingBits';
import { ErrorNotice, LoadingNotice } from '../StateNotice';
import { ResourceIcon } from './ResourceIcon';
import { ResourceName } from './ResourceName';

/** Resource detail (spec section 7.5). A drawer on desktop, a sheet on phones. */
export function ResourceDetailPanel({
  networkId,
  resourceId,
  snapshotId,
  onClose,
}: {
  networkId: string;
  resourceId: string;
  snapshotId: string | undefined;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const detail = useQuery({
    queryKey: ['resourceDetail', networkId, resourceId, snapshotId, locale],
    queryFn: ({ signal }) => fetchResourceDetail(networkId, resourceId, snapshotId, locale, signal),
    // A new snapshot keeps showing the same resource's previous detail instead of flashing a loading state.
    placeholderData: (previous) => (previous?.resource.id === resourceId ? previous : undefined),
  });
  const canCraft = useCapability(networkId, 'SUBMIT_CRAFT');
  // Captured when the dialog opens so snapshot refreshes (or a failed refetch) never unmount it mid-request.
  const [crafting, setCrafting] = useState<{ resource: ComponentProps<typeof CraftDialog>['resource']; assetVersion: string } | null>(null);

  return (
    <aside className="detail-panel" aria-label={t('terminal.detail')}>
      <header className="detail-header">
        <h2>{detail.data ? <ResourceName resource={detail.data.resource} /> : t('common.loading')}</h2>
        <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label={t('common.close')}>
          ✕
        </button>
      </header>

      {detail.isPending ? (
        <LoadingNotice />
      ) : detail.isError ? (
        <ErrorNotice title={t('terminal.detail')} error={detail.error} onRetry={() => void detail.refetch()} />
      ) : (
        <>
          <div className="detail-hero">
            <ResourceIcon iconKey={detail.data.resource.iconKey} assetVersion={detail.data.assetVersion} size={64} />
            <div>
              <div className="detail-amount">{exactAmount(detail.data.resource, locale)}</div>
              <div className="list-meta">
                {detail.data.resource.craftable ? <Badge tone="accent">{t('terminal.craftable')}</Badge> : null}
                {detail.data.resource.crafting !== null ? (
                  <Badge tone="warning">
                    {t('terminal.craftingAmount', { amount: exactAmount({ amount: detail.data.resource.crafting, unit: detail.data.resource.unit }, locale) })}
                  </Badge>
                ) : null}
              </div>
            </div>
          </div>

          <Fields>
            <Field label={t('terminal.mod')}>{detail.data.resource.modName}</Field>
            <Field label={t('terminal.registryId')}>
              <code>{detail.data.registryId}</code>
            </Field>
            <Field label={t('terminal.type')}>{detail.data.resource.type}</Field>
            {detail.data.variant ? (
              <Field label={t('terminal.variant')}>
                <code title={t('terminal.variantHint')}>{detail.data.variant}</code>
              </Field>
            ) : null}
            <Field label={t('terminal.snapshotAge')}>{formatRelative(detail.data.capturedAt, locale)}</Field>
          </Fields>

          {detail.data.tags.length > 0 ? (
            <div className="detail-tags">
              <div className="metric-label">{t('terminal.tags')}</div>
              <div className="chips">
                {detail.data.tags.map((tag) => (
                  <code key={tag} className="chip">
                    #{tag}
                  </code>
                ))}
              </div>
            </div>
          ) : null}

          {detail.data.resource.craftable ? (
            <div className="detail-actions">
              {canCraft ? (
                <button type="button" className="button button-primary" onClick={() => setCrafting({ resource: detail.data.resource, assetVersion: detail.data.assetVersion })}
                >
                  {t('crafting.craft')}
                </button>
              ) : (
                <p className="footnote">{t('crafting.needOperator')}</p>
              )}
            </div>
          ) : null}
        </>
      )}
      {crafting ? (
        <CraftDialog
          networkId={networkId}
          resource={crafting.resource}
          assetVersion={crafting.assetVersion}
          onClose={() => setCrafting(null)}
        />
      ) : null}
    </aside>
  );
}
