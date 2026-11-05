import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, apiBlob } from '@/shared/api/client';
import type {
  CursorPage,
  DriverReview,
  ReviewDecision,
  ReviewQueueItem,
  ReviewStatus,
} from '@/shared/api/types';

/*
 * Driver review back office (UC-01, frontend-design.md §4.4). Opening a profile or a document is
 * audited by the server, so these queries are not refetched behind the reviewer's back.
 */

const PAGE_SIZE = 20;
const path = (driverId: string) => `/api/v1/admin/drivers/${encodeURIComponent(driverId)}`;

export const queueKey = ['admin', 'drivers'] as const;
export const reviewKey = (driverId: string) => ['admin', 'driver', driverId] as const;

/** Profiles in one review status, ordered by driver id, a page at a time. */
export function useReviewQueue(status: ReviewStatus) {
  return useInfiniteQuery({
    queryKey: [...queueKey, status],
    queryFn: ({ pageParam, signal }) =>
      api<CursorPage<ReviewQueueItem>>(
        `/api/v1/admin/drivers?status=${status}&size=${PAGE_SIZE}${
          pageParam ? `&after=${encodeURIComponent(pageParam)}` : ''
        }`,
        { signal },
      ),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor ?? null,
  });
}

export function useDriverReview(driverId: string) {
  return useQuery({
    queryKey: reviewKey(driverId),
    queryFn: ({ signal }) => api<DriverReview>(path(driverId), { signal }),
    staleTime: Infinity,
  });
}

export type Verdict = 'APPROVE' | 'REJECT' | 'REQUEST_CHANGES';

export type ReviewAction =
  | { kind: 'decide'; verdict: Verdict; profileVersion: number; reason?: string }
  | { kind: 'suspend'; reason: string }
  | { kind: 'reinstate'; reason: string };

/**
 * Records a decision. A decision names the profile version the reviewer looked at; if the driver
 * changed the profile meanwhile the server refuses it (INVALID_STATE), and either way the profile
 * is loaded again so the screen shows what is true now.
 */
export function useReviewAction(driverId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (action: ReviewAction) => {
      if (action.kind === 'decide') {
        const { verdict, profileVersion, reason } = action;
        return api<ReviewDecision>(`${path(driverId)}/decisions`, {
          method: 'POST',
          body: { verdict, profileVersion, reason: reason || undefined },
        });
      }
      return api<ReviewDecision>(`${path(driverId)}/${action.kind}`, {
        method: 'POST',
        body: { reason: action.reason },
      });
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: reviewKey(driverId) });
      await queryClient.invalidateQueries({ queryKey: queueKey });
    },
  });
}

/** One document's scan or photo, downloaded once per opening (each download is audited). */
export function useDocumentFile(driverId: string, documentId: string) {
  return useQuery({
    queryKey: ['admin', 'driver', driverId, 'document', documentId],
    queryFn: ({ signal }) =>
      apiBlob(`${path(driverId)}/documents/${encodeURIComponent(documentId)}/file`, signal),
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
}
