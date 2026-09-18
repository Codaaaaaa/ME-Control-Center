import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { alertEventSchema, eventMessage } from '../api/alerts';
import { liveClient } from '../api/live';
import { alertKeys, useNetworks } from '../api/queries';
import { exactAmount } from '../lib/amount';
import { useAlertPrefs } from '../stores/alertPrefs';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

/**
 * Mounted once by the shell: keeps the live connection open on every page (alerts are sent to the player whatever
 * they are looking at), refreshes alert data when one fires, and raises a browser notification if the player
 * turned them on and the browser allows it.
 */
export function useAlertNotifications(): void {
  const { t, i18n } = useTranslation();
  const client = useQueryClient();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const networkId = networks.data ? resolveSelectedNetwork(networks.data, storedId)?.id : undefined;
  const enabled = useAlertPrefs((state) => state.browserNotifications);
  const locale = i18n.language;

  useEffect(() => {
    if (!networkId) return;
    return liveClient.subscribe(networkId, locale);
  }, [networkId, locale]);

  useEffect(() => liveClient.onEvent((event) => {
    if (event.type !== 'alert.triggered' && event.type !== 'alert.resolved') return;
    void client.invalidateQueries({ queryKey: alertKeys.all });
    const parsed = alertEventSchema.safeParse(event.payload);
    if (!parsed.success || !enabled || typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
    const alert = parsed.data;
    const message = eventMessage(alert, (raw) => exactAmount({ amount: raw, unit: alert.resource?.unit ?? null }, locale));
    try {
      new Notification(alert.networkName ?? t('app.name'), { body: t(message.key, message.values), tag: `mecc-alert-${alert.ruleId}` });
    } catch {
      // Some mobile browsers only allow notifications from a service worker; the in-page list still updates.
    }
  }), [client, enabled, locale, t]);
}
