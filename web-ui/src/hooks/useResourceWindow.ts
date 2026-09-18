import { keepPreviousData, useQueries, useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { fetchResourcePage, type Resource, type ResourceQuery } from '../api/resources';

export const PAGE_SIZE = 120;
/** Matches the server's default snapshot age, so the terminal stays roughly live. */
export const REFRESH_MS = 5_000;

export interface ResourceWindow {
  total: number;
  snapshotId?: string;
  capturedAt?: string;
  assetVersion?: string;
  /** Loaded resource at an absolute index, or undefined while its page is loading. */
  itemAt: (index: number) => Resource | undefined;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => void;
}

/**
 * Loads only the pages covering the visible range. The first page is polled and defines the snapshot;
 * further pages are pinned to that snapshot so paging stays consistent while storage changes.
 */
export function useResourceWindow(query: ResourceQuery | null, start: number, end: number): ResourceWindow {
  const head = useQuery({
    queryKey: ['resources', query, 'head'] as const,
    queryFn: ({ signal }) => fetchResourcePage(query!, 0, PAGE_SIZE, undefined, signal),
    enabled: query !== null,
    refetchInterval: REFRESH_MS,
    placeholderData: keepPreviousData,
  });

  const snapshotId = head.data?.snapshotId;
  const pageIndexes = useMemo(() => {
    if (!head.data) return [];
    const first = Math.floor(start / PAGE_SIZE);
    const last = Math.floor(Math.max(start, Math.min(end, head.data.total) - 1) / PAGE_SIZE);
    const indexes: number[] = [];
    for (let page = first; page <= last; page++) {
      if (page > 0) indexes.push(page);
    }
    return indexes;
  }, [head.data, start, end]);

  const pages = useQueries({
    queries: pageIndexes.map((page) => ({
      queryKey: ['resources', query, snapshotId, page] as const,
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        fetchResourcePage(query!, page * PAGE_SIZE, PAGE_SIZE, snapshotId, signal),
      enabled: query !== null && snapshotId !== undefined,
      staleTime: REFRESH_MS,
    })),
  });

  const loaded = useMemo(() => {
    const byOffset = new Map<number, Resource[]>();
    if (head.data) {
      byOffset.set(0, head.data.entries);
    }
    pages.forEach((page) => {
      if (page.data) {
        byOffset.set(page.data.offset, page.data.entries);
      }
    });
    return byOffset;
  }, [head.data, pages]);

  return {
    total: head.data?.total ?? 0,
    snapshotId,
    capturedAt: head.data?.capturedAt,
    assetVersion: head.data?.assetVersion,
    itemAt: (index: number) => loaded.get(Math.floor(index / PAGE_SIZE) * PAGE_SIZE)?.[index % PAGE_SIZE],
    isPending: head.isPending,
    isError: head.isError,
    error: head.error,
    refetch: () => void head.refetch(),
  };
}
