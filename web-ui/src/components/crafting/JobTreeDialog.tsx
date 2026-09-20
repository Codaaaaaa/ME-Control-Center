import { useQuery } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type PointerEvent, type ReactNode, type WheelEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { activeSteps, fetchJobTree, STEP_STATUSES, visibleSteps, type Step, type StepStatus } from '../../api/crafting';
import { fetchMachines, type Machine, type MachineStatus } from '../../api/machines';
import { exactAmount } from '../../lib/amount';
import { formatElapsed } from '../../lib/format';
import { Badge, type Tone } from '../Card';
import { ErrorNotice, LoadingNotice } from '../StateNotice';
import { ResourceIcon } from '../terminal/ResourceIcon';

/** The tree follows the CPU closely while it is open. */
const TREE_REFRESH_MS = 2_000;
const MIN_SCALE = 0.2;
const MAX_SCALE = 2.5;

/** Same as the machines page, so a step's machine and its card agree. */
const MACHINE_TONE: Record<MachineStatus, Tone> = {
  STUCK: 'danger', WAITING: 'warning', WORKING: 'success', IDLE: 'neutral', DISABLED: 'neutral',
};

export const STEP_TONE: Record<StepStatus, Tone> = {
  CRAFTING: 'accent',
  WAITING_MACHINE: 'warning',
  READY: 'neutral',
  WAITING_INPUTS: 'neutral',
  DONE: 'success',
  FROM_STORAGE: 'success',
};

/**
 * The crafting tree of a busy CPU (spec section 12): every step of the job as a tree that can be dragged and zoomed,
 * where each step stands, and what the CPU is doing right now. Finished branches can be hidden, and any step folded.
 */
