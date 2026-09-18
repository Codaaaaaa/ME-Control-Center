import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import type { Device } from '../api/auth';
import {
  useDevices,
  useLogout,
  useNetworks,
  useRenameDevice,
  useRevokeDevice,
  useRevokeOtherDevices,
} from '../api/queries';
import { Badge, Card } from '../components/Card';
import { ConfirmButton } from '../components/ConfirmButton';
import { LocaleSelect } from '../components/LocaleSelect';
import { ClaimNetworks } from '../components/network/ClaimNetworks';
import { STATE_TONE } from '../components/network/NetworkStatusCard';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { formatRelative } from '../lib/format';

const TABS = ['devices', 'networks', 'preferences'] as const;
type Tab = (typeof TABS)[number];

export function SettingsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab: Tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'devices';

  return (
    <>
      <div className="page-header">
        <h1>{t('settings.title')}</h1>
        <p>{t('settings.subtitle')}</p>
      </div>
      <div className="tabs" role="tablist">
        {TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            className={`tab${tab === name ? ' tab-active' : ''}`}
            onClick={() => setParams({ tab: name }, { replace: true })}
          >
            {t(`settings.tabs.${name}`)}
          </button>
        ))}
      </div>
      <div className="grid">
        {tab === 'devices' ? <DevicesSection /> : null}
        {tab === 'networks' ? <NetworksSection /> : null}
        {tab === 'preferences' ? <PreferencesSection /> : null}
      </div>
    </>
  );
}

function DevicesSection() {
  const { t } = useTranslation();
  const devices = useDevices();
  const revokeOthers = useRevokeOtherDevices();

  return (
    <Card className="card-wide" title={t('devices.title')}>
      <p className="muted-text">{t('devices.subtitle')}</p>
      {devices.isPending ? (
        <LoadingNotice />
      ) : devices.isError ? (
        <ErrorNotice title={t('devices.title')} error={devices.error} onRetry={() => void devices.refetch()} />
      ) : (
        <>
          <ul className="list">
            {devices.data.map((device) => (
              <DeviceRow key={device.id} device={device} />
            ))}
          </ul>
          {devices.data.length > 1 ? (
            <footer className="card-footer">
              <ConfirmButton label={t('devices.revokeOthers')} onConfirm={() => revokeOthers.mutate()} disabled={revokeOthers.isPending} />
              {revokeOthers.isSuccess ? (
                <span className="muted">{t('devices.revokedCount', { count: revokeOthers.data })}</span>
              ) : null}
              <FormError error={revokeOthers.error} />
            </footer>
          ) : null}
        </>
      )}
    </Card>
  );
}

function DeviceRow({ device }: { device: Device }) {
  const { t, i18n } = useTranslation();
  const rename = useRenameDevice();
  const revoke = useRevokeDevice();
  const logout = useLogout();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(device.name);

  return (
    <li className="list-row">
      <div className="list-main">
        {editing ? (
          <form
            className="inline-form"
            onSubmit={(event) => {
              event.preventDefault();
              rename.mutate({ id: device.id, name }, { onSuccess: () => setEditing(false) });
            }}
          >
            <input className="input" value={name} maxLength={48} onChange={(event) => setName(event.target.value)} autoFocus />
            <button type="submit" className="button button-primary button-small" disabled={!name.trim() || rename.isPending}>
              {rename.isPending ? t('common.saving') : t('common.save')}
            </button>
            <button type="button" className="button button-quiet button-small" onClick={() => setEditing(false)}>
              {t('common.cancel')}
            </button>
          </form>
        ) : (
          <div className="list-title">
            {device.name} {device.current ? <Badge tone="accent">{t('devices.current')}</Badge> : null}
          </div>
        )}
        <div className="list-meta">
          <code>{device.id}</code>
          <span>{t('devices.lastUsed', { time: formatRelative(device.lastUsedAt, i18n.language) })}</span>
          <span>{t('devices.paired', { time: formatRelative(device.createdAt, i18n.language) })}</span>
          {device.lastAddress ? <span>{device.lastAddress}</span> : null}
        </div>
        <FormError error={rename.error ?? revoke.error} />
      </div>
      {!editing ? (
        <div className="list-actions">
          <button type="button" className="button button-quiet button-small" onClick={() => setEditing(true)}>
            {t('devices.rename')}
          </button>
          {device.current ? (
            <button type="button" className="button button-quiet button-small" onClick={() => logout.mutate()} disabled={logout.isPending}>
              {t('devices.signOutHere')}
            </button>
          ) : (
            <ConfirmButton label={t('devices.revoke')} onConfirm={() => revoke.mutate(device.id)} disabled={revoke.isPending} />
          )}
        </div>
      ) : null}
    </li>
  );
}

function NetworksSection() {
  const { t } = useTranslation();
  const networks = useNetworks();

  return (
    <>
      <Card className="card-wide" title={t('networks.title')}>
        <p className="muted-text">{t('networks.subtitle')}</p>
        {networks.isPending ? (
          <LoadingNotice />
        ) : networks.isError && !networks.data ? (
          <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
        ) : networks.data.length === 0 ? (
          <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
        ) : (
          <ul className="list">
            {networks.data.map((network) => (
              <li key={network.id} className="list-row">
                <div className="list-main">
                  <div className="list-title">
                    {network.displayName} <Badge tone={STATE_TONE[network.state]}>{t(`network.state.${network.state}`)}</Badge>
                  </div>
                  <div className="list-meta">
                    <span>{t(`roles.${network.role}`)}</span>
                    {network.adminOverride ? <Badge tone="accent">{t('network.adminOverride')}</Badge> : null}
                    <span>
                      {t('network.owner')}: {network.owner.playerName ?? t('common.unknownPlayer')}
                    </span>
                  </div>
                </div>
                <div className="list-actions">
                  <Link to={`/networks/${network.id}`} className="button button-quiet button-small">
                    {t('networks.open')}
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <ClaimNetworks />
    </>
  );
}

function PreferencesSection() {
  const { t } = useTranslation();
  return (
    <Card title={t('preferences.language')}>
      <p className="muted-text">{t('preferences.languageHint')}</p>
      <LocaleSelect />
    </Card>
  );
}
