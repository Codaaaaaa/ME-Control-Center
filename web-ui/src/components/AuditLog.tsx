import { useTranslation } from 'react-i18next';
import type { AuditEntry } from '../api/admin';
import { useAuditLog } from '../api/queries';
import { Badge } from './Card';
import { EmptyNotice, ErrorNotice, LoadingNotice } from './StateNotice';
import { formatDateTime, formatRelative } from '../lib/format';

/** Audit log of one network, or of the whole server when `networkId` is null (spec section 35). */
export function AuditLog({ networkId }: { networkId: string | null }) {
  const { t } = useTranslation();
  const log = useAuditLog(networkId);

  if (log.isPending) return <LoadingNotice />;
  if (log.isError && !log.data) {
    return <ErrorNotice title={t('audit.title')} error={log.error} onRetry={() => void log.refetch()} />;
  }
  const entries = log.data.pages.flatMap((page) => page.entries);
  if (entries.length === 0) return <EmptyNotice title={t('audit.empty')} />;

  return (
    <>
      <ul className="list list-compact">
        {entries.map((entry) => (
          <AuditRow key={entry.id} entry={entry} showNetwork={networkId === null} />
        ))}
      </ul>
      {log.hasNextPage ? (
        <footer className="card-footer">
          <button type="button" className="button" onClick={() => void log.fetchNextPage()} disabled={log.isFetchingNextPage}>
            {log.isFetchingNextPage ? t('common.loading') : t('audit.more')}
          </button>
        </footer>
      ) : null}
    </>
  );
}

function AuditRow({ entry, showNetwork }: { entry: AuditEntry; showNetwork: boolean }) {
  const { t, i18n } = useTranslation();
  const actor = entry.actor ? (entry.actor.playerName ?? entry.actor.playerUuid) : t('audit.console');
  const target = entry.targetPlayer?.playerName ?? entry.target;
  const parameters = Object.entries(entry.parameters);

  return (
    <li className="list-row">
      <div className="list-main">
        <div className="list-title">
          {i18n.exists(`audit.actions.${entry.action}`) ? t(`audit.actions.${entry.action}`) : entry.action}{' '}
          {entry.result !== 'SUCCESS' ? (
            <Badge tone={entry.result === 'FAILED' ? 'danger' : 'warning'}>{t(`audit.results.${entry.result}`)}</Badge>
          ) : null}
          {entry.adminOverride ? <Badge tone="accent">{t('network.adminOverride')}</Badge> : null}
        </div>
        <div className="list-meta">
          <time dateTime={entry.at} title={formatDateTime(entry.at, i18n.language)}>
            {formatRelative(entry.at, i18n.language)}
          </time>
          <span>{t('audit.by', { name: actor })}</span>
          {target ? <span>{t('audit.target', { target })}</span> : null}
          {showNetwork && entry.networkId ? <span>{entry.networkName ?? t('audit.deletedNetwork')}</span> : null}
          {parameters.length > 0 ? (
            <code className="audit-parameters">{parameters.map(([key, value]) => `${key}=${value}`).join(' ')}</code>
          ) : null}
        </div>
      </div>
    </li>
  );
}
