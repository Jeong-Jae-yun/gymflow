import { useQuery } from '@tanstack/react-query'
import { resourcesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import type { PageParams } from '@/api/pageParams'

export function useResources(params?: PageParams) {
  return useQuery({
    queryKey: queryKeys.resources.list(params),
    queryFn: () => resourcesApi.getResources(params),
  })
}

export function useResourceDetail(resourceId: number) {
  return useQuery({
    queryKey: queryKeys.resources.detail(resourceId),
    queryFn: () => resourcesApi.getResourceDetail(resourceId),
    enabled: Number.isFinite(resourceId),
  })
}

export function useTopRankings(limit = 6) {
  return useQuery({
    queryKey: queryKeys.resources.rankings(limit),
    queryFn: () => resourcesApi.getTopRankings(limit),
  })
}
