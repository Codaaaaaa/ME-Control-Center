import { useQuery } from '@tanstack/react-query';
import { fetchStatus } from '../api/status';

export const STATUS_REFRESH_MS = 5_000;

export function useStatusQuery() {
  return useQuery({
    queryKey: ['status'],
    queryFn: ({ signal }) => fetchStatus(signal),
    refetchInterval: STATUS_REFRESH_MS,
  });
}
