import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, failureKind } from '../api/client';

export function LoadingNotice({ label }: { label?: string }) {
  const { t } = useTranslation();
  return (
    <div className="notice" role="status">
      <span className="spinner" aria-hidden="true" />
      {label ?? t('common.loading')}
    </div>
  );
}

/** Message for a failed request, distinguishing offline, permission, missing, busy, and generic errors. */
export function useErrorMessage() {
  const { t, i18n } = useTranslation();
  return (error: unknown): string => {
    // Errors with a specific explanation (e.g. why a crafting job was refused) say exactly that.
    if (error instanceof ApiError && i18n.exists(`errors.codes.${error.code}`)) {
      return t(`errors.codes.${error.code}`);
    }
    const kind = failureKind(error);
    if (kind === 'error') {
      return t('errors.error', { code: error instanceof ApiError ? error.code : 'UNKNOWN' });
    }
    return t(`errors.${kind}`);
  };
}

export function ErrorNotice({
  title,
  error,
  onRetry,
  retrying,
}: {
  title: string;
  error: unknown;
  onRetry?: () => void;
  retrying?: boolean;
}) {
  const { t } = useTranslation();
  const message = useErrorMessage();
  return (
    <div className="notice notice-danger" role="alert">
      <div>
        <strong>{title}</strong>
        <p>{message(error)}</p>
      </div>
      {onRetry ? (
        <button type="button" className="button" onClick={onRetry} disabled={retrying}>
          {t('common.retry')}
        </button>
      ) : null}
    </div>
  );
}

export function EmptyNotice({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="notice notice-empty">
      <div>
        <strong>{title}</strong>
        {children ? <p>{children}</p> : null}
      </div>
      {action}
    </div>
  );
}

/** Inline error text under a form. */
export function FormError({ error }: { error: unknown }) {
  const message = useErrorMessage();
  if (!error) return null;
  return (
    <p className="form-error" role="alert">
      {error instanceof ApiError && error.kind === 'http' && failureKind(error) === 'error' ? error.message : message(error)}
    </p>
  );
}
