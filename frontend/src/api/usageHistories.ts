import { apiClient } from './client'
import type { PageResponse, UsageHistoryResponse, UsageStatisticsResponse } from '@/types'

export interface UsageHistoryQueryParams {
  page?: number
  size?: number
  from?: string
  to?: string
}

export const usageHistoriesApi = {
  getMyUsageHistories: (params?: UsageHistoryQueryParams) =>
    apiClient
      .get<PageResponse<UsageHistoryResponse>>('/api/usage-histories', { params })
      .then((res) => res.data),

  getMyStatistics: (params?: { from?: string; to?: string }) =>
    apiClient
      .get<UsageStatisticsResponse>('/api/usage-histories/statistics', { params })
      .then((res) => res.data),
}
