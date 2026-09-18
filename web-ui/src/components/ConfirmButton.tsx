import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

/** Two-step destructive button: the first click arms it, the second confirms. Disarms after a few seconds. */
export function ConfirmButton({
  label,
  onConfirm,
  disabled,
  variant = 'danger',
}: {
  label: string;
  onConfirm: () => void;
  disabled?: boolean;
  variant?: 'danger' | 'quiet';
}) {
  const { t } = useTranslation();
  const [armed, setArmed] = useState(false);

  useEffect(() => {
    if (!armed) return;
    const timer = window.setTimeout(() => setArmed(false), 4000);
    return () => window.clearTimeout(timer);
  }, [armed]);

  return (
    <button
      type="button"
      className={`button button-${variant}${armed ? ' button-armed' : ''}`}
      disabled={disabled}
      onClick={() => {
        if (armed) {
          setArmed(false);
          onConfirm();
        } else {
          setArmed(true);
        }
      }}
    >
      {armed ? `${t('common.confirm')}: ${label}` : label}
    </button>
  );
}
