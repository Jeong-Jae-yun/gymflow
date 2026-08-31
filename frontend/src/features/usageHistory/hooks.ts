import { useQuery } from '@tanstack/react-query'
import { usageHistoriesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import type { UsageHistoryQueryParams } from '@/api/usageHistories'

export function useMyUsageHistories(params?: UsageHistoryQueryParams) {
  return useQuery({
    queryKey: queryKeys.usageHistories.list(params),
    queryFn: () => usageHistoriesApi.getMyUsageHistories(params),
  })
}

export function useMyUsageStatistics(from?: string, to?: string) {
  return useQuery({
    queryKey: queryKeys.usageHistories.statistics(from, to),
    queryFn: () => usageHistoriesApi.getMyStatistics({ from, to }),
  })
}
