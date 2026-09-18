import { useTranslation } from 'react-i18next';
import { SUPPORTED_LOCALES, setLocale, type Locale } from '../i18n';

export function LocaleSelect() {
  const { t, i18n } = useTranslation();
  return (
    <label className="locale-select">
      <span className="visually-hidden">{t('language.label')}</span>
      <select value={i18n.language} onChange={(event) => setLocale(event.target.value as Locale)}>
        {SUPPORTED_LOCALES.map((locale) => (
          <option key={locale} value={locale}>
            {t(`language.${locale}`)}
          </option>
        ))}
      </select>
    </label>
  );
}
