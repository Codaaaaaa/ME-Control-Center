import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ResourceLabel } from '../../api/crafting';
import {
  applyRecipe,
  emptyEditor,
  PATTERN_TYPES,
  recipeDriven,
  type Editor,
  type PatternIssue,
  type PatternType,
  type Slot,
  type Validation,
} from '../../api/patterns';
import { exactAmount } from '../../lib/amount';
import { Badge } from '../Card';
import { ResourceLabelView } from '../crafting/CraftingBits';
import { ResourceIcon } from '../terminal/ResourceIcon';
import { ResourceName } from '../terminal/ResourceName';
import { RecipeFill } from './RecipeFill';
import { ResourcePicker } from './ResourcePicker';

type Target = { list: 'inputs' | 'outputs'; index: number };

/**
 * Pattern editors (spec sections 13.2-13.5): a 3x3 crafting grid, free-form processing inputs and outputs,
 * smithing template/base/addition, and stonecutter input with a chosen recipe. The browser only edits a
 * definition; the server checks it against the game.
 */
export function PatternEditor({
  networkId,
  editor,
  onChange,
  validation,
  assetVersion,
  maxInputs,
  maxOutputs,
}: {
  networkId: string | undefined;
  editor: Editor;
  onChange: (editor: Editor) => void;
  validation: Validation | undefined;
  assetVersion: string;
  maxInputs: number;
  maxOutputs: number;
}) {
  const { t } = useTranslation();
  const [picking, setPicking] = useState<Target | null>(null);
  const [filling, setFilling] = useState(false);
  const issues = validation?.issues ?? [];

  const setSlot = (target: Target, slot: Slot) => {
    const list = [...editor[target.list]];
    list[target.index] = slot;
    // A chosen recipe may stop matching once an input changes: the server picks again (the stonecutter asks).
    onChange({ ...editor, [target.list]: list, recipeId: target.list === 'inputs' ? null : editor.recipeId });
  };

  const changeType = (type: PatternType) => {
    if (type !== editor.type) onChange(emptyEditor(type));
  };

  const slotProps = (list: 'inputs' | 'outputs', index: number) => ({
    slot: editor[list][index] ?? null,
    assetVersion,
    issue: issueFor(issues, `${list}[${index}]`),
    onPick: () => setPicking({ list, index }),
    onClear: () => setSlot({ list, index }, null),
  });

  const output = validation?.valid ? validation.outputs[0] : undefined;

  return (
    <div className="pattern-editor">
      <div className="segmented" role="radiogroup" aria-label={t('patterns.type')}>
        {PATTERN_TYPES.map((type) => (
          <button key={type} type="button" role="radio" aria-checked={editor.type === type}
            className={`segment${editor.type === type ? ' segment-active' : ''}`} onClick={() => changeType(type)}>
            {t(`patterns.types.${type}`)}
          </button>
        ))}
      </div>

      {editor.type === 'CRAFTING' ? (
        <div className="recipe-layout">
          <div className="crafting-grid">
            {editor.inputs.map((_, index) => (
              <SlotButton key={index} {...slotProps('inputs', index)} label={t('patterns.slot', { index: index + 1 })} />
            ))}
          </div>
          <span className="recipe-arrow" aria-hidden="true">→</span>
          <OutputPreview output={output} assetVersion={assetVersion} />
        </div>
      ) : null}

      {editor.type === 'SMITHING' ? (
        <div className="recipe-layout">
          <div className="smithing-slots">
            {(['template', 'base', 'addition'] as const).map((name, index) => (
              <div key={name} className="named-slot">
                <SlotButton {...slotProps('inputs', index)} label={t(`patterns.smithing.${name}`)} />
                <span className="named-slot-label">{t(`patterns.smithing.${name}`)}</span>
              </div>
            ))}
          </div>
          <span className="recipe-arrow" aria-hidden="true">→</span>
          <OutputPreview output={output} assetVersion={assetVersion} />
        </div>
      ) : null}

      {editor.type === 'STONECUTTING' ? (
        <div className="recipe-layout">
          <div className="named-slot">
            <SlotButton {...slotProps('inputs', 0)} label={t('patterns.stonecutting.input')} />
            <span className="named-slot-label">{t('patterns.stonecutting.input')}</span>
          </div>
          <span className="recipe-arrow" aria-hidden="true">→</span>
          <div className="stonecutting-output">
            <OutputPreview output={output} assetVersion={assetVersion} />
            <button type="button" className="button button-small" disabled={!editor.inputs[0]} onClick={() => setFilling(true)}>
              {editor.recipeId ? t('patterns.stonecutting.change') : t('patterns.stonecutting.choose')}
            </button>
          </div>
        </div>
      ) : null}

      {editor.type === 'PROCESSING' ? (
        <div className="processing">
          <StackList
            title={t('patterns.processing.inputs')}
            list="inputs"
            slots={editor.inputs}
            max={maxInputs}
            issues={issues}
            assetVersion={assetVersion}
            onPick={(index) => setPicking({ list: 'inputs', index })}
            onChange={(inputs) => onChange({ ...editor, inputs })}
          />
          <StackList
            title={t('patterns.processing.outputs')}
            list="outputs"
            slots={editor.outputs}
            max={maxOutputs}
            issues={issues}
            assetVersion={assetVersion}
            onPick={(index) => setPicking({ list: 'outputs', index })}
            onChange={(outputs) => onChange({ ...editor, outputs })}
          />
          <p className="form-hint">{t('patterns.processing.hint')}</p>
        </div>
      ) : null}

      <div className="editor-options">
        {editor.type !== 'STONECUTTING' ? (
          <button type="button" className="button button-small" onClick={() => setFilling(true)}>
            {t('patterns.fillFromRecipe')}
          </button>
        ) : null}
        {recipeDriven(editor.type) ? (
          <label className="check">
            <input type="checkbox" checked={editor.substitutes}
              onChange={(event) => onChange({ ...editor, substitutes: event.target.checked })} />
            <span>{t('patterns.substitutes')}</span>
          </label>
        ) : null}
        {editor.type === 'CRAFTING' ? (
          <label className="check">
            <input type="checkbox" checked={editor.fluidSubstitutes}
              onChange={(event) => onChange({ ...editor, fluidSubstitutes: event.target.checked })} />
            <span>{t('patterns.fluidSubstitutes')}</span>
          </label>
        ) : null}
        {editor.recipeId && editor.type !== 'PROCESSING' ? (
          <span className="muted recipe-source" title={t('patterns.recipeSource')}>
            {t('patterns.recipeSource')}: <code>{validation?.recipeId ?? editor.recipeId}</code>
          </span>
        ) : validation?.recipeId ? (
          <span className="muted recipe-source">
            {t('patterns.recipeSource')}: <code>{validation.recipeId}</code>
          </span>
        ) : null}
      </div>

      {picking ? (
        <ResourcePicker
          networkId={networkId}
          itemsOnly={recipeDriven(editor.type)}
          title={t('patterns.pickResource')}
          onPick={(resource) => {
            const existing = editor[picking.list][picking.index];
            setSlot(picking, { resource, amount: recipeDriven(editor.type) ? 1 : existing?.amount ?? defaultAmount(resource) });
            setPicking(null);
          }}
          onClose={() => setPicking(null)}
        />
      ) : null}

      {filling ? (
        <RecipeFill
          type={editor.type}
          networkId={networkId}
          initial={editor.type === 'STONECUTTING' ? editor.inputs[0]?.resource ?? null : null}
          assetVersion={assetVersion}
          onApply={(recipe) => {
            onChange({ ...applyRecipe(editor, recipe), substitutes: editor.substitutes,
              fluidSubstitutes: editor.fluidSubstitutes });
            setFilling(false);
          }}
          onClose={() => setFilling(false)}
        />
      ) : null}
    </div>
  );
}

