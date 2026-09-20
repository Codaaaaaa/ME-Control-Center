import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  createRestockRule,
  deleteRequesterRequest,
  deleteRestockRule,
  fetchRequesters,
  fetchRestockRules,
  stopAutomation,
  updateRestockRule,
  type Requester,
  type RestockInput,
  type RestockRule,
} from '../api/automation';
import type { ResourceLabel } from '../api/crafting';
import { useNetworks } from '../api/queries';
import { Badge, Card } from '../components/Card';
import { ConfirmButton } from '../components/ConfirmButton';
import { ResourceLabelView } from '../components/crafting/CraftingBits';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { ResourcePicker } from '../components/patterns/ResourcePicker';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { exactAmount } from '../lib/amount';
import { formatRelative } from '../lib/format';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

const REFRESH_MS = 15_000;

/**
 * Auto Restock / Keep Stock (spec section 25). Rules belong to the network and need the Manager role; they only
 * ever run when the server has automation switched on.
 */
export function AutomationPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.automation')}</h1>
          <p>{selected ? selected.displayName : t('automation.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>
      {networks.isPending ? (
        <LoadingNotice />
      ) : networks.isError && !networks.data ? (
        <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
      ) : selected ? (
        <>
          <Restock networkId={selected.id} />
          <Requesters networkId={selected.id} />
        </>
      ) : (
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      )}
    </>
  );
}

function useRestock(networkId: string, locale: string) {
  const client = useQueryClient();
  const key = ['automation', networkId, locale];
  const refresh = () => void client.invalidateQueries({ queryKey: ['automation', networkId] });
  return {
    rules: useQuery({
      queryKey: key,
      queryFn: ({ signal }) => fetchRestockRules(networkId, locale, signal),
      refetchInterval: REFRESH_MS,
    }),
    create: useMutation({ mutationFn: (input: RestockInput) => createRestockRule(networkId, input, locale), onSuccess: refresh }),
    update: useMutation({
      mutationFn: ({ id, input }: { id: string; input: RestockInput }) => updateRestockRule(networkId, id, input, locale),
      onSuccess: refresh,
    }),
    remove: useMutation({ mutationFn: (id: string) => deleteRestockRule(networkId, id), onSuccess: refresh }),
    stop: useMutation({ mutationFn: () => stopAutomation(networkId, locale), onSuccess: refresh }),
  };
}

