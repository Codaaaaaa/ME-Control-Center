import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { can, ROLES, type Member, type NetworkDetail, type Role } from '../api/networks';
import { useDeleteNetwork, useMe, useMemberMutations, useMembers, useNetwork, useRenameNetwork } from '../api/queries';
import { Badge, Card } from '../components/Card';
import { ConfirmButton } from '../components/ConfirmButton';
import { shortDimension } from '../components/network/ClaimNetworks';
import { STATE_TONE } from '../components/network/NetworkStatusCard';
import { ErrorNotice, FormError, LoadingNotice } from '../components/StateNotice';

export function NetworkSettingsPage() {
  const { t } = useTranslation();
  const { networkId = '' } = useParams();
  const detail = useNetwork(networkId);

  return (
    <>
      <div className="page-header">
        <Link to="/settings?tab=networks" className="back-link">
          ← {t('networkSettings.back')}
        </Link>
        <h1>
          {detail.data?.network.displayName ?? t('networks.title')}{' '}
          {detail.data ? (
            <Badge tone={STATE_TONE[detail.data.network.state]}>{t(`network.state.${detail.data.network.state}`)}</Badge>
          ) : null}
        </h1>
      </div>
      {detail.isPending ? (
        <LoadingNotice />
      ) : detail.isError ? (
        <ErrorNotice title={t('networks.title')} error={detail.error} onRetry={() => void detail.refetch()} />
      ) : (
        <div className="grid">
          <GeneralCard detail={detail.data} />
          <AnchorsCard detail={detail.data} />
          <MembersCard detail={detail.data} />
          <DangerCard detail={detail.data} />
        </div>
      )}
    </>
  );
}

function GeneralCard({ detail }: { detail: NetworkDetail }) {
  const { t } = useTranslation();
  const rename = useRenameNetwork(detail.network.id);
  const [name, setName] = useState(detail.network.displayName);
  useEffect(() => setName(detail.network.displayName), [detail.network.displayName]);
  const editable = can(detail, 'RENAME_NETWORK');

  return (
    <Card title={t('networkSettings.general')}>
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          rename.mutate(name.trim());
        }}
      >
        <label className="form-field">
          <span>{t('networkSettings.nameLabel')}</span>
          <input
            className="input"
            value={name}
            maxLength={48}
            readOnly={!editable}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        {editable ? (
          <button
            type="submit"
            className="button button-primary"
            disabled={!name.trim() || name.trim() === detail.network.displayName || rename.isPending}
          >
            {rename.isPending ? t('common.saving') : t('common.save')}
          </button>
        ) : null}
        <FormError error={rename.error} />
      </form>
      <p className="muted-text">
        {t('network.role')}: {t(`roles.${detail.network.role}`)}
        {detail.network.adminOverride ? ` · ${t('network.adminOverride')}` : ''}
      </p>
    </Card>
  );
}

