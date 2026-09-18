import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import enUs from './locales/en_us.json';
import zhCn from './locales/zh_cn.json';

export const SUPPORTED_LOCALES = ['en_us', 'zh_cn'] as const;
export type Locale = (typeof SUPPORTED_LOCALES)[number];

const STORAGE_KEY = 'mecc.locale';

function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

/** Chinese unless the viewer has chosen otherwise; English stays one click away in the settings. */
export const DEFAULT_LOCALE: Locale = 'zh_cn';

function initialLocale(): Locale {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (isLocale(stored)) return stored;
  } catch {
    // Storage may be unavailable (private mode); fall through to the default.
  }
  return DEFAULT_LOCALE;
}

export function setLocale(locale: Locale): void {
  void i18n.changeLanguage(locale);
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // Non-persistent locale is acceptable.
  }
}

function syncDocumentLanguage(locale: string): void {
  document.documentElement.lang = locale === 'zh_cn' ? 'zh-CN' : 'en';
}

void i18n.use(initReactI18next).init({
  resources: {
    en_us: { translation: enUs },
    zh_cn: { translation: zhCn },
  },
  lng: initialLocale(),
  fallbackLng: 'en_us',
  supportedLngs: [...SUPPORTED_LOCALES],
  load: 'currentOnly',
  interpolation: { escapeValue: false },
});

syncDocumentLanguage(i18n.language);
i18n.on('languageChanged', syncDocumentLanguage);

export default i18n;
