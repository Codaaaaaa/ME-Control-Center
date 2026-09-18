import { useTranslation } from 'react-i18next';
import { NavLink, Outlet } from 'react-router';
import { useLogout, useMe } from '../api/queries';
import { ConnectionIndicator } from './ConnectionIndicator';
import { LocaleSelect } from './LocaleSelect';

export function Shell() {
  const { t } = useTranslation();
  const me = useMe();
  const logout = useLogout();
  const name = me.data?.user.playerName ?? '';

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <img className="brand-mark" src="/favicon.svg" alt="" width={28} height={28} />
          <div>
            <div className="brand-name">{t('app.name')}</div>
            <div className="brand-tagline">{t('app.tagline')}</div>
          </div>
        </div>
        <div className="topbar-actions">
          <ConnectionIndicator />
          <LocaleSelect />
          {me.data ? (
            <div className="account" title={t('account.signedInAs', { name })}>
              <span className="avatar" aria-hidden="true">
                {name.slice(0, 1).toUpperCase()}
              </span>
              <span className="account-name">{name}</span>
              <button
                type="button"
                className="button button-quiet button-small"
                onClick={() => logout.mutate()}
                disabled={logout.isPending}
              >
                {t('account.logout')}
              </button>
            </div>
          ) : null}
        </div>
      </header>

      <nav className="nav" aria-label={t('nav.label')}>
        <NavLink to="/" end className="nav-item">
          <OverviewIcon />
          <span>{t('nav.overview')}</span>
        </NavLink>
        <NavLink to="/terminal" className="nav-item">
          <TerminalIcon />
          <span>{t('nav.terminal')}</span>
        </NavLink>
        <NavLink to="/crafting" className="nav-item">
          <CraftingIcon />
          <span>{t('nav.crafting')}</span>
        </NavLink>
        <NavLink to="/cpus" className="nav-item">
          <CpuIcon />
          <span>{t('nav.cpus')}</span>
        </NavLink>
        <NavLink to="/settings" className="nav-item">
          <SettingsIcon />
          <span>{t('nav.settings')}</span>
        </NavLink>
      </nav>

      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

function OverviewIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function TerminalIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <rect x="3" y="4" width="7" height="7" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <rect x="14" y="4" width="7" height="7" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <rect x="3" y="14" width="7" height="7" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <rect x="14" y="14" width="7" height="7" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  );
}

function CraftingIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M4 4h4v4H4zM10 4h4v4h-4zM4 10h4v4H4zM10 10h4v4h-4zM17 12h3M18.5 10.5 20 12l-1.5 1.5M4 17h10v3H4z"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}

function CpuIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <rect x="6" y="6" width="12" height="12" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <rect x="9.5" y="9.5" width="5" height="5" fill="none" stroke="currentColor" strokeWidth="1.4" />
      <path d="M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

function SettingsIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M4 7h10M18 7h2M4 17h2M10 17h10M14 5v4M6 15v4"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
      <circle cx="16" cy="7" r="2" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="8" cy="17" r="2" fill="none" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  );
}
