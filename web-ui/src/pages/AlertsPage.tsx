import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import {
  ALERT_SHAPE,
  ALERT_TYPES,
  eventMessage,
  type AlertEvent,
  type AlertRule,
  type AlertType,
} from '../api/alerts';
import type { ResourceLabel } from '../api/crafting';
import {
  useAlertEvents,
  useAlertRuleMutations,
  useAlertRules,
  useAlertSettings,
  useAlertSettingsMutations,
  useNetworks,
} from '../api/queries';
import { Badge, Card } from '../components/Card';
import { ConfirmButton } from '../components/ConfirmButton';
import { ResourceLabelView } from '../components/crafting/CraftingBits';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { ResourcePicker } from '../components/patterns/ResourcePicker';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { exactAmount } from '../lib/amount';
import { formatRelative } from '../lib/format';
import { useAlertPrefs } from '../stores/alertPrefs';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

/** Alerts (spec section 23): personal rules per network, what they reported, and where they are sent. */
export function AlertsPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.alerts')}</h1>
          <p>{selected ? selected.displayName : t('alerts.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>
      {networks.isPending ? (
        <LoadingNotice />
      ) : networks.isError && !networks.data ? (
        <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
      ) : !selected ? (
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      ) : (
        <div className="grid">
          <Rules networkId={selected.id} />
          <Events networkId={selected.id} />
          <Channels />
        </div>
      )}
    </>
  );
}

function useThresholdText() {
  const { t, i18n } = useTranslation();
  return (rule: Pick<AlertRule, 'type' | 'threshold' | 'windowMinutes' | 'resource'>): string => {
    if (rule.threshold === null) return '';
    const kind = ALERT_SHAPE[rule.type].threshold;
    const text = kind === 'percent' ? `${rule.threshold}%`
      : kind === 'minutes' ? t('alerts.minutes', { count: rule.threshold })
        : exactAmount({ amount: rule.threshold, unit: rule.resource?.unit ?? null }, i18n.language);
    return rule.windowMinutes !== null ? t('alerts.withinWindow', { change: text, count: rule.windowMinutes }) : text;
  };
}

