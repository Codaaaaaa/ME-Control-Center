import { useQuery } from '@tanstack/react-query';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResourceLabel } from '../../api/crafting';
import { fetchRecipes, type PatternType, type Recipe } from '../../api/patterns';
import { patternKeys } from '../../api/queries';
import { compactAmount } from '../../lib/amount';
import { Badge } from '../Card';
import { ResourceLabelView } from '../crafting/CraftingBits';
import { EmptyNotice, ErrorNotice, LoadingNotice } from '../StateNotice';
import { ResourceIcon } from '../terminal/ResourceIcon';
import { ResourcePicker } from './ResourcePicker';

/**
 * "Fill from Recipe" (spec section 13): find the server's recipes by what they make - or, for the stonecutter,
 * by what they take - and copy one into the editor.
 */
export function RecipeFill({
  type,
  networkId,
  initial,
  assetVersion,
  onApply,
  onClose,
}: {
  type: PatternType;
  networkId: string | undefined;
  /** For the stonecutter: the input already in the editor. */
  initial: ResourceLabel | null;
  assetVersion: string;
  onApply: (recipe: Recipe) => void;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const titleId = useId();
  const byInput = type === 'STONECUTTING';
  const [target, setTarget] = useState<ResourceLabel | null>(byInput ? initial : null);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && target) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, target]);

  const recipes = useQuery({
    queryKey: patternKeys.recipes(type, `${byInput ? 'in' : 'out'}:${target?.id ?? ''}`, locale),
    queryFn: ({ signal }) =>
      fetchRecipes(type, byInput ? { input: target?.id ?? '' } : { output: target?.id ?? '' }, locale, signal),
    enabled: target !== null,
  });

  if (!target) {
    return (
      <ResourcePicker
        networkId={networkId}
        itemsOnly={type !== 'PROCESSING'}
        title={byInput ? t('patterns.fill.pickInput') : t('patterns.fill.pickOutput')}
        onPick={(resource) => setTarget(resource)}
        onClose={onClose}
      />
    );
  }

  const version = recipes.data?.assetVersion ?? assetVersion;
  return (
    <div className="dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="dialog-header">
          <h2 id={titleId}>{t('patterns.fill.title')}</h2>
          <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label={t('common.close')}>
            ✕
          </button>
        </header>
        <div className="dialog-body">
          <div className="fill-target">
            <span className="muted">{byInput ? t('patterns.fill.input') : t('patterns.fill.output')}</span>
            <ResourceLabelView resource={target} assetVersion={version} size={28} />
            <button type="button" className="button button-quiet button-small" onClick={() => setTarget(null)}>
              {t('patterns.fill.change')}
            </button>
          </div>

          {recipes.isPending ? (
            <LoadingNotice />
          ) : recipes.isError ? (
            <ErrorNotice title={t('patterns.fill.error')} error={recipes.error} onRetry={() => void recipes.refetch()} />
          ) : recipes.data.recipes.length === 0 ? (
            <EmptyNotice title={t('patterns.fill.none')}>{t('patterns.fill.noneHint')}</EmptyNotice>
          ) : (
            <ul className="recipe-list">
              {recipes.data.recipes.map((recipe) => (
                <li key={recipe.id}>
                  <button type="button" className="recipe-option" onClick={() => onApply(recipe)}>
                    {recipe.category ? (
                      <span className="recipe-category">
                        <strong>{recipe.category.name ?? recipe.category.id}</strong>
                        {recipe.complete ? null : <Badge tone="warning">{t('patterns.fill.incomplete')}</Badge>}
                      </span>
                    ) : null}
                    <RecipeSlots recipe={recipe} assetVersion={version} />
                    <span className="recipe-arrow" aria-hidden="true">→</span>
                    <span className="recipe-outputs">
                      {[recipe.output, ...recipe.byproducts].map((output) => (
                        <ResourceLabelView key={output.resource.id} resource={output.resource} assetVersion={version}
                          size={28} amount={output.amount} />
                      ))}
                    </span>
                    <code className="recipe-id">{recipe.id}</code>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {recipes.data?.truncated ? <p className="footnote">{t('patterns.fill.truncated')}</p> : null}
          {type === 'PROCESSING' ? <p className="form-hint">{t('patterns.fill.processingHint')}</p> : null}
        </div>
      </div>
    </div>
  );
}

/** e.g. 144 mB of a 1000 mB/B fluid: "0.144B"; whole buckets without decimals. */
function compactUnits(amount: number, perUnit: number, symbol: string): string {
  const units = amount / perUnit;
  return `${Number.isInteger(units) ? units : Number(units.toFixed(3))}${symbol}`;
}

function RecipeSlots({ recipe, assetVersion }: { recipe: Recipe; assetVersion: string }) {
  const { t } = useTranslation();
  return (
    <span className={`recipe-slots recipe-slots-${recipe.type.toLowerCase()}`}>
      {recipe.slots.map((slot, index) => (
        <span key={index} className="recipe-slot"
          title={slot ? slot.options.map((option) => option.name).join(' / ') + (slot.more > 0 ? ` ${t('patterns.fill.moreOptions', { n: slot.more })}` : '') : undefined}>
          {slot && slot.options[0] ? (
            <ResourceIcon iconKey={slot.options[0].iconKey} assetVersion={assetVersion} size={20} />
          ) : null}
          {slot && recipe.type === 'PROCESSING' && (slot.amount > 1 || slot.options[0]?.unit) ? (
            <span className="recipe-slot-amount">
              {slot.options[0]?.unit ? compactUnits(slot.amount, slot.options[0].unit.amountPerUnit, slot.options[0].unit.symbol)
                : compactAmount(slot.amount)}
            </span>
          ) : null}
          {slot && slot.options.length + slot.more > 1 ? <span className="recipe-slot-alt" aria-hidden="true">+</span> : null}
        </span>
      ))}
    </span>
  );
}
