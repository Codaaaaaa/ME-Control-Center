import { useTranslation } from 'react-i18next';
import type { NetworkSummary } from '../../api/networks';
import { useSelectedNetwork } from '../../stores/selectedNetwork';

/** Network selector, shown when the player can access more than one network. */
export function NetworkPicker({ networks, selectedId }: { networks: NetworkSummary[]; selectedId: string }) {
  const { t } = useTranslation();
  const select = useSelectedNetwork((state) => state.select);
  if (networks.length < 2) {
    return null;
  }
  return (
    <label className="select-field">
      <span>{t('overview.network')}</span>
      <select className="input" value={selectedId} onChange={(event) => select(event.target.value)}>
        {networks.map((network) => (
          <option key={network.id} value={network.id}>
            {network.displayName}
          </option>
        ))}
      </select>
    </label>
  );
}
