import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { failureKind } from '../api/client';
import { useMe } from '../api/queries';
import { PairPage } from '../pages/PairPage';
import { ErrorNotice, LoadingNotice } from './StateNotice';

/** Shows the pairing flow until this browser holds a valid device token. */
export function AuthGate({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const me = useMe();

  if (me.isPending) {
    return (
      <div className="gate">
        <LoadingNotice />
      </div>
    );
  }
  if (me.isError) {
    if (failureKind(me.error) === 'unauthenticated') {
      return <PairPage />;
    }
    // A failed background refresh (e.g. the server restarting) must not tear down the open page and its dialogs.
    if (me.data) {
      return <>{children}</>;
    }
    return (
      <div className="gate">
        <ErrorNotice
          title={t('connection.offline')}
          error={me.error}
          onRetry={() => void me.refetch()}
          retrying={me.isFetching}
        />
      </div>
    );
  }
  return <>{children}</>;
}
