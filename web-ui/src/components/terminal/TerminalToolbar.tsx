import { useTranslation } from 'react-i18next';
import { SORTS, TYPE_FILTERS, type Sort, type TypeFilter } from '../../api/resources';
import { DENSITIES, type Density } from '../../stores/terminalPrefs';

/** Search, filters, sort, and grid density (spec sections 7.1, 7.3, 7.4, 39). */
export function TerminalToolbar({
  search,
  onSearch,
  sort,
  descending,
  onSort,
  type,
  onType,
  density,
  onDensity,
  total,
}: {
  search: string;
  onSearch: (value: string) => void;
  sort: Sort;
  descending: boolean;
  onSort: (sort: Sort, descending: boolean) => void;
  type: TypeFilter;
  onType: (type: TypeFilter) => void;
  density: Density;
  onDensity: (density: Density) => void;
  total: number;
}) {
  const { t } = useTranslation();

  return (
    <div className="terminal-toolbar">
      <div className="search-field">
        <SearchIcon />
        <input
          className="input"
          type="search"
          value={search}
          placeholder={t('terminal.searchPlaceholder')}
          aria-label={t('terminal.search')}
          autoComplete="off"
          spellCheck={false}
          onChange={(event) => onSearch(event.target.value)}
        />
        {search ? (
          <button type="button" className="search-clear" onClick={() => onSearch('')} aria-label={t('common.cancel')}>
            ✕
          </button>
        ) : null}
      </div>

      <div className="toolbar-controls">
        <div className="segmented" role="group" aria-label={t('terminal.type')}>
          {TYPE_FILTERS.map((option) => (
            <button
              key={option}
              type="button"
              className={`segment${type === option ? ' segment-active' : ''}`}
              aria-pressed={type === option}
              onClick={() => onType(option)}
            >
              {t(`terminal.types.${option}`)}
            </button>
          ))}
        </div>

        <label className="select-field select-inline">
          <span className="visually-hidden">{t('terminal.sort')}</span>
          <select
            className="input input-select"
            value={sort}
            onChange={(event) => onSort(event.target.value as Sort, descending)}
          >
            {SORTS.map((option) => (
              <option key={option} value={option}>
                {t(`terminal.sorts.${option}`)}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="button button-quiet button-small"
          onClick={() => onSort(sort, !descending)}
          title={t(descending ? 'terminal.descending' : 'terminal.ascending')}
          aria-label={t(descending ? 'terminal.descending' : 'terminal.ascending')}
        >
          {descending ? '↓' : '↑'}
        </button>

        <label className="select-field select-inline">
          <span className="visually-hidden">{t('terminal.density')}</span>
          <select
            className="input input-select"
            value={density}
            onChange={(event) => onDensity(event.target.value as Density)}
          >
            {(Object.keys(DENSITIES) as Density[]).map((option) => (
              <option key={option} value={option}>
                {t(`terminal.densities.${option}`)}
              </option>
            ))}
          </select>
        </label>

        <span className="toolbar-count">{t('terminal.count', { count: total })}</span>
      </div>
    </div>
  );
}

function SearchIcon() {
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" className="search-icon">
      <circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d="M10.5 10.5 14 14" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  );
}
