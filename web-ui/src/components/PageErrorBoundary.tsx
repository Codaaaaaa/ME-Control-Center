import { Component, type ErrorInfo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router';

/**
 * Keeps one page's rendering error inside that page (spec section 47): without it React unmounts the whole app and
 * the browser shows an empty, black screen. Navigating elsewhere clears the error.
 */
export function PageErrorBoundary({ children }: { children: ReactNode }) {
  const { pathname, search } = useLocation();
  return <Boundary key={pathname + search}>{children}</Boundary>;
}

class Boundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ME Control Center page failed to render', error, info.componentStack);
  }

  render() {
    return this.state.error ? <PageError error={this.state.error} onRetry={() => this.setState({ error: null })} />
      : this.props.children;
  }
}

function PageError({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="notice notice-danger" role="alert">
      <strong>{t('errors.pageTitle')}</strong>
      <p>{t('errors.pageBody')}</p>
      <code className="audit-parameters">{error.message}</code>
      <div className="order-actions">
        <button type="button" className="button" onClick={onRetry}>{t('common.retry')}</button>
      </div>
    </div>
  );
}
