import { memo } from 'react';
import { useTranslation } from 'react-i18next';
import type { Resource } from '../../api/resources';
import { tileAmount } from '../../lib/amount';
import { ResourceIcon } from './ResourceIcon';

/**
 * One terminal tile, in AE2's style: the icon is the primary visual, with the amount overlaid and small
 * indicators for craftable and currently-crafting resources (spec sections 7.2 and 39).
 */
export const ResourceTile = memo(function ResourceTile({
  resource,
  assetVersion,
  size,
  selected,
  focusable,
  onSelect,
}: {
  resource: Resource | undefined;
  assetVersion: string;
  size: number;
  selected: boolean;
  focusable: boolean;
  onSelect: () => void;
}) {
  const { t } = useTranslation();
  if (!resource) {
    return <div className="tile tile-skeleton" style={{ width: size, height: size }} aria-hidden="true" />;
  }

  const amount = tileAmount(resource);
  const title = `${resource.name}\n${resource.modName}\n${amount}`;
  return (
    <button
      type="button"
      className={`tile${selected ? ' tile-selected' : ''}${resource.craftable ? ' tile-craftable' : ''}`}
      style={{ width: size, height: size }}
      title={title}
      tabIndex={focusable ? 0 : -1}
      aria-label={`${resource.name}, ${amount}`}
      aria-pressed={selected}
      data-resource-tile={resource.id}
      onClick={onSelect}
    >
      <ResourceIcon iconKey={resource.iconKey} assetVersion={assetVersion} size={Math.round(size * 0.66)} />
      <span className="tile-amount">{amount}</span>
      {resource.craftable ? (
        <span className="tile-flag tile-flag-craftable" title={t('terminal.craftable')}>
          +
        </span>
      ) : null}
      {resource.crafting !== null ? (
        <span className="tile-flag tile-flag-crafting" title={t('terminal.crafting')} />
      ) : null}
      {resource.type !== 'item' ? <span className="tile-type">{resource.type === 'fluid' ? '◆' : '●'}</span> : null}
    </button>
  );
});
