import { useTranslation } from 'react-i18next';
import { useEffect, useRef, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { useLogout, useMe } from '../api/queries';
import { useAlertNotifications } from '../hooks/useAlertNotifications';
import { useUpdateAvailable } from '../hooks/useUpdateAvailable';
import { ConnectionIndicator } from './ConnectionIndicator';
import { LocaleSelect } from './LocaleSelect';
import { PageErrorBoundary } from './PageErrorBoundary';

export function Shell() {
  const { t } = useTranslation();
  const me = useMe();
  const logout = useLogout();
  const name = me.data?.user.playerName ?? '';
  const updateAvailable = useUpdateAvailable();
  useAlertNotifications();

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
        {NAV.map((item) => (
          <NavItem key={item.to} item={item} className={item.primary ? 'nav-item' : 'nav-item nav-secondary'} />
        ))}
        <MoreMenu />
      </nav>

      <main className="content">
        {updateAvailable ? (
          <div className="notice update-notice" role="status">
            <div>
              <strong>{t('update.title')}</strong>
              <p>{t('update.body')}</p>
            </div>
            <button type="button" className="button button-primary" onClick={() => window.location.reload()}>
              {t('update.reload')}
            </button>
          </div>
        ) : null}
        <PageErrorBoundary>
          <Outlet />
        </PageErrorBoundary>
      </main>
    </div>
  );
}

interface NavEntry {
  to: string;
  label: string;
  icon: ReactNode;
  /** Shown in the phone's bottom bar; the others move into "More" there (spec section 5). */
  primary: boolean;
}

const NAV: NavEntry[] = [
  { to: '/', label: 'nav.overview', icon: <OverviewIcon />, primary: true },
  { to: '/terminal', label: 'nav.terminal', icon: <TerminalIcon />, primary: true },
  { to: '/crafting', label: 'nav.crafting', icon: <CraftingIcon />, primary: true },
  { to: '/cpus', label: 'nav.cpus', icon: <CpuIcon />, primary: false },
  { to: '/machines', label: 'nav.machines', icon: <MachinesIcon />, primary: false },
  { to: '/patterns', label: 'nav.patterns', icon: <PatternIcon />, primary: false },
  { to: '/insights', label: 'nav.insights', icon: <InsightsIcon />, primary: true },
  { to: '/alerts', label: 'nav.alerts', icon: <AlertsIcon />, primary: false },
  { to: '/automation', label: 'nav.automation', icon: <AutomationIcon />, primary: false },
  { to: '/explorer', label: 'nav.explorer', icon: <ExplorerIcon />, primary: false },
  { to: '/settings', label: 'nav.settings', icon: <SettingsIcon />, primary: false },
];

function NavItem({ item, className }: { item: NavEntry; className: string }) {
  const { t } = useTranslation();
  return (
    <NavLink to={item.to} end={item.to === '/'} className={className}>
      {item.icon}
      <span>{t(item.label)}</span>
    </NavLink>
  );
}

/** Phones only: the secondary sections, closed again by navigating or tapping elsewhere. */
function MoreMenu() {
  const { t } = useTranslation();
  const menu = useRef<HTMLDetailsElement>(null);
  const { pathname } = useLocation();
  const secondary = NAV.filter((item) => !item.primary);
  const active = secondary.some((item) => pathname.startsWith(item.to));

  useEffect(() => {
    if (menu.current) menu.current.open = false;
  }, [pathname]);
  useEffect(() => {
    const close = (event: PointerEvent) => {
      if (menu.current && !menu.current.contains(event.target as Node)) menu.current.open = false;
    };
    document.addEventListener('pointerdown', close);
    return () => document.removeEventListener('pointerdown', close);
  }, []);

  return (
    <details className="nav-more" ref={menu}>
      <summary className={active ? 'nav-item active' : 'nav-item'}>
        <MoreIcon />
        <span>{t('nav.more')}</span>
      </summary>
      <div className="nav-more-menu">
        {secondary.map((item) => (
          <NavItem key={item.to} item={item} className="nav-item" />
        ))}
      </div>
    </details>
  );
}

function MoreIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <circle cx="5" cy="12" r="1.8" fill="currentColor" />
      <circle cx="12" cy="12" r="1.8" fill="currentColor" />
      <circle cx="19" cy="12" r="1.8" fill="currentColor" />
    </svg>
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

function PatternIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M6 3h9l3 3v15H6zM15 3v3h3M9 10h2v2H9zM13 10h2v2h-2zM9 14h2v2H9zM13 14h2v2h-2z"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function MachinesIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M4 20V9l5 3V9l5 3V6l6 3v11H4zM8 16h2M13 16h2"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function AlertsIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15L6 16zM10 20.5a2 2 0 0 0 4 0"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function InsightsIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M4 4v16h16M7 15l4-5 3 3 5-7"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function AutomationIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M4 12a8 8 0 0 1 13.7-5.7M20 12a8 8 0 0 1-13.7 5.7M17 3v4h-4M7 21v-4h4"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function ExplorerIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
      <path
        d="M12 7V4M12 20v-3M12 9.5 5 13M12 9.5 19 13"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
      <circle cx="12" cy="8.5" r="2" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="4" cy="14.5" r="2" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="20" cy="14.5" r="2" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <circle cx="12" cy="19" r="2" fill="none" stroke="currentColor" strokeWidth="1.6" />
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
