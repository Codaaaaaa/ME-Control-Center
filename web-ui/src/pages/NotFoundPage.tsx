import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <div className="page-header">
      <h1>{t('notFound.title')}</h1>
      <p>
        <Link to="/">{t('notFound.back')}</Link>
      </p>
    </div>
  );
}