function Restock({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const restock = useRestock(networkId, locale);
  const data = restock.rules.data;
  const enabledRules = data?.rules.filter((rule) => rule.enabled).length ?? 0;

  return (
    <div className="grid">
      <Card
        className="card-wide"
        title={t('automation.rules')}
        badge={data && !data.serverEnabled ? <Badge tone="warning">{t('automation.serverOff')}</Badge> : undefined}
      >
        <p className="muted-text">{t('automation.rulesHint')}</p>
        {data && !data.serverEnabled ? <p className="muted-text">{t('automation.serverOffHint')}</p> : null}
        {restock.rules.isPending ? (
          <LoadingNotice />
        ) : restock.rules.isError ? (
          <ErrorNotice title={t('automation.rules')} error={restock.rules.error} onRetry={() => void restock.rules.refetch()} />
        ) : data && data.rules.length === 0 ? (
          <p className="muted-text">{t('automation.noRules')}</p>
        ) : data ? (
          <ul className="list">
            {data.rules.map((rule) => (
              <RuleRow
                key={rule.id}
                rule={rule}
                assetVersion={data.assetVersion}
                busy={restock.update.isPending || restock.remove.isPending}
                onToggle={() => restock.update.mutate({ id: rule.id, input: { enabled: !rule.enabled } })}
                onDelete={() => restock.remove.mutate(rule.id)}
              />
            ))}
          </ul>
        ) : null}
        <FormError error={restock.update.error ?? restock.remove.error ?? restock.stop.error} />
        {enabledRules > 0 ? (
          <div className="order-actions">
            <ConfirmButton label={t('automation.stopAll')} onConfirm={() => restock.stop.mutate()} disabled={restock.stop.isPending} />
            <span className="muted">{t('automation.stopAllHint')}</span>
          </div>
        ) : null}
        <NewRule networkId={networkId} full={data !== undefined && data.rules.length >= data.limit} />
      </Card>
    </div>
  );
}

function RuleRow({ rule, assetVersion, busy, onToggle, onDelete }: {
  rule: RestockRule;
  assetVersion: string;
  busy: boolean;
  onToggle: () => void;
  onDelete: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const amount = (raw: number) => exactAmount({ amount: raw, unit: rule.resource.unit }, locale);
  const paused = rule.pausedUntil !== null && new Date(rule.pausedUntil).getTime() > Date.now();
  return (
    <li className="list-row">
      <div className="list-main">
        <div className="list-title">
          <ResourceLabelView resource={rule.resource} assetVersion={assetVersion} size={20} />{' '}
          {!rule.enabled ? (
            <Badge tone="neutral">{t('automation.disabled')}</Badge>
          ) : paused ? (
            <Badge tone="warning">{t('automation.backingOff')}</Badge>
          ) : rule.stored !== null && rule.stored < rule.minimum ? (
            <Badge tone="accent">{t('automation.below')}</Badge>
          ) : (
            <Badge tone="success">{t('automation.ok')}</Badge>
          )}
        </div>
        <div className="list-meta">
          <span>{t('automation.range', { minimum: amount(rule.minimum), target: amount(rule.restockTo) })}</span>
          {rule.stored !== null ? <span>{t('automation.stored', { amount: amount(rule.stored) })}</span> : null}
          <span>{t('automation.cooldown', { minutes: rule.cooldownMinutes })}</span>
          {rule.cpuId ? <span>{t('automation.cpu', { cpu: rule.cpuId })}</span> : null}
          {rule.createdBy ? <span>{t('automation.createdBy', { player: rule.createdBy })}</span> : null}
          {rule.lastRunAt ? <span>{t('automation.lastRun', { time: formatRelative(rule.lastRunAt, locale) })}</span> : null}
          {rule.failures > 0 ? (
            <span>{t('automation.failures', { count: rule.failures, error: rule.lastError ?? '' })}</span>
          ) : null}
        </div>
      </div>
      <div className="list-actions">
        <button type="button" className="button button-quiet button-small" onClick={onToggle} disabled={busy}>
          {rule.enabled ? t('automation.disable') : t('automation.enable')}
        </button>
        <ConfirmButton label={t('automation.delete')} onConfirm={onDelete} disabled={busy} />
      </div>
    </li>
  );
}

function NewRule({ networkId, full }: { networkId: string; full: boolean }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const restock = useRestock(networkId, locale);
  const [resource, setResource] = useState<{ label: ResourceLabel; assetVersion: string } | null>(null);
  const [minimumText, setMinimumText] = useState('');
  const [targetText, setTargetText] = useState('');
  const [cooldown, setCooldown] = useState('30');
  const [picking, setPicking] = useState(false);

  const unit = resource?.label.unit ?? null;
  const parse = (text: string) => {
    const value = Number(text.replace(',', '.'));
    return text.trim() === '' || !Number.isFinite(value) ? NaN : Math.round(unit ? value * unit.amountPerUnit : value);
  };
  const minimum = parse(minimumText);
  const restockTo = parse(targetText);
  const cooldownMinutes = Number(cooldown);
  const valid = resource !== null
    && Number.isFinite(minimum) && minimum >= 0
    && Number.isFinite(restockTo) && restockTo > minimum
    && Number.isInteger(cooldownMinutes) && cooldownMinutes >= 1;

  return (
    <form
      className="alert-form"
      onSubmit={(event) => {
        event.preventDefault();
        if (!valid || resource === null) return;
        restock.create.mutate(
          { resourceId: resource.label.id, minimum, restockTo, cooldownMinutes, enabled: false },
          { onSuccess: () => { setResource(null); setMinimumText(''); setTargetText(''); } },
        );
      }}
    >
      <h3 className="config-section-title">{t('automation.newRule')}</h3>
      <div className="alert-form-fields">
        <div className="form-field">
          <span>{t('automation.resource')}</span>
          <div className="alert-resource">
            {resource ? <ResourceLabelView resource={resource.label} assetVersion={resource.assetVersion} size={24} /> : null}
            <button type="button" className="button button-small" onClick={() => setPicking(true)}>
              {resource ? t('automation.change') : t('automation.choose')}
            </button>
          </div>
        </div>
        <label className="form-field">
          <span>{unit ? t('automation.minimumIn', { unit: unit.symbol }) : t('automation.minimum')}</span>
          <input className="input" inputMode="decimal" value={minimumText} onChange={(event) => setMinimumText(event.target.value)} />
        </label>
        <label className="form-field">
          <span>{unit ? t('automation.targetIn', { unit: unit.symbol }) : t('automation.target')}</span>
          <input className="input" inputMode="decimal" value={targetText} onChange={(event) => setTargetText(event.target.value)} />
        </label>
        <label className="form-field">
          <span>{t('automation.cooldownLabel')}</span>
          <input className="input" inputMode="numeric" value={cooldown} onChange={(event) => setCooldown(event.target.value)} />
        </label>
      </div>
      <FormError error={restock.create.error} />
      <div className="order-actions">
        <button type="submit" className="button button-primary" disabled={!valid || full || restock.create.isPending}>
          {t('automation.add')}
        </button>
        <span className="muted">{t('automation.addHint')}</span>
        {full ? <span className="muted">{t('automation.full')}</span> : null}
      </div>
      {picking ? (
        <ResourcePicker
          networkId={networkId}
          itemsOnly={false}
          title={t('automation.choose')}
          onPick={(label, assetVersion) => {
            setResource({ label, assetVersion });
            setPicking(false);
          }}
          onClose={() => setPicking(false)}
        />
      ) : null}
    </form>
  );
}

/**
 * The ME Requesters standing in the world (the optional ME Requester mod). They keep stock exactly as the rules
 * above do, so they belong on the same page; ME Control Center shows what each slot asks for and lets a Manager
 * empty one. The card stays hidden on servers without the mod.
 */
function Requesters({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const client = useQueryClient();
  const requesters = useQuery({
    queryKey: ['requesters', networkId, locale],
    queryFn: ({ signal }) => fetchRequesters(networkId, locale, signal),
    refetchInterval: REFRESH_MS,
  });
  const remove = useMutation({
    mutationFn: ({ id, slot }: { id: string; slot: number }) => deleteRequesterRequest(networkId, id, slot),
    onSuccess: () => void client.invalidateQueries({ queryKey: ['requesters', networkId] }),
  });
  const data = requesters.data;
  if (!requesters.isError && (!data || !data.supported)) {
    return null;
  }

  return (
    <div className="grid">
      <Card className="card-wide" title={t('automation.requesters')}>
        <p className="muted-text">{t('automation.requestersHint')}</p>
        {requesters.isError ? (
          <ErrorNotice
            title={t('automation.requesters')}
            error={requesters.error}
            onRetry={() => void requesters.refetch()}
          />
        ) : data && data.requesters.length === 0 ? (
          <p className="muted-text">{t('automation.noRequesters')}</p>
        ) : data ? (
          <ul className="list">
            {data.requesters.map((requester) =>
              requester.requests.map((request) => (
                <RequestRow
                  key={`${requester.id}#${request.slot}`}
                  requester={requester}
                  request={request}
                  assetVersion={data.assetVersion}
                  busy={remove.isPending}
                  onDelete={() => remove.mutate({ id: requester.id, slot: request.slot })}
                />
              )),
            )}
          </ul>
        ) : null}
        <FormError error={remove.error} />
      </Card>
    </div>
  );
}

function RequestRow({ requester, request, assetVersion, busy, onDelete }: {
  requester: Requester;
  request: Requester['requests'][number];
  assetVersion: string;
  busy: boolean;
  onDelete: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const amount = (raw: number) => exactAmount({ amount: raw, unit: request.resource.unit }, locale);
  return (
    <li className="list-row">
      <div className="list-main">
        <div className="list-title">
          <ResourceLabelView resource={request.resource} assetVersion={assetVersion} size={20} />{' '}
          {!request.enabled ? (
            <Badge tone="neutral">{t('automation.disabled')}</Badge>
          ) : !requester.online ? (
            <Badge tone="warning">{t('automation.requesterOffline')}</Badge>
          ) : request.status ? (
            <Badge tone={request.status === 'MISSING' ? 'warning' : 'accent'}>
              {t(`automation.requestStatus.${request.status}`, { defaultValue: request.status })}
            </Badge>
          ) : null}
        </div>
        <div className="list-meta">
          <span>{t('automation.keepStocked', { amount: amount(request.amount) })}</span>
          <span>{t('automation.batch', { amount: amount(request.batch) })}</span>
          {request.stored !== null ? <span>{t('automation.stored', { amount: amount(request.stored) })}</span> : null}
          <span>
            {requester.name ?? t('automation.requester')}
            {requester.location
              ? ` - ${requester.location.dimension} ${requester.location.x}, ${requester.location.y}, ${requester.location.z}`
              : ''}
          </span>
        </div>
      </div>
      <div className="list-actions">
        <ConfirmButton label={t('automation.delete')} onConfirm={onDelete} disabled={busy} />
      </div>
    </li>
  );
}
