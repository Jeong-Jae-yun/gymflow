import { useQuery } from '@tanstack/react-query'
import { waitingQueuesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import { useAuth } from '@/context/useAuth'

const INDICATOR_PARAMS = { page: 0, size: 50 } as const

/**
 * Drives the small dot on the "대기열" nav item. Shares its TanStack Query
 * cache entry with the WaitingQueue list page (same query key), so once that
 * page has been visited this becomes a free cache read; the WebSocket
 * handler in WaitingQueuePage invalidates the same key on a PROMOTED event.
 */
export function useWaitingQueueLiveIndicator(): boolean {
  const { status } = useAuth()

  const query = useQuery({
    queryKey: queryKeys.waitingQueues.list(INDICATOR_PARAMS),
    queryFn: () => waitingQueuesApi.getMyWaitingQueues(INDICATOR_PARAMS),
    enabled: status === 'authenticated',
    staleTime: 30_000,
  })

  return query.data?.content.some((item) => item.status === 'PROMOTED') ?? false
}
