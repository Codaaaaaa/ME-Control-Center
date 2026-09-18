import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import type { Order, Plan, ResourceLabel } from '../../api/crafting';
import { useCraftingMutations, usePlan } from '../../api/queries';
import { exactAmount } from '../../lib/amount';
import { formatBytes } from '../../lib/format';
import { Badge } from '../Card';
import { ErrorNotice, FormError, LoadingNotice } from '../StateNotice';
import { ResourceLabelView } from './CraftingBits';

type Craftable = Pick<ResourceLabel, 'id' | 'name' | 'nameSpans' | 'iconKey' | 'unit' | 'type' | 'modId' | 'modName'>;

/**
 * Crafting request (spec section 9): amount, then calculate, then review the plan and confirm. Nothing is
 * submitted until the player presses Start.
 */
export function CraftDialog({
  networkId,
  resource,
  assetVersion,
  initialAmount,
  onClose,
}: {
  networkId: string;
  resource: Craftable;
  assetVersion: string;
  initialAmount?: number;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const titleId = useId();
  const unit = resource.unit;
  const [amountText, setAmountText] = useState(() =>
    initialAmount === undefined ? '1' : String(unit ? initialAmount / unit.amountPerUnit : initialAmount));
  const [planId, setPlanId] = useState<string | null>(null);
  const [cpuId, setCpuId] = useState<string | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const mutations = useCraftingMutations(networkId, locale);
  const plan = usePlan(networkId, planId, locale);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const parsed = Number(amountText.replace(',', '.'));
  const amount = Number.isFinite(parsed) ? Math.round(unit ? parsed * unit.amountPerUnit : parsed) : NaN;
  const amountValid = Number.isFinite(amount) && amount >= 1 && amount <= Number.MAX_SAFE_INTEGER;

  const calculate = () => {
    if (!amountValid) return;
    setCpuId(null);
    mutations.calculate.mutate(
      { resourceId: resource.id, amount },
      { onSuccess: (created) => setPlanId(created.id) },
    );
  };

  const current: Plan | undefined = plan.data;

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="dialog-header">
          <h2 id={titleId}>{t('crafting.dialogTitle')}</h2>
          <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label={t('common.close')}>
            ✕
          </button>
        </header>

        <div className="dialog-body">
          <ResourceLabelView resource={resource} assetVersion={assetVersion} size={40} />

          {order ? (
            <div className="craft-started" role="status">
              <strong>{t('crafting.started')}</strong>
              <p>{t('crafting.startedHint', { cpu: order.cpu?.name ?? order.cpu?.id ?? t('crafting.cpuAuto') })}</p>
              <div className="dialog-actions">
                <Link className="button button-primary" to="/crafting" onClick={onClose}>
                  {t('crafting.viewOrders')}
                </Link>
                <button type="button" className="button" onClick={onClose}>
                  {t('common.close')}
                </button>
              </div>
            </div>
          ) : (
            <>
              <form
                className="craft-amount"
                onSubmit={(event) => {
                  event.preventDefault();
                  calculate();
                }}
              >
                <label className="form-field">
                  <span>{unit ? t('crafting.amountIn', { unit: unit.symbol }) : t('crafting.amount')}</span>
                  <input
                    className="input"
                    inputMode="decimal"
                    value={amountText}
                    onChange={(event) => {
                      setAmountText(event.target.value);
                      setPlanId(null);
                    }}
                    autoFocus
                  />
                </label>
                <button type="submit" className="button button-primary" disabled={!amountValid || mutations.calculate.isPending}>
                  {mutations.calculate.isPending ? t('crafting.calculating') : t('crafting.calculate')}
                </button>
              </form>
              <FormError error={mutations.calculate.error} />

              {planId === null ? null : plan.isError ? (
                <ErrorNotice title={t('crafting.planTitle')} error={plan.error} onRetry={() => void plan.refetch()} />
              ) : !current || current.state === 'CALCULATING' ? (
                <LoadingNotice label={t('crafting.calculatingLong')} />
              ) : current.state === 'FAILED' ? (
                <p className="form-error" role="alert">
                  {current.errorCode && i18n.exists(`errors.codes.${current.errorCode}`)
                    ? t(`errors.codes.${current.errorCode}`)
                    : current.errorMessage}
                </p>
              ) : (
                <PlanReview plan={current} cpuId={cpuId} onCpu={setCpuId} />
              )}

              {current?.state === 'READY' ? (
                <>
                  <FormError error={mutations.submit.error} />
                  <div className="dialog-actions">
                    <button
                      type="button"
                      className="button button-primary"
                      disabled={!current.complete || mutations.submit.isPending}
                      onClick={() =>
                        mutations.submit.mutate({ planId: current.id, cpuId }, { onSuccess: (created) => setOrder(created) })}
                    >
                      {mutations.submit.isPending ? t('crafting.starting') : t('crafting.start')}
                    </button>
                    <button type="button" className="button" onClick={onClose}>
                      {t('common.cancel')}
                    </button>
                  </div>
                </>
              ) : null}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function PlanReview({ plan, cpuId, onCpu }: { plan: Plan; cpuId: string | null; onCpu: (id: string | null) => void }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const missing = plan.entries.filter((entry) => entry.missing > 0).length;
  return (
    <div className="plan">
      <div className="plan-summary">
        {plan.complete ? (
          <Badge tone="success">{t('crafting.planReady')}</Badge>
        ) : (
          <Badge tone="danger">{t('crafting.planMissing', { count: missing })}</Badge>
        )}
        {plan.bytes !== null ? <span className="muted">{t('crafting.bytes', { bytes: formatBytes(plan.bytes) })}</span> : null}
        {plan.multiplePaths ? <span className="muted">{t('crafting.multiplePaths')}</span> : null}
      </div>

      <div className="plan-table" role="table" aria-label={t('crafting.planTitle')}>
        <div className="plan-row plan-head" role="row">
          <span role="columnheader">{t('crafting.resource')}</span>
          <span role="columnheader">{t('crafting.stored')}</span>
          <span role="columnheader">{t('crafting.toCraft')}</span>
          <span role="columnheader">{t('crafting.missing')}</span>
        </div>
        {plan.entries.map((entry) => (
          <div key={entry.resource.id} className={`plan-row${entry.missing > 0 ? ' plan-row-missing' : ''}`} role="row">
            <span role="cell">
              <ResourceLabelView resource={entry.resource} assetVersion={plan.assetVersion} size={24} />
            </span>
            <span role="cell">{entry.stored > 0 ? exactAmount({ amount: entry.stored, unit: entry.resource.unit }, locale) : ''}</span>
            <span role="cell">{entry.toCraft > 0 ? exactAmount({ amount: entry.toCraft, unit: entry.resource.unit }, locale) : ''}</span>
            <span role="cell">{entry.missing > 0 ? exactAmount({ amount: entry.missing, unit: entry.resource.unit }, locale) : ''}</span>
          </div>
        ))}
      </div>
      {plan.totalEntries > plan.entries.length ? (
        <p className="footnote">{t('crafting.truncated', { shown: plan.entries.length, total: plan.totalEntries })}</p>
      ) : null}

      {plan.complete ? (
        <fieldset className="cpu-choice">
          <legend>{t('crafting.cpu')}</legend>
          <label className="cpu-option">
            <input type="radio" name="cpu" checked={cpuId === null} onChange={() => onCpu(null)} />
            <span>{t('crafting.cpuAuto')}</span>
          </label>
          {plan.cpus.map((cpu) => (
            <label key={cpu.id} className={`cpu-option${cpu.reason ? ' cpu-option-disabled' : ''}`}>
              <input
                type="radio"
                name="cpu"
                disabled={cpu.reason !== null}
                checked={cpuId === cpu.id}
                onChange={() => onCpu(cpu.id)}
              />
              <span>{cpu.name ?? t('cpus.unnamed')}</span>
              <span className="muted">
                {formatBytes(cpu.storageBytes)} · {t('cpus.coProcessorsShort', { count: cpu.coProcessors })}
              </span>
              {cpu.reason ? <Badge tone="neutral">{t(`crafting.unsuitable.${cpu.reason}`)}</Badge> : null}
            </label>
          ))}
        </fieldset>
      ) : (
        <p className="form-hint">{t('crafting.planMissingHint')}</p>
      )}
    </div>
  );
}
