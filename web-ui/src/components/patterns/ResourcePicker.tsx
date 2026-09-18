import { useQuery } from '@tanstack/react-query';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResourceLabel } from '../../api/crafting';
import { fetchCatalog } from '../../api/patterns';
import { fetchResourcePage, type TypeFilter } from '../../api/resources';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { tileAmount } from '../../lib/amount';
import { ErrorNotice, LoadingNotice } from '../StateNotice';
import { ResourceIcon } from '../terminal/ResourceIcon';
import { ResourceName } from '../terminal/ResourceName';

const PAGE = 60;

type Source = 'network' | 'all';

/**
 * Chooses a resource for a pattern slot: from the network's storage (including craftables and addon resource
 * types), or from every registered item and fluid for resources the network has never held.
 */
export function ResourcePicker({
  networkId,
  itemsOnly,
  title,
  onPick,
  onClose,
}: {
  networkId: string | undefined;
  itemsOnly: boolean;
  title: string;
  onPick: (resource: ResourceLabel, assetVersion: string) => void;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const titleId = useId();
  const [source, setSource] = useState<Source>(networkId ? 'network' : 'all');
  const [search, setSearch] = useState('');
  const [type, setType] = useState<TypeFilter>(itemsOnly ? 'ITEM' : 'ALL');
  const debounced = useDebouncedValue(search.trim());

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const results = useQuery({
    queryKey: ['patterns', 'picker', source, networkId, debounced, type, locale],
    queryFn: ({ signal }) =>
      source === 'network' && networkId
        ? fetchResourcePage({ networkId, search: debounced, sort: 'NAME', descending: false, type, locale }, 0, PAGE,
          undefined, signal)
        : fetchCatalog({ search: debounced, type, sort: 'NAME', locale }, 0, PAGE, signal),
    placeholderData: (previous) => previous,
  });

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="dialog picker" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="dialog-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label={t('common.close')}>
            ✕
          </button>
        </header>
        <div className="dialog-body">
          <div className="tabs" role="tablist">
            {networkId ? (
              <button type="button" role="tab" aria-selected={source === 'network'}
                className={`tab${source === 'network' ? ' tab-active' : ''}`} onClick={() => setSource('network')}>
                {t('patterns.picker.network')}
              </button>
            ) : null}
            <button type="button" role="tab" aria-selected={source === 'all'}
              className={`tab${source === 'all' ? ' tab-active' : ''}`} onClick={() => setSource('all')}>
              {t('patterns.picker.all')}
            </button>
          </div>
          <div className="picker-toolbar">
            <input
              className="input"
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t('terminal.searchPlaceholder')}
              aria-label={t('terminal.search')}
              autoFocus
            />
            {itemsOnly ? null : (
              <select className="input input-select" value={type} onChange={(event) => setType(event.target.value as TypeFilter)}
                aria-label={t('terminal.type')}>
                {(['ALL', 'ITEM', 'FLUID', 'OTHER'] as const).map((value) => (
                  <option key={value} value={value}>{t(`terminal.types.${value}`)}</option>
                ))}
              </select>
            )}
          </div>
          {source === 'all' ? <p className="form-hint">{t('patterns.picker.allHint')}</p> : null}

          {results.isPending ? (
            <LoadingNotice />
          ) : results.isError ? (
            <ErrorNotice title={t('patterns.picker.error')} error={results.error} onRetry={() => void results.refetch()} />
          ) : results.data.entries.length === 0 ? (
            <p className="muted">{t('terminal.noMatches')}</p>
          ) : (
            <>
              <ul className="picker-list">
                {results.data.entries.map((resource) => (
                  <li key={resource.id}>
                    <button type="button" className="picker-item" title={resource.id}
                      onClick={() => onPick(resource, results.data.assetVersion)}>
                      <ResourceIcon iconKey={resource.iconKey} assetVersion={results.data.assetVersion} size={28} />
                      <span className="picker-name"><ResourceName resource={resource} /></span>
                      <span className="picker-meta">
                        {source === 'network' && resource.amount > 0 ? tileAmount(resource) : resource.modName}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
              {results.data.total > results.data.entries.length ? (
                <p className="footnote">{t('patterns.picker.more', { shown: results.data.entries.length, total: results.data.total })}</p>
              ) : null}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
