import { useQueries } from '@tanstack/react-query'
import { resourcesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import type { ResourceResponse } from '@/types'

/**
 * Reservation/WaitingQueue/UsageHistory list responses only carry
 * `resourceId` — the backend has no "with resource details" projection for
 * those endpoints. We batch-fetch the distinct resource ids that appear on
 * the current page via GET /api/resources/{id} (shared cache with the rest of
 * the app) so list rows can show a resource name instead of a bare id. This
 * is a documented Frontend Integration Limitation (N+1), bounded by page size.
 */
export function useResourcesByIds(resourceIds: number[]): Map<number, ResourceResponse> {
  const uniqueIds = Array.from(new Set(resourceIds))

  const queries = useQueries({
    queries: uniqueIds.map((id) => ({
      queryKey: queryKeys.resources.detail(id),
      queryFn: () => resourcesApi.getResourceDetail(id),
    })),
  })

  const map = new Map<number, ResourceResponse>()
  queries.forEach((query, index) => {
    if (query.data) map.set(uniqueIds[index], query.data)
  })
  return map
}