/** A fluid starts at one bucket, anything else at one. */
function defaultAmount(resource: ResourceLabel): number {
  return resource.unit ? resource.unit.amountPerUnit : 1;
}

function issueFor(issues: PatternIssue[], field: string): PatternIssue | undefined {
  return issues.find((issue) => issue.field === field);
}

function SlotButton({
  slot,
  assetVersion,
  issue,
  label,
  onPick,
  onClear,
}: {
  slot: Slot;
  assetVersion: string;
  issue: PatternIssue | undefined;
  label: string;
  onPick: () => void;
  onClear: () => void;
}) {
  const { t } = useTranslation();
  return (
    <span className={`pattern-slot${issue ? ' pattern-slot-issue' : ''}`}>
      <button
        type="button"
        className="pattern-slot-button"
        onClick={onPick}
        onContextMenu={(event) => {
          if (slot) {
            event.preventDefault();
            onClear();
          }
        }}
        aria-label={slot ? `${label}: ${slot.resource.name}` : `${label}: ${t('patterns.emptySlot')}`}
        title={slot ? `${slot.resource.name}\n${slot.resource.id}` : issue?.message ?? t('patterns.emptySlot')}
      >
        {slot ? <ResourceIcon iconKey={slot.resource.iconKey} assetVersion={assetVersion} size={32} />
          : <span className="pattern-slot-empty" aria-hidden="true">+</span>}
      </button>
      {slot ? (
        <button type="button" className="pattern-slot-clear" onClick={onClear} aria-label={t('patterns.clearSlot')}>
          ✕
        </button>
      ) : null}
    </span>
  );
}

