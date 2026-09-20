import { useTranslation } from 'react-i18next';
import { useStatusQuery } from '../hooks/useStatusQuery';
import { formatTime } from '../lib/format';

/** Shows whether the browser can currently reach the ME Control Center server. */
export function ConnectionIndicator() {
  const { t, i18n } = useTranslation();
  const { isPending, isError, dataUpdatedAt } = useStatusQuery();

  const tone = isPending ? 'pending' : isError ? 'danger' : 'success';
  const label = isPending ? t('connection.checking') : isError ? t('connection.offline') : t('connection.online');
  const updated =
    dataUpdatedAt > 0
      ? t('connection.updated', { time: formatTime(dataUpdatedAt, i18n.language) })
      : undefined;

  return (
    <div className={`connection connection-${tone}`} title={updated} role="status">
      <span className="connection-dot" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}
