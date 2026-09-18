import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { providerSearchText, type DefinitionBody, type EncodeResult } from '../../api/patterns';
import { usePatternMutations, useProviders } from '../../api/queries';
import { Badge } from '../Card';
import { ResourceLabelView } from '../crafting/CraftingBits';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../StateNotice';
import { ResourceIcon } from '../terminal/ResourceIcon';

/**
 * Encode & Deploy (spec sections 14-15): choose an online Pattern Provider with a free slot, then encode onto
 * one Blank Pattern and place it there. The server verifies the provider holds the pattern afterwards.
 */
export function DeployDialog({
  networkId,
  definition,
  draftId,
  onClose,
}: {
  networkId: string;
  definition: DefinitionBody;
  draftId: string | null;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const titleId = useId();
  const providers = useProviders(networkId, locale);
  const mutations = usePatternMutations(networkId, locale);
  const [providerId, setProviderId] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [result, setResult] = useState<EncodeResult | null>(null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const all = providers.data?.providers ?? [];
  const needle = filter.trim().toLowerCase();
  const list = needle ? all.filter((provider) => provider.id === providerId || providerSearchText(provider).includes(needle)) : all;
  const usable = (id: string) => {
    const provider = list.find((candidate) => candidate.id === id);
    return provider !== undefined && provider.online && provider.usedSlots < provider.slots;
  };

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="dialog-header">
          <h2 id={titleId}>{t('patterns.deploy.title')}</h2>
          <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label={t('common.close')}>
            ✕
          </button>
        </header>
        <div className="dialog-body">
          {result ? (
            <div className="craft-started" role="status">
              <strong>{t('patterns.deploy.done')}</strong>
              <p>{t('patterns.deploy.doneHint', { provider: result.providerName ?? result.providerId, slot: (result.slot ?? 0) + 1 })}</p>
              <div className="dialog-actions">
                <button type="button" className="button button-primary" onClick={onClose}>{t('common.close')}</button>
              </div>
            </div>
          ) : providers.isPending ? (
            <LoadingNotice />
          ) : providers.isError ? (
            <ErrorNotice title={t('patterns.providers.error')} error={providers.error} onRetry={() => void providers.refetch()} />
          ) : all.length === 0 ? (
            <EmptyNotice title={t('patterns.providers.none')}>{t('patterns.providers.noneHint')}</EmptyNotice>
          ) : (
            <>
              <p className="form-hint">
                {t('patterns.deploy.hint')} · {t('patterns.blankPatterns', { count: providers.data.blankPatterns })}
              </p>
              {all.length > 6 ? (
                <input className="input" type="search" value={filter} onChange={(event) => setFilter(event.target.value)}
                  placeholder={t('patterns.providers.filter')} aria-label={t('patterns.providers.filter')} />
              ) : null}
              <fieldset className="provider-choice">
                <legend className="visually-hidden">{t('patterns.deploy.provider')}</legend>
                {list.map((provider) => {
                  const full = provider.usedSlots >= provider.slots;
                  const disabled = !provider.online || full;
                  return (
                    <label key={provider.id} className={`provider-option${disabled ? ' provider-option-disabled' : ''}`}>
                      <input type="radio" name="provider" disabled={disabled} checked={providerId === provider.id}
                        onChange={() => setProviderId(provider.id)} />
                      {provider.icon ? (
                        <ResourceIcon iconKey={provider.icon.iconKey} assetVersion={providers.data.assetVersion} size={28} />
                      ) : null}
                      <span className="provider-option-name">
                        {provider.name ?? t('patterns.providers.unnamed')}
                        {provider.kind || provider.machine ? (
                          <span className="provider-option-meta">
                            {[provider.kind?.name, provider.machine && provider.machine.name !== provider.name
                              ? provider.machine.name : null].filter(Boolean).join(' · ')}
                          </span>
                        ) : null}
                      </span>
                      <span className="muted">{t('patterns.providers.slots', { used: provider.usedSlots, total: provider.slots })}</span>
                      {!provider.online ? <Badge tone="danger">{t('patterns.providers.offline')}</Badge>
                        : full ? <Badge tone="warning">{t('patterns.providers.full')}</Badge> : null}
                    </label>
                  );
                })}
              </fieldset>
              {providers.data.blankPatterns < 1 ? (
                <p className="form-error" role="alert">{t('errors.codes.NO_BLANK_PATTERN')}</p>
              ) : null}
              <FormError error={mutations.deploy.error} />
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button-primary"
                  disabled={providerId === null || !usable(providerId) || mutations.deploy.isPending
                    || providers.data.blankPatterns < 1}
                  onClick={() => providerId && mutations.deploy.mutate({ definition, draftId, providerId },
                    { onSuccess: setResult })}
                >
                  {mutations.deploy.isPending ? t('patterns.deploy.deploying') : t('patterns.deploy.submit')}
                </button>
                <button type="button" className="button" onClick={onClose}>{t('common.cancel')}</button>
              </div>
            </>
          )}
          {result && result.outputs[0] ? (
            <ResourceLabelView resource={result.outputs[0].resource} assetVersion={result.assetVersion} size={28}
              amount={result.outputs[0].amount} />
          ) : null}
        </div>
      </div>
    </div>
  );
}