function OutputPreview({ output, assetVersion }: { output: Validation['outputs'][number] | undefined; assetVersion: string }) {
  const { t } = useTranslation();
  return (
    <div className="output-preview" aria-live="polite">
      {output ? (
        <ResourceLabelView resource={output.resource} assetVersion={assetVersion} size={40} amount={output.amount} />
      ) : (
        <span className="muted">{t('patterns.noOutput')}</span>
      )}
    </div>
  );
}

function StackList({
  title,
  list,
  slots,
  max,
  issues,
  assetVersion,
  onPick,
  onChange,
}: {
  title: string;
  list: 'inputs' | 'outputs';
  slots: Slot[];
  max: number;
  issues: PatternIssue[];
  assetVersion: string;
  onPick: (index: number) => void;
  onChange: (slots: Slot[]) => void;
}) {
  const { t } = useTranslation();
  return (
    <section className="stack-list">
      <h3 className="section-title">{title}</h3>
      <ol>
        {slots.map((slot, index) => {
          const issue = issueFor(issues, `${list}[${index}]`);
          return (
            <li key={index} className={`stack-row${issue ? ' stack-row-issue' : ''}`} title={issue?.message}>
              <button type="button" className="stack-resource" onClick={() => onPick(index)}>
                {slot ? (
                  <>
                    <ResourceIcon iconKey={slot.resource.iconKey} assetVersion={assetVersion} size={28} />
                    <span className="stack-name"><ResourceName resource={slot.resource} /></span>
                  </>
                ) : (
                  <span className="muted">{t('patterns.choose')}</span>
                )}
              </button>
              {slot ? (
                <AmountInput slot={slot} onChange={(amount) => {
                  const next = [...slots];
                  next[index] = { ...slot, amount };
                  onChange(next);
                }} />
              ) : null}
              {list === 'outputs' && index === 0 ? <Badge tone="accent">{t('patterns.processing.primary')}</Badge> : null}
              <button type="button" className="button button-quiet button-small" aria-label={t('patterns.removeRow')}
                disabled={slots.length === 1 && slot === null}
                onClick={() => {
                  const next = slots.filter((_, position) => position !== index);
                  onChange(next.length === 0 ? [null] : next);
                }}>
                ✕
              </button>
            </li>
          );
        })}
      </ol>
      <button type="button" className="button button-small" disabled={slots.length >= max}
        onClick={() => onChange([...slots, null])}>
        {t('patterns.addRow')}
      </button>
    </section>
  );
}

/** Amounts are typed in the resource's display unit (buckets for fluids) and stored raw. */
function AmountInput({ slot, onChange }: { slot: NonNullable<Slot>; onChange: (amount: number) => void }) {
  const { t, i18n } = useTranslation();
  const unit = slot.resource.unit;
  const shown = unit ? slot.amount / unit.amountPerUnit : slot.amount;
  const [text, setText] = useState(String(shown));
  const [editing, setEditing] = useState(false);
  const value = editing ? text : String(shown);
  return (
    <label className="amount-field" title={exactAmount({ amount: slot.amount, unit }, i18n.language)}>
      <span className="visually-hidden">{t('crafting.amount')}</span>
      <input
        className="input"
        inputMode="decimal"
        value={value}
        onFocus={() => {
          setText(String(shown));
          setEditing(true);
        }}
        onBlur={() => setEditing(false)}
        onChange={(event) => {
          setText(event.target.value);
          const parsed = Number(event.target.value.replace(',', '.'));
          const raw = Math.round(unit ? parsed * unit.amountPerUnit : parsed);
          if (Number.isFinite(raw) && raw >= 1 && raw <= Number.MAX_SAFE_INTEGER) onChange(raw);
        }}
      />
      {unit ? <span className="amount-unit">{unit.symbol}</span> : null}
    </label>
  );
}
