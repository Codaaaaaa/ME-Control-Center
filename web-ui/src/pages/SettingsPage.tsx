import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import type { Device } from '../api/auth';
import {
  useAdminOverview,
  useBackup,
  useDevices,
  useMe,
  useLogout,
  useNetworks,
  useRenameDevice,
  useRevokeDevice,
  useRevokeOtherDevices,
} from '../api/queries';
import { AuditLog } from '../components/AuditLog';
import { Badge, Card, Field, Fields } from '../components/Card';
import { ConfirmButton } from '../components/ConfirmButton';
import { LocaleSelect } from '../components/LocaleSelect';
import { ClaimNetworks } from '../components/network/ClaimNetworks';
import { STATE_TONE } from '../components/network/NetworkStatusCard';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { formatBytes, formatRelative } from '../lib/format';

const TABS = ['devices', 'networks', 'preferences', 'server'] as const;
type Tab = (typeof TABS)[number];

export function SettingsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const me = useMe();
  // The server tab is for server admins only; the API enforces this as well.
  const tabs = TABS.filter((name) => name !== 'server' || me.data?.serverAdmin);
  const tab: Tab = (tabs as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'devices';

  return (
    <>
      <div className="page-header">
        <h1>{t('settings.title')}</h1>
        <p>{t('settings.subtitle')}</p>
      </div>
      <div className="tabs" role="tablist">
        {tabs.map((name) => (
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
        {tab === 'server' ? <ServerSection /> : null}
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

function ServerSection() {
  const { t, i18n } = useTranslation();
  const overview = useAdminOverview(true);
  const backup = useBackup();

  if (overview.isPending) return <LoadingNotice />;
  if (overview.isError) {
    return <ErrorNotice title={t('admin.title')} error={overview.error} onRetry={() => void overview.refetch()} />;
  }
  const { platform, database, config } = overview.data;

  return (
    <>
      <Card title={t('admin.server')}>
        <Fields>
          <Field label={t('admin.version')}>{overview.data.meccVersion}</Field>
          <Field label={t('admin.platform')}>
            Minecraft {platform.minecraftVersion} · {platform.loader} {platform.loaderVersion}
          </Field>
        </Fields>
      </Card>
      <Card title={t('admin.database')}>
        <Fields>
          <Field label={t('admin.schemaVersion')}>{database.schemaVersion}</Field>
          <Field label={t('admin.size')}>{formatBytes(database.sizeBytes)}</Field>
        </Fields>
        <p className="muted-text">{t('admin.backupHint')}</p>
        {database.backups.length === 0 ? (
          <p className="muted-text">{t('admin.noBackups')}</p>
        ) : (
          <ul className="list list-compact">
            {database.backups.map((file) => (
              <li key={file.name} className="list-row">
                <div className="list-main">
                  <div className="list-title">
                    <code>{file.name}</code>
                  </div>
                  <div className="list-meta">
                    <span>{formatRelative(file.createdAt, i18n.language)}</span>
                    <span>{formatBytes(file.sizeBytes)}</span>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
        <footer className="card-footer">
          <button type="button" className="button button-primary" onClick={() => backup.mutate()} disabled={backup.isPending}>
            {backup.isPending ? t('admin.backingUp') : t('admin.backUpNow')}
          </button>
          <FormError error={backup.error} />
        </footer>
      </Card>
      <Card className="card-wide" title={t('admin.contentPacks')}>
        {overview.data.contentPacks.length === 0 ? (
          <p className="muted-text">{t('admin.noContentPacks')}</p>
        ) : (
          <ul className="list list-compact">
            {overview.data.contentPacks.map((pack) => (
              <li key={pack.name} className="list-row">
                <div className="list-main">
                  <div className="list-title">
                    <code>{pack.name}</code>{' '}
                    <Badge tone={PACK_TONE[pack.status]}>{t(`admin.packStatus.${pack.status}`)}</Badge>
                  </div>
                  <div className="list-meta">
                    {pack.fingerprint ? <span>{t('admin.packFingerprint', { hash: pack.fingerprint })}</span> : null}
                    {pack.minecraft ? <span>Minecraft {pack.minecraft}</span> : null}
                    {pack.icons !== null ? <span>{t('admin.packIcons', { count: pack.icons })}</span> : null}
                    {pack.locales.length > 0 ? <span>{pack.locales.join(', ')}</span> : null}
                    {pack.createdAt ? <span>{formatRelative(pack.createdAt, i18n.language)}</span> : null}
                  </div>
                  {pack.problems.map((problem) => (
                    <p key={problem} className="form-hint">
                      {problem}
                    </p>
                  ))}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card className="card-wide" title={t('admin.configuration')}>
        <p className="muted-text">{t('admin.configHint', { file: overview.data.configFile })}</p>
        <div className="config-sections">
          {Object.entries(config).map(([section, values]) => (
            <section key={section}>
              <h3 className="config-section-title">[{section}]</h3>
              <Fields>
                {Object.entries(values).map(([key, value]) => (
                  <Field key={key} label={tomlKey(key)}>
                    <code>{Array.isArray(value) ? `[${value.map((item) => JSON.stringify(item)).join(', ')}]` : String(value)}</code>
                  </Field>
                ))}
              </Fields>
            </section>
          ))}
        </div>
      </Card>
      <Card className="card-wide" title={t('audit.serverTitle')}>
        <AuditLog networkId={null} />
      </Card>
    </>
  );
}

const PACK_TONE = { MATCH: 'success', MISMATCH: 'warning', SKIPPED: 'danger' } as const;

/** `maxThreads` -> `max_threads`: configuration fields are named after their mecc.toml keys. */
function tomlKey(key: string): string {
  return key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
}
