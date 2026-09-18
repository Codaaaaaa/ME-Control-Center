import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Candidate } from '../../api/networks';
import { useCandidates, useClaimNetwork, useMe } from '../../api/queries';
import { useSelectedNetwork } from '../../stores/selectedNetwork';
import { Badge, Card } from '../Card';
import { ErrorNotice, FormError, LoadingNotice } from '../StateNotice';

/** Lists loaded, unenrolled ME networks the player may claim, and enrolls one. */
export function ClaimNetworks() {
  const { t } = useTranslation();
  const me = useMe();
  const candidates = useCandidates();

  return (
    <Card
      className="card-wide"
      title={t('claim.title')}
      badge={
        <button
          type="button"
          className="button button-quiet button-small"
          onClick={() => void candidates.refetch()}
          disabled={candidates.isFetching}
        >
          {t('claim.refresh')}
        </button>
      }
    >
      <p className="muted-text">
        {t('claim.subtitle')} {me.data?.serverAdmin && me.data.adminOverride ? t('claim.adminSubtitle') : null}
      </p>
      {candidates.isPending ? (
        <LoadingNotice />
      ) : candidates.isError ? (
        <ErrorNotice
          title={t('claim.title')}
          error={candidates.error}
          onRetry={() => void candidates.refetch()}
          retrying={candidates.isFetching}
        />
      ) : candidates.data.length === 0 ? (
        <div className="empty-inline">
          <strong>{t('claim.empty')}</strong>
          <p>{t('claim.emptyHelp')}</p>
        </div>
      ) : (
        <ul className="list">
          {candidates.data.map((candidate) => (
            <CandidateRow key={candidate.key} candidate={candidate} />
          ))}
        </ul>
      )}
    </Card>
  );
}

function CandidateRow({ candidate }: { candidate: Candidate }) {
  const { t } = useTranslation();
  const claim = useClaimNetwork();
  const select = useSelectedNetwork((state) => state.select);
  const [name, setName] = useState('');
  const first = candidate.anchors[0];
  const placedBy = first?.owner?.playerName;

  return (
    <li className="list-row candidate">
      <div className="list-main">
        <div className="list-title">
          <code>
            {candidate.anchors.map((anchor) => `${shortDimension(anchor.dimension)} ${anchor.x}, ${anchor.y}, ${anchor.z}`).join(' · ')}
          </code>
        </div>
        <div className="list-meta">
          <span>{t('claim.nodes', { count: candidate.nodeCount })}</span>
          {!candidate.powered ? <Badge tone="danger">{t('claim.unpowered')}</Badge> : null}
          {candidate.ownedByYou ? null : (
            <Badge tone="warning">{placedBy ? t('claim.placedBy', { name: placedBy }) : t('claim.notYours')}</Badge>
          )}
        </div>
      </div>
      <form
        className="inline-form"
        onSubmit={(event) => {
          event.preventDefault();
          if (name.trim()) {
            claim.mutate(
              { key: candidate.key, name: name.trim() },
              { onSuccess: (detail) => select(detail.network.id) },
            );
          }
        }}
      >
        <label className="visually-hidden" htmlFor={`claim-${candidate.key}`}>
          {t('claim.nameLabel')}
        </label>
        <input
          id={`claim-${candidate.key}`}
          className="input"
          value={name}
          maxLength={48}
          placeholder={t('claim.namePlaceholder')}
          onChange={(event) => setName(event.target.value)}
        />
        <button type="submit" className="button button-primary" disabled={!name.trim() || claim.isPending}>
          {claim.isPending ? t('claim.submitting') : t('claim.submit')}
        </button>
        <FormError error={claim.error} />
      </form>
    </li>
  );
}

export function shortDimension(dimension: string): string {
  return dimension.startsWith('minecraft:') ? dimension.slice('minecraft:'.length) : dimension;
}