export function JobTreeDialog({ networkId, cpuId, cpuName, onClose }: {
  networkId: string;
  cpuId: string;
  cpuName: string;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const titleId = useId();
  const [hideFinished, setHideFinished] = useState(false);
  const [folded, setFolded] = useState<ReadonlySet<string>>(new Set());
  const tree = useQuery({
    queryKey: ['crafting', networkId, 'tree', cpuId, locale],
    queryFn: ({ signal }) => fetchJobTree(networkId, cpuId, locale, signal),
    refetchInterval: TREE_REFRESH_MS,
    placeholderData: (previous) => previous,
  });

  // Shares the machines page's cache: a step names the machines its pattern sits in, and they carry the status.
  const machines = useQuery({
    queryKey: ['machines', networkId, locale],
    queryFn: ({ signal }) => fetchMachines(networkId, locale, signal),
    refetchInterval: TREE_REFRESH_MS * 2,
  });
  const byId = new Map((machines.data?.machines ?? []).map((machine) => [machine.id, machine] as const));

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const data = tree.data;
  const root = data ? visibleSteps(data.root, hideFinished) : null;
  const now = data ? activeSteps(data.root) : [];
  const toggle = (path: string) => setFolded((current) => {
    const next = new Set(current);
    if (next.has(path)) next.delete(path);
    else next.add(path);
    return next;
  });

  return (
    <div className="dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div className="dialog dialog-full" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="dialog-header">
          <h2 id={titleId}>{t('tree.title', { cpu: cpuName })}</h2>
          <button type="button" className="button button-quiet button-small" onClick={onClose} aria-label={t('common.close')}>
            ✕
          </button>
        </header>
        {tree.isPending ? (
          <LoadingNotice />
        ) : tree.isError && !data ? (
          <ErrorNotice title={t('tree.title', { cpu: cpuName })} error={tree.error} onRetry={() => void tree.refetch()} />
        ) : data ? (
          <div className="tree-layout">
            <aside className="tree-side">
              <div className="tree-summary">
                <ResourceIcon iconKey={data.output.iconKey} assetVersion={data.assetVersion} size={32} />
                <div>
                  <div className="list-title">{data.output.name} × {exactAmount({ amount: data.amount, unit: data.output.unit }, locale)}</div>
                  <div className="muted">
                    {data.elapsedMillis !== null ? t('tree.elapsed', { time: formatElapsed(data.elapsedMillis) }) : null}
                  </div>
                </div>
              </div>
              <div className="tree-counts">
                {STEP_STATUSES.filter((status) => status !== 'FROM_STORAGE' && data.counts[status]).map((status) => (
                  <Badge key={status} tone={STEP_TONE[status]}>{t(`tree.status.${status}`)} {data.counts[status]}</Badge>
                ))}
              </div>
              {data.partial ? <p className="form-hint">{t('tree.partial')}</p> : null}
              {tree.isError ? <p className="form-hint">{t('tree.stale')}</p> : null}
              <h3 className="config-section-title">{t('tree.now')}</h3>
              {now.length === 0 ? (
                <p className="muted-text">{t('tree.nothingNow')}</p>
              ) : (
                <ul className="list list-compact">
                  {now.map((step) => (
                    <li key={step.id} className="list-row">
                      <div className="list-main">
                        <div className="list-title tree-now-title">
                          <ResourceIcon iconKey={step.resource.iconKey} assetVersion={data.assetVersion} size={20} />
                          {step.resource.name}
                        </div>
                        <div className="list-meta">
                          <Badge tone={STEP_TONE[step.status]}>{t(`tree.status.${step.status}`)}</Badge>
                          <span>{t(`tree.explain.${step.status}`)}</span>
                        </div>
                        <StepMachines step={step} byId={byId} assetVersion={data.assetVersion} />
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              <label className="check">
                <input type="checkbox" checked={hideFinished} onChange={(event) => setHideFinished(event.target.checked)} />
                {t('tree.hideFinished')}
              </label>
            </aside>
            <TreeCanvas>
              {root ? (
                <ul className="tree">
                  <StepNode step={root} path={root.id} assetVersion={data.assetVersion} folded={folded} onToggle={toggle} />
                </ul>
              ) : (
                <p className="muted-text">{t('tree.allFinished')}</p>
              )}
            </TreeCanvas>
          </div>
        ) : null}
      </div>
    </div>
  );
}

/** Which machines hold this step's pattern, and where each one stands right now. */
function StepMachines({ step, byId, assetVersion }: {
  step: Step;
  byId: ReadonlyMap<string, Machine>;
  assetVersion: string;
}) {
  const { t } = useTranslation();
  const found = step.machineIds.map((id) => byId.get(id)).filter((machine): machine is Machine => machine !== undefined);
  if (found.length === 0) return null;
  return (
    <div className="list-meta">
      <span className="muted">{t('tree.machines')}</span>
      {found.map((machine) => (
        <span key={machine.id} className="tree-machine" title={machine.location
          ? `${machine.location.dimension} ${machine.location.x}, ${machine.location.y}, ${machine.location.z}`
          : undefined}>
          {machine.block ? <ResourceIcon iconKey={machine.block.iconKey} assetVersion={assetVersion} size={18} /> : null}
          {machine.block?.name ?? t('machines.unknownBlock')}
          <Badge tone={MACHINE_TONE[machine.status]}>{t(`machines.status.${machine.status}`)}</Badge>
        </span>
      ))}
    </div>
  );
}

function StepNode({ step, path, assetVersion, folded, onToggle }: {
  step: Step;
  path: string;
  assetVersion: string;
  folded: ReadonlySet<string>;
  onToggle: (path: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const locale = i18n.language;
  const isFolded = folded.has(path);
  const amount = (value: number) => exactAmount({ amount: value, unit: step.resource.unit }, locale);
  const crafted = step.status === 'FROM_STORAGE' ? null : Math.max(0, step.runs - step.remaining);
  return (
    <li>
      <button type="button" className={`tree-node tree-node-${step.status.toLowerCase()}`}
        onClick={() => step.children.length > 0 && onToggle(path)} aria-expanded={step.children.length > 0 ? !isFolded : undefined}
        title={`${step.resource.name} · ${t(`tree.explain.${step.status}`)}`}>
        <span className="tree-node-head">
          <ResourceIcon iconKey={step.resource.iconKey} assetVersion={assetVersion} size={28} />
          <span className="tree-node-name">{step.resource.name}</span>
        </span>
        <span className="tree-node-status">
          {step.status === 'CRAFTING' ? <span className="tree-spinner" aria-hidden="true" /> : null}
          {t(`tree.status.${step.status}`)}
        </span>
        <span className="tree-node-facts">
          {step.status === 'FROM_STORAGE'
            ? t('tree.perRun', { amount: amount(step.perRun) })
            : t('tree.runs', { done: crafted, total: step.runs, perRun: amount(step.perRun) })}
          {step.inMachines > 0 ? <> · {t('tree.inMachines', { amount: amount(step.inMachines) })}</> : null}
        </span>
        {step.children.length > 0 && isFolded ? <span className="tree-node-folded">+{step.children.length}</span> : null}
      </button>
      {step.children.length > 0 && !isFolded ? (
        <ul>
          {step.children.map((child, index) => (
            <StepNode key={`${child.id}-${index}`} step={child} path={`${path}/${index}`} assetVersion={assetVersion}
              folded={folded} onToggle={onToggle} />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

/** A viewport that can be dragged and zoomed (wheel, or the buttons on touch screens). */
function TreeCanvas({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const [view, setView] = useState({ x: 24, y: 24, scale: 1 });
  const drag = useRef<{ x: number; y: number; startX: number; startY: number } | null>(null);
  const viewport = useRef<HTMLDivElement>(null);

  const zoom = (factor: number, originX: number, originY: number) => setView((current) => {
    const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, current.scale * factor));
    const applied = scale / current.scale;
    return { scale, x: originX - (originX - current.x) * applied, y: originY - (originY - current.y) * applied };
  });
  const center = () => {
    const box = viewport.current?.getBoundingClientRect();
    return box ? [box.width / 2, box.height / 2] as const : [0, 0] as const;
  };

  const onWheel = (event: WheelEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    zoom(event.deltaY < 0 ? 1.15 : 1 / 1.15, event.clientX - box.left, event.clientY - box.top);
  };
  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest('button')) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { x: view.x, y: view.y, startX: event.clientX, startY: event.clientY };
  };
  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const start = drag.current;
    if (!start) return;
    setView((current) => ({ ...current, x: start.x + event.clientX - start.startX, y: start.y + event.clientY - start.startY }));
  };
  const onPointerUp = () => {
    drag.current = null;
  };

  return (
    <div className="tree-canvas">
      <div className="tree-zoom" role="group" aria-label={t('tree.zoom')}>
        <button type="button" className="button button-small" onClick={() => zoom(1.25, ...center())} aria-label={t('tree.zoomIn')}>+</button>
        <button type="button" className="button button-small" onClick={() => zoom(0.8, ...center())} aria-label={t('tree.zoomOut')}>−</button>
        <button type="button" className="button button-small" onClick={() => setView({ x: 24, y: 24, scale: 1 })}>{t('tree.reset')}</button>
      </div>
      <div ref={viewport} className="tree-viewport" onWheel={onWheel} onPointerDown={onPointerDown} onPointerMove={onPointerMove}
        onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
        <div className="tree-content" style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }}>
          {children}
        </div>
      </div>
    </div>
  );
}
