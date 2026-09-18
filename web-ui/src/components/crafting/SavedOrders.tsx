import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResourceLabel, SavedOrder } from '../../api/crafting';
import { useCpus, useSavedOrderMutations, useSavedOrders } from '../../api/queries';
import { ConfirmButton } from '../ConfirmButton';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../StateNotice';
import { CraftDialog } from './CraftDialog';
import { ResourceLabelView } from './CraftingBits';

/** What the preset form edits: a new preset for a resource, or an existing one. */
export type PresetDraft = { resource: ResourceLabel; amount: number; cpuId: string | null; name: string; notes: string; id?: string };

/** Saved Craft Orders (spec section 24): Run, Edit, Clone, Delete. Running always calculates and confirms. */
export function SavedOrders({ networkId, canCraft }: { networkId: string; canCraft: boolean }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const saved = useSavedOrders(networkId, locale);
  const mutations = useSavedOrderMutations(networkId, locale);
  const [running, setRunning] = useState<SavedOrder | null>(null);
  const [editing, setEditing] = useState<PresetDraft | null>(null);

  if (saved.isPending) return <LoadingNotice />;
  if (saved.isError) {
    return <ErrorNotice title={t('crafting.saved.error')} error={saved.error} onRetry={() => void saved.refetch()} />;
  }
  const { orders, assetVersion } = saved.data;
  const draftOf = (order: SavedOrder): PresetDraft => ({
    resource: order.target, amount: order.amount, cpuId: order.cpuId, name: order.name, notes: order.notes,
  });

  return (
    <>
      <FormError error={mutations.remove.error ?? mutations.create.error} />
      {orders.length === 0 ? (
        <EmptyNotice title={t('crafting.saved.none')}>{t('crafting.saved.noneHint')}</EmptyNotice>
      ) : (
        <div className="order-list">
          {orders.map((order) => (
            <article key={order.id} className="order-card">
              <div className="order-main">
                <div>
                  <div className="list-title">{order.name}</div>
                  <ResourceLabelView resource={order.target} assetVersion={assetVersion} size={32} amount={order.amount} />
                </div>
              </div>
              {order.notes ? <p className="form-hint saved-notes">{order.notes}</p> : null}
              <div className="order-actions">
                {canCraft ? (
                  <>
                    <button type="button" className="button button-primary button-small" onClick={() => setRunning(order)}>
                      {t('crafting.saved.run')}
                    </button>
                    <button type="button" className="button button-small" onClick={() => setEditing({ ...draftOf(order), id: order.id })}>
                      {t('crafting.saved.edit')}
                    </button>
                    <button type="button" className="button button-quiet button-small"
                      onClick={() => setEditing({ ...draftOf(order), name: t('crafting.saved.copyName', { name: order.name }) })}>
                      {t('crafting.saved.clone')}
                    </button>
                  </>
                ) : null}
                <ConfirmButton label={t('crafting.saved.delete')} onConfirm={() => mutations.remove.mutate(order.id)}
                  disabled={mutations.remove.isPending} />
              </div>
            </article>
          ))}
        </div>
      )}

      {running ? (
        <CraftDialog networkId={networkId} resource={running.target} assetVersion={assetVersion} initialAmount={running.amount}
          preferredCpuId={running.cpuId} savedOrder onClose={() => setRunning(null)} />
      ) : null}
      {editing ? (
        <PresetDialog networkId={networkId} draft={editing} assetVersion={assetVersion} onClose={() => setEditing(null)} />
      ) : null}
    </>
  );
}

/** Creates or edits a preset. The resource is fixed; everything else can change. */
export function PresetDialog({
  networkId,
  draft,
  assetVersion,
  onClose,
}: {
  networkId: string;
  draft: PresetDraft;
  assetVersion: string;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const titleId = useId();
  const unit = draft.resource.unit;
  const mutations = useSavedOrderMutations(networkId, locale);
  const cpus = useCpus(networkId, locale, true);
  const [name, setName] = useState(draft.name);
  const [notes, setNotes] = useState(draft.notes);
  const [cpuId, setCpuId] = useState<string | null>(draft.cpuId);
  const [amountText, setAmountText] = useState(String(unit ? draft.amount / unit.amountPerUnit : draft.amount));

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const parsed = Number(amountText.replace(',', '.'));
  const amount = Number.isFinite(parsed) ? Math.round(unit ? parsed * unit.amountPerUnit : parsed) : NaN;
  const valid = name.trim().length > 0 && Number.isFinite(amount) && amount >= 1 && amount <= Number.MAX_SAFE_INTEGER;
  const pending = mutations.create.isPending || mutations.update.isPending;

  const save = () => {
    const input = { name: name.trim(), resourceId: draft.resource.id, amount, cpuId, notes };
    const done = { onSuccess: onClose };
    if (draft.id) mutations.update.mutate({ id: draft.id, input }, done);
    else mutations.create.mutate(input, done);
  };

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="dialog-header">
          <h2 id={titleId}>{draft.id ? t('crafting.saved.editTitle') : t('crafting.saved.newTitle')}</h2>
          <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label={t('common.close')}>
            ✕
          </button>
        </header>
        <form className="dialog-body preset-form" onSubmit={(event) => {
          event.preventDefault();
          if (valid) save();
        }}>
          <ResourceLabelView resource={draft.resource} assetVersion={assetVersion} size={40} />
          <label className="form-field">
            <span>{t('crafting.saved.name')}</span>
            <input className="input" value={name} maxLength={64} onChange={(event) => setName(event.target.value)} autoFocus />
          </label>
          <label className="form-field">
            <span>{unit ? t('crafting.amountIn', { unit: unit.symbol }) : t('crafting.amount')}</span>
            <input className="input" inputMode="decimal" value={amountText} onChange={(event) => setAmountText(event.target.value)} />
          </label>
          <label className="form-field">
            <span>{t('crafting.cpu')}</span>
            <select className="input" value={cpuId ?? ''} onChange={(event) => setCpuId(event.target.value || null)}>
              <option value="">{t('crafting.cpuAuto')}</option>
              {cpuId && !cpus.data?.cpus.some((cpu) => cpu.id === cpuId) ? <option value={cpuId}>{cpuId}</option> : null}
              {cpus.data?.cpus.map((cpu) => <option key={cpu.id} value={cpu.id}>{cpu.name ?? t('cpus.unnamed')}</option>)}
            </select>
          </label>
          <label className="form-field">
            <span>{t('crafting.saved.notes')}</span>
            <textarea className="input" rows={3} maxLength={1000} value={notes} onChange={(event) => setNotes(event.target.value)} />
          </label>
          <p className="form-hint">{t('crafting.saved.confirmHint')}</p>
          <FormError error={mutations.create.error ?? mutations.update.error} />
          <div className="dialog-actions">
            <button type="submit" className="button button-primary" disabled={!valid || pending}>
              {pending ? t('common.saving') : t('common.save')}
            </button>
            <button type="button" className="button" onClick={onClose}>{t('common.cancel')}</button>
          </div>
        </form>
      </div>
    </div>
  );
}
