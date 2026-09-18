import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import {
  definitionBody,
  editorFromDefinition,
  emptyEditor,
  findDuplicates,
  hasContent,
  LOCK_MODES,
  parsePortable,
  portableFileName,
  toPortable,
  validatePattern,
  type Draft,
  type Editor,
  type EncodeResult,
  type Provider,
  type ProviderSettings,
  type Validation,
  providerSearchText,
} from '../api/patterns';
import {
  patternKeys,
  useDeployments,
  useDraftMutations,
  useDrafts,
  useNetworks,
  usePatternMutations,
  useProviders,
} from '../api/queries';
import { Badge } from '../components/Card';
import { ConfirmButton } from '../components/ConfirmButton';
import { ResourceLabelView, useCapability } from '../components/crafting/CraftingBits';
import { NetworkPicker } from '../components/network/NetworkPicker';
import { DeployDialog } from '../components/patterns/DeployDialog';
import { PatternEditor } from '../components/patterns/PatternEditor';
import { EmptyNotice, ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';
import { ResourceIcon } from '../components/terminal/ResourceIcon';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useLiveNetwork } from '../hooks/useLiveNetwork';
import { formatRelative } from '../lib/format';
import { resolveSelectedNetwork, useSelectedNetwork } from '../stores/selectedNetwork';

const TABS = ['studio', 'providers', 'duplicates', 'history'] as const;
type Tab = (typeof TABS)[number];

/** Processing-pattern limits as AE2 has them; the server may configure lower ones and says so when validating. */
const MAX_INPUTS = 81;
const MAX_OUTPUTS = 27;

/** Pattern Studio (spec sections 13-17): drafts, editors, validation, encoding, and pattern providers. */
export function PatternsPage() {
  const { t } = useTranslation();
  const networks = useNetworks();
  const storedId = useSelectedNetwork((state) => state.networkId);
  const selected = networks.data ? resolveSelectedNetwork(networks.data, storedId) : undefined;
  const [params, setParams] = useSearchParams();
  const tab: Tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'studio';

  return (
    <>
      <div className="page-header page-header-row">
        <div>
          <h1>{t('nav.patterns')}</h1>
          <p>{selected ? selected.displayName : t('patterns.subtitle')}</p>
        </div>
        {networks.data && selected ? <NetworkPicker networks={networks.data} selectedId={selected.id} /> : null}
      </div>
      <div className="tabs" role="tablist">
        {TABS.map((name) => (
          <button key={name} type="button" role="tab" aria-selected={tab === name}
            className={`tab${tab === name ? ' tab-active' : ''}`} onClick={() => setParams({ tab: name }, { replace: true })}>
            {t(`patterns.tabs.${name}`)}
          </button>
        ))}
      </div>
      {networks.isError && !networks.data ? (
        <ErrorNotice title={t('networks.title')} error={networks.error} onRetry={() => void networks.refetch()} />
      ) : tab === 'studio' ? (
        // Drafts are personal and work without a network; validating and encoding need one.
        <Studio networkId={selected?.id} />
      ) : networks.isPending ? (
        <LoadingNotice />
      ) : !selected ? (
        <EmptyNotice title={t('networks.noneTitle')}>{t('networks.noneBody')}</EmptyNotice>
      ) : tab === 'providers' ? (
        <Providers networkId={selected.id} />
      ) : tab === 'duplicates' ? (
        <Duplicates networkId={selected.id} />
      ) : (
        <History networkId={selected.id} />
      )}
    </>
  );
}

// --- studio -----------------------------------------------------------------------------------------

interface Current {
  draftId: string | null;
  name: string;
  description: string;
  editor: Editor;
}

const blank = (): Current => ({ draftId: null, name: '', description: '', editor: emptyEditor('CRAFTING') });

