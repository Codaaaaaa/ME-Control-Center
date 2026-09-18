import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { failureKind, isApiError } from '../api/client';
import { usePair } from '../api/queries';
import { LocaleSelect } from '../components/LocaleSelect';
import { FormError } from '../components/StateNotice';
import { formatPairingKeyInput, isCompletePairingKey } from '../lib/pairingKey';

export function PairPage() {
  const { t } = useTranslation();
  const pair = usePair();
  const [key, setKey] = useState('');
  const [deviceName, setDeviceName] = useState('');
  const keyId = useId();
  const nameId = useId();

  const invalidKey = isApiError(pair.error, 'PAIRING_KEY_INVALID') || isApiError(pair.error, 'VALIDATION_FAILED');

  return (
    <div className="pair-screen">
      <div className="pair-card">
        <header className="pair-brand">
          <img src="/favicon.svg" alt="" width={36} height={36} />
          <div>
            <div className="brand-name">{t('app.name')}</div>
            <div className="brand-tagline">{t('app.tagline')}</div>
          </div>
          <LocaleSelect />
        </header>

        <h1>{t('pair.title')}</h1>
        <p className="muted-text">{t('pair.subtitle')}</p>

        <ol className="pair-steps">
          <li>
            {t('pair.step1')} <code className="command">/mecc pair</code>
          </li>
          <li>{t('pair.step2')}</li>
        </ol>

        <form
          className="form"
          onSubmit={(event) => {
            event.preventDefault();
            if (isCompletePairingKey(key) && !pair.isPending) {
              pair.mutate({ key, deviceName: deviceName.trim() });
            }
          }}
        >
          <label className="form-field" htmlFor={keyId}>
            <span>{t('pair.keyLabel')}</span>
            <input
              id={keyId}
              className="input input-key"
              value={key}
              onChange={(event) => setKey(formatPairingKeyInput(event.target.value))}
              placeholder="XXXX-XXXX-XXXX"
              autoComplete="one-time-code"
              autoCapitalize="characters"
              spellCheck={false}
              inputMode="text"
              aria-invalid={invalidKey}
              autoFocus
            />
          </label>
          <label className="form-field" htmlFor={nameId}>
            <span>{t('pair.deviceNameLabel')}</span>
            <input
              id={nameId}
              className="input"
              value={deviceName}
              maxLength={48}
              onChange={(event) => setDeviceName(event.target.value)}
              placeholder={t('pair.deviceNamePlaceholder')}
            />
          </label>

          {pair.isError ? (
            invalidKey ? (
              <p className="form-error" role="alert">
                {t('pair.invalid')}
              </p>
            ) : failureKind(pair.error) === 'unauthenticated' ? null : (
              <FormError error={pair.error} />
            )
          ) : null}

          <button type="submit" className="button button-primary" disabled={!isCompletePairingKey(key) || pair.isPending}>
            {pair.isPending ? t('pair.submitting') : t('pair.submit')}
          </button>
        </form>

        <p className="footnote">{t('pair.footnote')}</p>
      </div>
    </div>
  );
}