/** Overview card (spec section 6.5): the player's alerts that currently hold on this network. */
export function ActiveAlertsCard({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const rules = useAlertRules(networkId, locale);
  const thresholdText = useThresholdText();
  const active = rules.data?.rules.filter((rule) => rule.enabled && rule.active) ?? [];
  return (
    <Card title={t('alerts.current')} badge={active.length > 0 ? <Badge tone="danger">{active.length}</Badge> : undefined}>
      {rules.isPending ? (
        <LoadingNotice />
      ) : rules.isError ? (
        <ErrorNotice title={t('alerts.current')} error={rules.error} onRetry={() => void rules.refetch()} />
      ) : active.length === 0 ? (
        <p className="muted-text">{rules.data.rules.length === 0 ? t('alerts.noRulesShort') : t('alerts.allClear')}</p>
      ) : (
        <ul className="list list-compact">
          {active.map((rule) => {
            const threshold = thresholdText(rule);
            return (
              <li key={rule.id} className="list-row">
                <div className="list-main">
                  <div className="list-title">{t(`alerts.types.${rule.type}`)}{threshold ? ` · ${threshold}` : ''}</div>
                  {rule.resource ? (
                    <div className="list-meta">
                      <ResourceLabelView resource={rule.resource} assetVersion={rules.data.assetVersion} size={20} />
                    </div>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
      )}
      <footer className="card-footer">
        <Link to="/alerts" className="button button-quiet button-small">{t('alerts.manage')}</Link>
      </footer>
    </Card>
  );
}

function Rules({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const rules = useAlertRules(networkId, locale);
  const mutations = useAlertRuleMutations(networkId, locale);

  return (
    <Card className="card-wide" title={t('alerts.rules')}>
      <p className="muted-text">{t('alerts.rulesHint')}</p>
      {rules.isPending ? (
        <LoadingNotice />
      ) : rules.isError ? (
        <ErrorNotice title={t('alerts.rules')} error={rules.error} onRetry={() => void rules.refetch()} />
      ) : rules.data.rules.length === 0 ? (
        <p className="muted-text">{t('alerts.noRules')}</p>
      ) : (
        <ul className="list">
          {rules.data.rules.map((rule) => (
            <RuleRow key={rule.id} rule={rule} assetVersion={rules.data.assetVersion}
              onToggle={() => mutations.update.mutate({ id: rule.id, change: { enabled: !rule.enabled } })}
              onDelete={() => mutations.remove.mutate(rule.id)} busy={mutations.update.isPending || mutations.remove.isPending} />
          ))}
        </ul>
      )}
      <FormError error={mutations.update.error ?? mutations.remove.error} />
      <NewRule networkId={networkId} full={rules.data !== undefined && rules.data.rules.length >= rules.data.limit} />
    </Card>
  );
}

function RuleRow({ rule, assetVersion, onToggle, onDelete, busy }: {
  rule: AlertRule;
  assetVersion: string;
  onToggle: () => void;
  onDelete: () => void;
  busy: boolean;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const threshold = useThresholdText()(rule);
  return (
    <li className="list-row">
      <div className="list-main">
        <div className="list-title">
          {t(`alerts.types.${rule.type}`)}{threshold ? ` · ${threshold}` : ''}{' '}
          {!rule.enabled ? <Badge tone="neutral">{t('alerts.disabled')}</Badge>
            : rule.active ? <Badge tone="danger">{t('alerts.active')}</Badge>
              : <Badge tone="success">{t('alerts.ok')}</Badge>}
        </div>
        <div className="list-meta">
          {rule.resource ? <ResourceLabelView resource={rule.resource} assetVersion={assetVersion} size={20} /> : null}
          {ALERT_SHAPE[rule.type].cooldown
            ? <span>{t('alerts.cooldown', { minutes: rule.cooldownMinutes })}</span> : null}
          {rule.notifiedAt ? <span>{t('alerts.lastNotified', { time: formatRelative(rule.notifiedAt, locale) })}</span> : null}
        </div>
      </div>
      <div className="list-actions">
        <button type="button" className="button button-quiet button-small" onClick={onToggle} disabled={busy}>
          {rule.enabled ? t('alerts.disable') : t('alerts.enable')}
        </button>
        <ConfirmButton label={t('alerts.delete')} onConfirm={onDelete} disabled={busy} />
      </div>
    </li>
  );
}

const BLANK_PATTERN = 'item:ae2:blank_pattern';

function NewRule({ networkId, full }: { networkId: string; full: boolean }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const mutations = useAlertRuleMutations(networkId, locale);
  const [type, setType] = useState<AlertType>('RESOURCE_BELOW');
  const [resource, setResource] = useState<{ label: ResourceLabel; assetVersion: string } | null>(null);
  const [thresholdText, setThresholdText] = useState('');
  const [cooldown, setCooldown] = useState('30');
  const [windowText, setWindowText] = useState('60');
  const [picking, setPicking] = useState(false);
  const shape = ALERT_SHAPE[type];
  const unit = shape.threshold === 'amount' ? resource?.label.unit ?? null : null;

  const parsed = Number(thresholdText.replace(',', '.'));
  const threshold = shape.threshold === null ? null
    : Number.isFinite(parsed) && thresholdText.trim() !== '' ? Math.round(unit ? parsed * unit.amountPerUnit : parsed) : NaN;
  const cooldownMinutes = shape.cooldown ? Number(cooldown) : 0;
  const windowMinutes = shape.window ? Number(windowText) : null;
  const valid = (shape.resource !== 'required' || resource !== null)
    && (threshold === null || (Number.isFinite(threshold) && threshold >= 0))
    && (windowMinutes === null || (Number.isInteger(windowMinutes) && windowMinutes >= 1))
    && Number.isInteger(cooldownMinutes) && cooldownMinutes >= 0;

  const create = (input: Parameters<typeof mutations.create.mutate>[0]) =>
    mutations.create.mutate(input, { onSuccess: () => { setResource(null); setThresholdText(''); } });

  return (
    <form className="alert-form" onSubmit={(event) => {
      event.preventDefault();
      if (!valid) return;
      create({ type, resourceId: shape.resource === 'none' ? null : resource?.label.id ?? null, threshold, windowMinutes,
        cooldownMinutes });
    }}>
      <h3 className="config-section-title">{t('alerts.newRule')}</h3>
      <div className="alert-form-fields">
        <label className="form-field">
          <span>{t('alerts.type')}</span>
          <select className="input" value={type} onChange={(event) => {
            const next = event.target.value as AlertType;
            setType(next);
            setCooldown(ALERT_SHAPE[next].cooldown ? '30' : '0');
            setThresholdText(next === 'CRAFT_STALLED' ? '15' : '');
          }}>
            {ALERT_TYPES.map((name) => <option key={name} value={name}>{t(`alerts.types.${name}`)}</option>)}
          </select>
        </label>
        {shape.resource !== 'none' ? (
          <div className="form-field">
            <span>{shape.resource === 'optional' ? t('alerts.resourceOptional') : t('alerts.resource')}</span>
            <div className="alert-resource">
              {resource ? <ResourceLabelView resource={resource.label} assetVersion={resource.assetVersion} size={24} /> : null}
              <button type="button" className="button button-small" onClick={() => setPicking(true)}>
                {resource ? t('alerts.change') : t('alerts.choose')}
              </button>
              {resource && shape.resource === 'optional' ? (
                <button type="button" className="button button-quiet button-small" onClick={() => setResource(null)}>
                  {t('alerts.anyResource')}
                </button>
              ) : null}
            </div>
          </div>
        ) : null}
        {shape.threshold !== null ? (
          <label className="form-field">
            <span>{shape.threshold === 'minutes' ? t('alerts.thresholdMinutes')
              : shape.threshold === 'percent' ? (shape.window ? t('alerts.thresholdChange') : t('alerts.thresholdPercent'))
                : unit ? t('alerts.thresholdIn', { unit: unit.symbol }) : t('alerts.threshold')}</span>
            <input className="input" inputMode="decimal" value={thresholdText} onChange={(event) => setThresholdText(event.target.value)} />
          </label>
        ) : null}
        {shape.window ? (
          <label className="form-field">
            <span>{t('alerts.windowLabel')}</span>
            <input className="input" inputMode="numeric" value={windowText} onChange={(event) => setWindowText(event.target.value)} />
          </label>
        ) : null}
        {shape.cooldown ? (
          <label className="form-field">
            <span>{t('alerts.cooldownLabel')}</span>
            <input className="input" inputMode="numeric" value={cooldown} onChange={(event) => setCooldown(event.target.value)} />
          </label>
        ) : null}
      </div>
      <FormError error={mutations.create.error} />
      <div className="order-actions">
        <button type="submit" className="button button-primary" disabled={!valid || full || mutations.create.isPending}>
          {t('alerts.add')}
        </button>
        <button type="button" className="button button-quiet" disabled={full || mutations.create.isPending}
          onClick={() => create({ type: 'RESOURCE_BELOW', resourceId: BLANK_PATTERN, threshold: 32, windowMinutes: null,
            cooldownMinutes: 30 })}>
          {t('alerts.blankPatternPreset')}
        </button>
        {full ? <span className="muted">{t('alerts.full')}</span> : null}
      </div>
      {picking ? (
        <ResourcePicker networkId={networkId} itemsOnly={false} title={t('alerts.choose')}
          onPick={(label, assetVersion) => {
            setResource({ label, assetVersion });
            setPicking(false);
          }}
          onClose={() => setPicking(false)} />
      ) : null}
    </form>
  );
}

export function AlertEventRow({ event, assetVersion }: { event: AlertEvent; assetVersion: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const message = eventMessage(event, (raw) => exactAmount({ amount: raw, unit: event.resource?.unit ?? null }, locale));
  return (
    <li className="list-row">
      <div className="list-main">
        <div className="list-title">
          <Badge tone={event.kind === 'RESOLVED' || event.type === 'CRAFT_COMPLETED' ? 'success' : 'danger'}>
            {t(`alerts.kind.${event.kind}`)}
          </Badge>{' '}
          {t(message.key, message.values)}
        </div>
        <div className="list-meta">
          {event.resource ? <ResourceLabelView resource={event.resource} assetVersion={assetVersion} size={20} /> : null}
          {event.networkName ? <span>{event.networkName}</span> : null}
          <time dateTime={event.at} title={new Date(event.at).toLocaleString(locale.replace('_', '-'))}>
            {formatRelative(event.at, locale)}
          </time>
        </div>
      </div>
    </li>
  );
}

function Events({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const events = useAlertEvents(networkId, i18n.language);
  const list = events.data?.pages.flatMap((page) => page.events.map((event) => ({ event, assetVersion: page.assetVersion }))) ?? [];

  return (
    <Card className="card-wide" title={t('alerts.recent')}>
      {events.isPending ? (
        <LoadingNotice />
      ) : events.isError ? (
        <ErrorNotice title={t('alerts.recent')} error={events.error} onRetry={() => void events.refetch()} />
      ) : list.length === 0 ? (
        <p className="muted-text">{t('alerts.noEvents')}</p>
      ) : (
        <ul className="list list-compact">
          {list.map(({ event, assetVersion }) => <AlertEventRow key={event.id} event={event} assetVersion={assetVersion} />)}
        </ul>
      )}
      {events.hasNextPage ? (
        <div className="load-more">
          <button type="button" className="button" onClick={() => void events.fetchNextPage()} disabled={events.isFetchingNextPage}>
            {events.isFetchingNextPage ? t('common.loading') : t('crafting.loadMore')}
          </button>
        </div>
      ) : null}
    </Card>
  );
}

function Channels() {
  const { t, i18n } = useTranslation();
  const settings = useAlertSettings();
  const mutations = useAlertSettingsMutations(i18n.language);
  const browser = useAlertPrefs();
  const [discord, setDiscord] = useState<string | null>(null);
  const [webhook, setWebhook] = useState<string | null>(null);
  const supported = typeof Notification !== 'undefined';
  const permission = supported ? Notification.permission : 'denied';

  const toggleBrowser = async () => {
    if (browser.browserNotifications) {
      browser.setBrowserNotifications(false);
      return;
    }
    const granted = permission === 'granted' || (await Notification.requestPermission()) === 'granted';
    browser.setBrowserNotifications(granted);
  };

  return (
    <Card className="card-wide" title={t('alerts.channels')}>
      <p className="muted-text">{t('alerts.channelsHint')}</p>
      <div className="order-actions">
        <button type="button" className="button" disabled={!supported || permission === 'denied'} onClick={() => void toggleBrowser()}>
          {browser.browserNotifications ? t('alerts.browserOff') : t('alerts.browserOn')}
        </button>
        <span className="muted">
          {!supported ? t('alerts.browserUnsupported') : permission === 'denied' ? t('alerts.browserDenied')
            : browser.browserNotifications ? t('alerts.browserEnabled') : null}
        </span>
      </div>
      {settings.isPending ? (
        <LoadingNotice />
      ) : settings.isError ? (
        <ErrorNotice title={t('alerts.channels')} error={settings.error} onRetry={() => void settings.refetch()} />
      ) : !settings.data.webhooksEnabled ? (
        <p className="muted-text">{t('alerts.webhooksOff')}</p>
      ) : (
        <form className="alert-form" onSubmit={(event) => {
          event.preventDefault();
          mutations.save.mutate({ discord: discord ?? settings.data.discordWebhookUrl ?? '', webhook: webhook ?? settings.data.webhookUrl ?? '' });
        }}>
          <label className="form-field">
            <span>{t('alerts.discord')}</span>
            <input className="input" type="url" placeholder="https://discord.com/api/webhooks/…" maxLength={512}
              value={discord ?? settings.data.discordWebhookUrl ?? ''} onChange={(event) => setDiscord(event.target.value)} />
          </label>
          <label className="form-field">
            <span>{t('alerts.webhook')}</span>
            <input className="input" type="url" placeholder="https://…" maxLength={512}
              value={webhook ?? settings.data.webhookUrl ?? ''} onChange={(event) => setWebhook(event.target.value)} />
          </label>
          <p className="form-hint">{t('alerts.webhookHint')}</p>
          <FormError error={mutations.save.error ?? mutations.test.error} />
          <div className="order-actions">
            <button type="submit" className="button button-primary" disabled={mutations.save.isPending}>
              {mutations.save.isPending ? t('common.saving') : t('common.save')}
            </button>
            <button type="button" className="button" disabled={mutations.test.isPending
              || (!settings.data.discordWebhookUrl && !settings.data.webhookUrl)} onClick={() => mutations.test.mutate()}>
              {mutations.test.isPending ? t('alerts.testing') : t('alerts.test')}
            </button>
            {mutations.save.isSuccess ? <span className="muted">{t('alerts.saved')}</span> : null}
          </div>
          {mutations.test.data ? (
            <ul className="list list-compact">
              {Object.entries(mutations.test.data).map(([channel, result]) => (
                <li key={channel} className="list-row">
                  <span className="list-title">{t(`alerts.channel.${channel}`, { defaultValue: channel })}</span>
                  <Badge tone={result === 'OK' ? 'success' : 'danger'}>{result === 'OK' ? t('alerts.delivered') : result}</Badge>
                </li>
              ))}
            </ul>
          ) : null}
        </form>
      )}
    </Card>
  );
}