function AnchorsCard({ detail }: { detail: NetworkDetail }) {
  const { t } = useTranslation();
  return (
    <Card title={t('networkSettings.anchors')}>
      <p className="muted-text">{t('networkSettings.anchorsHint')}</p>
      <ul className="list list-compact">
        {detail.anchors.map((anchor) => (
          <li key={anchor.key} className="list-row">
            <div className="list-main">
              <code>
                {shortDimension(anchor.dimension)} {anchor.x}, {anchor.y}, {anchor.z}
              </code>
              <div className="list-meta">{anchor.owner?.playerName ?? t('common.unknownPlayer')}</div>
            </div>
            <Badge tone={anchor.active === null ? 'neutral' : anchor.active ? 'success' : 'warning'}>
              {anchor.active === null
                ? t('networkSettings.anchorUnloaded')
                : anchor.active
                  ? t('networkSettings.anchorActive')
                  : t('networkSettings.anchorInactive')}
            </Badge>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function MembersCard({ detail }: { detail: NetworkDetail }) {
  const { t } = useTranslation();
  const id = detail.network.id;
  const members = useMembers(id);
  const mutations = useMemberMutations(id);
  const manage = can(detail, 'MANAGE_MEMBERS');
  const [player, setPlayer] = useState('');
  const [role, setRole] = useState<Role>('VIEWER');

  return (
    <Card className="card-wide" title={t('networkSettings.members')}>
      {manage ? <p className="muted-text">{t('networkSettings.roleHelp')}</p> : null}
      {members.isPending ? (
        <LoadingNotice />
      ) : members.isError ? (
        <ErrorNotice title={t('networkSettings.members')} error={members.error} onRetry={() => void members.refetch()} />
      ) : (
        <ul className="list">
          {members.data.map((member) => (
            <MemberRow key={member.user.playerUuid} member={member} manage={manage} mutations={mutations} />
          ))}
        </ul>
      )}
      {manage ? (
        <form
          className="inline-form add-member"
          onSubmit={(event) => {
            event.preventDefault();
            mutations.add.mutate({ player: player.trim(), role }, { onSuccess: () => setPlayer('') });
          }}
        >
          <label className="form-field">
            <span>{t('networkSettings.playerLabel')}</span>
            <input className="input" value={player} maxLength={36} onChange={(event) => setPlayer(event.target.value)} />
          </label>
          <label className="form-field">
            <span>{t('networkSettings.roleLabel')}</span>
            <RoleSelect value={role} onChange={setRole} />
          </label>
          <button type="submit" className="button button-primary" disabled={!player.trim() || mutations.add.isPending}>
            {t('networkSettings.add')}
          </button>
          <p className="muted-text form-hint">{t('networkSettings.membersHint')}</p>
          <FormError error={mutations.add.error} />
        </form>
      ) : null}
    </Card>
  );
}

function MemberRow({
  member,
  manage,
  mutations,
}: {
  member: Member;
  manage: boolean;
  mutations: ReturnType<typeof useMemberMutations>;
}) {
  const { t } = useTranslation();
  const me = useMe();
  const isMe = me.data?.user.playerUuid === member.user.playerUuid;
  const editable = manage && !member.primaryOwner;

  return (
    <li className="list-row">
      <div className="list-main">
        <div className="list-title">
          {member.user.playerName ?? t('common.unknownPlayer')}
          {isMe ? <Badge tone="accent">{t('networkSettings.you')}</Badge> : null}
          {member.primaryOwner ? <Badge tone="neutral">{t('networkSettings.primaryOwner')}</Badge> : null}
        </div>
      </div>
      <div className="list-actions">
        {editable ? (
          <RoleSelect
            value={member.role}
            onChange={(role) => mutations.changeRole.mutate({ playerUuid: member.user.playerUuid, role })}
            disabled={mutations.changeRole.isPending}
          />
        ) : (
          <span className="role-label">{t(`roles.${member.role}`)}</span>
        )}
        {editable && !isMe ? (
          <ConfirmButton
            label={t('networkSettings.remove')}
            onConfirm={() => mutations.remove.mutate(member.user.playerUuid)}
            disabled={mutations.remove.isPending}
          />
        ) : null}
      </div>
    </li>
  );
}

function RoleSelect({ value, onChange, disabled }: { value: Role; onChange: (role: Role) => void; disabled?: boolean }) {
  const { t } = useTranslation();
  return (
    <select className="input input-select" value={value} disabled={disabled} onChange={(event) => onChange(event.target.value as Role)}>
      {ROLES.map((role) => (
        <option key={role} value={role}>
          {t(`roles.${role}`)}
        </option>
      ))}
    </select>
  );
}

function DangerCard({ detail }: { detail: NetworkDetail }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const me = useMe();
  const remove = useDeleteNetwork();
  const leave = useMemberMutations(detail.network.id).remove;
  const isPrimaryOwner = me.data?.user.playerUuid === detail.network.owner.playerUuid;
  const canDelete = can(detail, 'DELETE_NETWORK');
  const canLeave = !isPrimaryOwner && !detail.network.adminOverride;

  if (!canDelete && !canLeave) return null;

  return (
    <Card className="card-wide card-danger" title={t('networkSettings.dangerZone')}>
      {canLeave && me.data ? (
        <div className="danger-row">
          <p className="muted-text">{t('networkSettings.leaveHint')}</p>
          <ConfirmButton
            label={t('networkSettings.leave')}
            disabled={leave.isPending}
            onConfirm={() => leave.mutate(me.data.user.playerUuid, { onSuccess: () => void navigate('/settings?tab=networks') })}
          />
        </div>
      ) : null}
      {canDelete ? (
        <div className="danger-row">
          <p className="muted-text">{t('networkSettings.deleteHint')}</p>
          <ConfirmButton
            label={t('networkSettings.delete')}
            disabled={remove.isPending}
            onConfirm={() => remove.mutate(detail.network.id, { onSuccess: () => void navigate('/settings?tab=networks') })}
          />
        </div>
      ) : null}
      <FormError error={remove.error ?? leave.error} />
    </Card>
  );
}