function Studio({ networkId }: { networkId: string | undefined }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const drafts = useDrafts(locale);
  const draftMutations = useDraftMutations(locale);
  const [current, setCurrent] = useState<Current>(blank);
  const [importError, setImportError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const assetVersion = drafts.data?.assetVersion ?? '';

  const open = (draft: Draft) => setCurrent({
    draftId: draft.id,
    name: draft.name,
    description: draft.description,
    editor: editorFromDefinition(draft.definition),
  });

  const importFile = async (file: File) => {
    setImportError(null);
    const portable = parsePortable(await file.text());
    if (!portable) {
      setImportError(t('patterns.import.invalid'));
      return;
    }
    draftMutations.create.mutate(
      { name: portable.name, description: portable.description, networkId: networkId ?? null, definition: portable.definition },
      { onSuccess: open },
    );
  };

  const list = drafts.data?.drafts ?? [];
  return (
    <div className="studio">
      <aside className="draft-library" aria-label={t('patterns.library')}>
        <div className="draft-library-actions">
          <button type="button" className="button button-primary button-small" onClick={() => setCurrent(blank())}>
            {t('patterns.new')}
          </button>
          <button type="button" className="button button-small" onClick={() => fileInput.current?.click()}>
            {t('patterns.import.button')}
          </button>
          <input ref={fileInput} type="file" accept=".json,application/json" hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file) void importFile(file);
            }} />
        </div>
        {importError ? <p className="form-error" role="alert">{importError}</p> : null}
        <FormError error={draftMutations.remove.error} />
        {drafts.isPending ? (
          <LoadingNotice />
        ) : drafts.isError ? (
          <ErrorNotice title={t('patterns.draftsError')} error={drafts.error} onRetry={() => void drafts.refetch()} />
        ) : list.length === 0 ? (
          <p className="muted empty-inline">{t('patterns.noDrafts')}</p>
        ) : (
          <ul className="draft-list">
            {list.map((draft) => (
              <li key={draft.id}>
                <button type="button" className={`draft-item${current.draftId === draft.id ? ' draft-item-active' : ''}`}
                  onClick={() => open(draft)}>
                  <DraftIcon draft={draft} assetVersion={assetVersion} />
                  <span className="draft-item-text">
                    <span className="draft-item-name">{draft.name}</span>
                    <span className="draft-item-meta">
                      {t(`patterns.types.${draft.definition.type}`)} · {formatRelative(draft.updatedAt, locale)}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {drafts.data ? (
          <p className="footnote">{t('patterns.draftCount', { used: list.length, max: drafts.data.max })}</p>
        ) : null}
      </aside>

      <DraftEditor
        key={current.draftId ?? 'new'}
        networkId={networkId}
        current={current}
        onChange={setCurrent}
        assetVersion={assetVersion}
        onDeleted={() => setCurrent(blank())}
      />
    </div>
  );
}

function DraftIcon({ draft, assetVersion }: { draft: Draft; assetVersion: string }) {
  const first = draft.definition.outputs.find((slot) => slot !== null) ?? draft.definition.inputs.find((slot) => slot !== null);
  return first ? <ResourceIcon iconKey={first.resource.iconKey} assetVersion={assetVersion} size={28} />
    : <span className="draft-icon-empty" aria-hidden="true" />;
}

function DraftEditor({
  networkId,
  current,
  onChange,
  assetVersion,
  onDeleted,
}: {
  networkId: string | undefined;
  current: Current;
  onChange: (current: Current) => void;
  assetVersion: string;
  onDeleted: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const canStudio = useCapability(networkId, 'PATTERN_STUDIO');
  const canDeploy = useCapability(networkId, 'DEPLOY_PATTERNS');
  const draftMutations = useDraftMutations(locale);
  const patternMutations = usePatternMutations(networkId ?? '', locale);
  const [deploying, setDeploying] = useState(false);
  const [encoded, setEncoded] = useState<EncodeResult | null>(null);
  useLiveNetwork(networkId, locale);

  const body = definitionBody(current.editor);
  const bodyKey = useDebouncedValue(JSON.stringify(body), 400);
  const validation = useQuery({
    queryKey: patternKeys.validation(networkId ?? '', bodyKey, locale),
    queryFn: ({ signal }) => validatePattern(networkId ?? '', JSON.parse(bodyKey), locale, signal),
    enabled: networkId !== undefined && canStudio && hasContent(current.editor),
    placeholderData: (previous) => previous,
    staleTime: 5_000,
  });
  const upToDate = bodyKey === JSON.stringify(body) && !validation.isFetching;
  const valid = upToDate && validation.data?.valid === true;
  const version = validation.data?.assetVersion ?? assetVersion;

  const input = () => ({
    name: current.name.trim() || suggestedName(current.editor) || t('patterns.untitled'),
    description: current.description,
    networkId: networkId ?? null,
    definition: body,
  });
  const save = () => {
    const payload = input();
    const done = (draft: Draft) => onChange({ ...current, draftId: draft.id, name: draft.name });
    if (current.draftId) {
      draftMutations.update.mutate({ id: current.draftId, input: payload }, { onSuccess: done });
    } else {
      draftMutations.create.mutate(payload, { onSuccess: done });
    }
  };
  const exportDraft = () => {
    const payload = input();
    const blob = new Blob([JSON.stringify(toPortable(payload.name, payload.description, current.editor), null, 2)],
      { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = portableFileName(payload.name);
    link.click();
    URL.revokeObjectURL(url);
  };

  const saving = draftMutations.create.isPending || draftMutations.update.isPending;
  return (
    <section className="draft-editor card">
      <div className="draft-meta">
        <label className="form-field">
          <span>{t('patterns.name')}</span>
          <input className="input" value={current.name} maxLength={64} placeholder={suggestedName(current.editor) ?? t('patterns.untitled')}
            onChange={(event) => onChange({ ...current, name: event.target.value })} />
        </label>
        <label className="form-field">
          <span>{t('patterns.description')}</span>
          <input className="input" value={current.description} maxLength={1000}
            onChange={(event) => onChange({ ...current, description: event.target.value })} />
        </label>
      </div>

      <PatternEditor
        networkId={networkId}
        editor={current.editor}
        onChange={(editor) => {
          setEncoded(null);
          onChange({ ...current, editor });
        }}
        validation={upToDate ? validation.data : undefined}
        assetVersion={version}
        maxInputs={MAX_INPUTS}
        maxOutputs={MAX_OUTPUTS}
      />

      <ValidationPanel
        networkId={networkId}
        canStudio={canStudio}
        empty={!hasContent(current.editor)}
        pending={!upToDate}
        validation={validation}
      />

      {encoded ? (
        <div className="craft-started" role="status">
          <strong>{t('patterns.encoded')}</strong>
          <p>{t('patterns.encodedHint')}</p>
        </div>
      ) : null}
      <FormError error={draftMutations.create.error ?? draftMutations.update.error} />
      <FormError error={patternMutations.encode.error} />

      <div className="dialog-actions draft-actions">
        <button type="button" className="button button-primary" disabled={!canDeploy || !valid}
          title={canDeploy ? undefined : t('patterns.needManager')} onClick={() => setDeploying(true)}>
          {t('patterns.encodeAndDeploy')}
        </button>
        <ConfirmButton
          variant="quiet"
          label={patternMutations.encode.isPending ? t('patterns.encoding') : t('patterns.encode')}
          disabled={!canStudio || !valid || patternMutations.encode.isPending}
          onConfirm={() => patternMutations.encode.mutate({ definition: body, draftId: current.draftId },
            { onSuccess: setEncoded })}
        />
        <button type="button" className="button" disabled={saving} onClick={save}>
          {saving ? t('common.saving') : t('patterns.saveDraft')}
        </button>
        {current.draftId ? (
          <button type="button" className="button button-quiet" disabled={draftMutations.create.isPending}
            onClick={() => draftMutations.create.mutate({ ...input(), name: t('patterns.copyName', { name: input().name }).slice(0, 64) },
              { onSuccess: (draft) => onChange({ ...current, draftId: draft.id, name: draft.name }) })}>
            {t('patterns.clone')}
          </button>
        ) : null}
        <button type="button" className="button button-quiet" onClick={exportDraft}>{t('patterns.export')}</button>
        {current.draftId ? (
          <ConfirmButton label={t('patterns.delete')} disabled={draftMutations.remove.isPending}
            onConfirm={() => draftMutations.remove.mutate(current.draftId ?? '', { onSuccess: onDeleted })} />
        ) : null}
      </div>

      {deploying && networkId ? (
        <DeployDialog networkId={networkId} definition={body} draftId={current.draftId} onClose={() => setDeploying(false)} />
      ) : null}
    </section>
  );
}

/** A name from the pattern's main output or first input, so a quickly saved draft is still recognizable. */
function suggestedName(editor: Editor): string | null {
  const slot = editor.outputs.find((candidate) => candidate !== null) ?? editor.inputs.find((candidate) => candidate !== null);
  return slot ? slot.resource.name.slice(0, 64) : null;
}

function ValidationPanel({
  networkId,
  canStudio,
  empty,
  pending,
  validation,
}: {
  networkId: string | undefined;
  canStudio: boolean;
  empty: boolean;
  pending: boolean;
  validation: UseQueryResult<Validation>;
}) {
  const { t, i18n } = useTranslation();
  if (!networkId) {
    return <p className="form-hint">{t('patterns.noNetwork')}</p>;
  }
  if (!canStudio) {
    return <p className="form-hint">{t('patterns.needManager')}</p>;
  }
  if (empty) {
    return <p className="form-hint">{t('patterns.startHint')}</p>;
  }
  if (validation.isError) {
    return <ErrorNotice title={t('patterns.validationError')} error={validation.error} onRetry={() => void validation.refetch()} />;
  }
  if (!validation.data || pending) {
    return <p className="form-hint" role="status">{t('patterns.validating')}</p>;
  }
  const data = validation.data;
  return (
    <div className="validation" role="status">
      {data.valid ? <Badge tone="success">{t('patterns.valid')}</Badge> : <Badge tone="danger">{t('patterns.invalid')}</Badge>}
      {data.blankPatterns !== null ? (
        <span className={data.blankPatterns < 1 ? 'form-error' : 'muted'}>
          {t('patterns.blankPatterns', { count: data.blankPatterns })}
        </span>
      ) : null}
      {data.issues.length > 0 ? (
        <ul className="issue-list">
          {data.issues.map((issue, index) => (
            <li key={index}>
              {issue.field ? <code>{issue.field}</code> : null}{' '}
              {i18n.exists(`patterns.issues.${issue.code}`) ? t(`patterns.issues.${issue.code}`) : issue.message}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

// --- providers ----------------------------------------------------------------------------------------

function Providers({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  useLiveNetwork(networkId, locale);
  const providers = useProviders(networkId, locale);
  const mutations = usePatternMutations(networkId, locale);
  const [filter, setFilter] = useState('');

  if (providers.isPending) {
    return <LoadingNotice />;
  }
  if (providers.isError) {
    return <ErrorNotice title={t('patterns.providers.error')} error={providers.error} onRetry={() => void providers.refetch()} />;
  }
  const data = providers.data;
  if (data.providers.length === 0) {
    return <EmptyNotice title={t('patterns.providers.none')}>{t('patterns.providers.noneHint')}</EmptyNotice>;
  }
  const online = data.providers.filter((provider) => provider.online).length;
  const needle = filter.trim().toLowerCase();
  const shown = needle ? data.providers.filter((provider) => providerSearchText(provider).includes(needle)) : data.providers;
  return (
    <>
      <div className="provider-toolbar">
        <p className="muted section-note">
          {t('patterns.providers.summary', { total: data.providers.length, online })} ·{' '}
          {t('patterns.blankPatterns', { count: data.blankPatterns })} ·{' '}
          {t('terminal.updated', { time: formatRelative(data.capturedAt, locale) })}
        </p>
        <input className="input" type="search" value={filter} onChange={(event) => setFilter(event.target.value)}
          placeholder={t('patterns.providers.filter')} aria-label={t('patterns.providers.filter')} />
      </div>
      <FormError error={mutations.rename.error ?? mutations.configure.error} />
      <div className="provider-grid">
        {shown.map((provider) => (
          <ProviderCard key={provider.id} provider={provider} assetVersion={data.assetVersion}
            canRename={data.canConfigure && provider.renamable}
            canConfigure={data.canConfigure && provider.priority !== null}
            renaming={mutations.rename.isPending || mutations.configure.isPending}
            onRename={(name) => mutations.rename.mutate({ providerId: provider.id, name })}
            onConfigure={(settings) => mutations.configure.mutate({ providerId: provider.id, settings })} />
        ))}
      </div>
      {shown.length === 0 ? <p className="muted">{t('terminal.noMatches')}</p> : null}
    </>
  );
}

function ProviderCard({
  provider,
  assetVersion,
  canRename,
  canConfigure,
  renaming,
  onRename,
  onConfigure,
}: {
  provider: Provider;
  assetVersion: string;
  canRename: boolean;
  canConfigure: boolean;
  renaming: boolean;
  onRename: (name: string) => void;
  onConfigure: (settings: ProviderSettings) => void;
}) {
  const { t, i18n } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [settings, setSettings] = useState<ProviderSettings | null>(null);
  return (
    <article className="card provider-card">
      <header className="card-header">
        {editing ? (
          <form className="provider-rename" onSubmit={(event) => {
            event.preventDefault();
            onRename(draft.trim());
            setEditing(false);
          }}>
            <input className="input" value={draft} maxLength={64} autoFocus placeholder={t('patterns.providers.renameHint')}
              aria-label={t('patterns.providers.rename')} onChange={(event) => setDraft(event.target.value)} />
            <button type="submit" className="button button-primary button-small" disabled={renaming}>{t('common.save')}</button>
            <button type="button" className="button button-quiet button-small" onClick={() => setEditing(false)}>
              {t('common.cancel')}
            </button>
          </form>
        ) : (
          <h2 className="card-title provider-title">
            {provider.icon ? <ResourceIcon iconKey={provider.icon.iconKey} assetVersion={assetVersion} size={28} /> : null}
            <span title={provider.name ?? undefined}>{provider.name ?? t('patterns.providers.unnamed')}</span>
            {canRename ? (
              <button type="button" className="button button-quiet button-small provider-rename-button"
                aria-label={t('patterns.providers.rename')} title={t('patterns.providers.rename')}
                onClick={() => {
                  setDraft(provider.customName ?? '');
                  setEditing(true);
                }}>
                ✎
              </button>
            ) : null}
          </h2>
        )}
        {provider.online ? <Badge tone="success">{t('patterns.providers.online')}</Badge>
          : <Badge tone="danger">{t('patterns.providers.offline')}</Badge>}
      </header>
      <dl className="fields">
        {provider.kind ? (
          <div className="field">
            <dt>{t('patterns.providers.kind')}</dt>
            <dd><ResourceLabelView resource={provider.kind} assetVersion={assetVersion} size={20} /></dd>
          </div>
        ) : null}
        {provider.machine ? (
          <div className="field">
            <dt>{t('patterns.providers.machine')}</dt>
            <dd><ResourceLabelView resource={provider.machine} assetVersion={assetVersion} size={20} /></dd>
          </div>
        ) : null}
        <div className="field">
          <dt>{t('patterns.providers.slotsLabel')}</dt>
          <dd>{t('patterns.providers.slots', { used: provider.usedSlots, total: provider.slots })}</dd>
        </div>
        {provider.location ? (
          <div className="field">
            <dt>{t('patterns.providers.location')}</dt>
            <dd><code>{provider.location.dimension} {provider.location.x}, {provider.location.y}, {provider.location.z}</code></dd>
          </div>
        ) : null}
        {provider.priority !== null ? (
          <div className="field"><dt>{t('patterns.providers.priority')}</dt><dd>{provider.priority}</dd></div>
        ) : null}
        {provider.blocking !== null ? (
          <div className="field">
            <dt>{t('patterns.providers.blocking')}</dt>
            <dd>{provider.blocking ? t('common.yes') : t('common.no')}</dd>
          </div>
        ) : null}
        {provider.lockMode !== null ? (
          <div className="field">
            <dt>{t('patterns.providers.lockMode')}</dt>
            <dd>{i18n.exists(`patterns.lockModes.${provider.lockMode}`) ? t(`patterns.lockModes.${provider.lockMode}`) : provider.lockMode}</dd>
          </div>
        ) : null}
        {provider.visibleInTerminal !== null ? (
          <div className="field">
            <dt>{t('patterns.providers.visible')}</dt>
            <dd>{provider.visibleInTerminal ? t('common.yes') : t('common.no')}</dd>
          </div>
        ) : null}
      </dl>
      {settings ? (
        <form className="provider-settings" onSubmit={(event) => {
          event.preventDefault();
          onConfigure(settings);
          setSettings(null);
        }}>
          <label className="form-field">
            <span>{t('patterns.providers.priority')}</span>
            <input className="input" type="number" step={1} value={settings.priority}
              onChange={(event) => setSettings({ ...settings, priority: Math.trunc(Number(event.target.value) || 0) })} />
          </label>
          <label className="form-field">
            <span>{t('patterns.providers.lockMode')}</span>
            <select className="input" value={settings.lockMode}
              onChange={(event) => setSettings({ ...settings, lockMode: event.target.value })}>
              {LOCK_MODES.map((mode) => <option key={mode} value={mode}>{t(`patterns.lockModes.${mode}`)}</option>)}
            </select>
          </label>
          <label className="check">
            <input type="checkbox" checked={settings.blocking}
              onChange={(event) => setSettings({ ...settings, blocking: event.target.checked })} />
            {t('patterns.providers.blocking')}
          </label>
          <label className="check">
            <input type="checkbox" checked={settings.visibleInTerminal}
              onChange={(event) => setSettings({ ...settings, visibleInTerminal: event.target.checked })} />
            {t('patterns.providers.visible')}
          </label>
          <div className="order-actions">
            <button type="submit" className="button button-primary button-small" disabled={renaming}>{t('common.save')}</button>
            <button type="button" className="button button-quiet button-small" onClick={() => setSettings(null)}>
              {t('common.cancel')}
            </button>
          </div>
        </form>
      ) : canConfigure ? (
        <div className="order-actions">
          <button type="button" className="button button-quiet button-small" onClick={() => setSettings({
            priority: provider.priority ?? 0,
            blocking: provider.blocking ?? false,
            lockMode: provider.lockMode ?? 'NONE',
            visibleInTerminal: provider.visibleInTerminal ?? true,
          })}>
            {t('patterns.providers.settings')}
          </button>
        </div>
      ) : null}
      {provider.patterns.length > 0 ? (
        <ul className="stored-patterns" aria-label={t('patterns.providers.patterns')}>
          {provider.patterns.map((pattern) => {
            const output = pattern.outputs[0];
            return (
              <li key={pattern.slot} className="stored-pattern"
                title={`${pattern.type ? t(`patterns.types.${pattern.type}`) : t('patterns.providers.unknownType')}${output ? ` · ${output.resource.name} ×${output.amount}` : ''}`}>
                {output ? <ResourceIcon iconKey={output.resource.iconKey} assetVersion={assetVersion} size={28} />
                  : <span className="draft-icon-empty" aria-hidden="true" />}
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="muted">{t('patterns.providers.empty')}</p>
      )}
    </article>
  );
}

// --- duplicates (spec section 17) ----------------------------------------------------------------------

type DuplicateFilter = 'all' | 'same' | 'different';

function Duplicates({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  useLiveNetwork(networkId, locale);
  const providers = useProviders(networkId, locale);
  const [filter, setFilter] = useState<DuplicateFilter>('all');

  if (providers.isPending) {
    return <LoadingNotice />;
  }
  if (providers.isError) {
    return <ErrorNotice title={t('patterns.providers.error')} error={providers.error} onRetry={() => void providers.refetch()} />;
  }
  const groups = findDuplicates(providers.data.providers);
  const shown = groups.filter((group) => filter === 'all' || group.sameInputs === (filter === 'same'));
  const { assetVersion } = providers.data;
  return (
    <>
      <div className="provider-toolbar">
        <p className="muted section-note">{t('patterns.duplicates.explain')}</p>
        <select className="input" value={filter} aria-label={t('patterns.duplicates.filter')}
          onChange={(event) => setFilter(event.target.value as DuplicateFilter)}>
          {(['all', 'same', 'different'] as const).map((name) => (
            <option key={name} value={name}>{t(`patterns.duplicates.filters.${name}`)}</option>
          ))}
        </select>
      </div>
      {shown.length === 0 ? (
        <EmptyNotice title={t('patterns.duplicates.none')}>{t('patterns.duplicates.noneHint')}</EmptyNotice>
      ) : (
        <div className="provider-grid">
          {shown.map((group) => (
            <article key={group.output.resource.id} className="card provider-card">
              <header className="card-header">
                <ResourceLabelView resource={group.output.resource} assetVersion={assetVersion} size={28} />
                <Badge tone={group.sameInputs ? 'warning' : 'neutral'}>
                  {group.sameInputs ? t('patterns.duplicates.same') : t('patterns.duplicates.different')}
                </Badge>
              </header>
              <ul className="list list-compact">
                {group.entries.map(({ provider, pattern }) => (
                  <li key={`${provider.id}-${pattern.slot}`} className="list-row">
                    <div className="list-main">
                      <div className="list-title">{provider.name ?? t('patterns.providers.unnamed')}</div>
                      <div className="list-meta">
                        <span>{t('patterns.duplicates.priority', { priority: provider.priority ?? '—' })}</span>
                        <span>{t('patterns.duplicates.slot', { slot: pattern.slot + 1 })}</span>
                        <span>{pattern.inputs.map((input) => `${input.resource.name} ×${input.amount}`).join(', ')}</span>
                      </div>
                    </div>
                    {provider.online ? <Badge tone="success">{t('patterns.providers.online')}</Badge>
                      : <Badge tone="danger">{t('patterns.providers.offline')}</Badge>}
                  </li>
                ))}
              </ul>
            </article>
          ))}
        </div>
      )}
    </>
  );
}

// --- history ------------------------------------------------------------------------------------------

function History({ networkId }: { networkId: string }) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  useLiveNetwork(networkId, locale);
  const history = useDeployments(networkId, locale);

  if (history.isPending) {
    return <LoadingNotice />;
  }
  if (history.isError) {
    return <ErrorNotice title={t('patterns.history.error')} error={history.error} onRetry={() => void history.refetch()} />;
  }
  if (history.data.deployments.length === 0) {
    return <EmptyNotice title={t('patterns.history.none')}>{t('patterns.history.noneHint')}</EmptyNotice>;
  }
  return (
    <ul className="list deployment-list">
      {history.data.deployments.map((deployment) => (
        <li key={deployment.id} className="list-row">
          <div className="list-main">
            {deployment.output ? (
              <ResourceLabelView resource={deployment.output} assetVersion={history.data.assetVersion} size={28} />
            ) : (
              <span className="list-title">{t(`patterns.types.${deployment.type}`)}</span>
            )}
            <span className="list-meta">
              {t(`patterns.history.${deployment.action}`)}
              {deployment.providerName ? ` → ${deployment.providerName}` : ''}
              {' · '}{deployment.actor.playerName ?? t('common.unknownPlayer')}
              {' · '}{formatRelative(deployment.at, locale)}
            </span>
          </div>
          {deployment.errorCode ? (
            <Badge tone="danger" title={i18n.exists(`errors.codes.${deployment.errorCode}`) ? t(`errors.codes.${deployment.errorCode}`) : undefined}>
              {deployment.errorCode}
            </Badge>
          ) : (
            <Badge tone="success">{t('patterns.history.success')}</Badge>
          )}
        </li>
      ))}
    </ul>
  );
}
