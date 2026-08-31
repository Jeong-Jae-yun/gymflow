import { apiClient } from './client'
import { toPageableParams, type PageParams } from './pageParams'
import type {
  PopularResourceResponse,
  ResourceAvailabilityResponse,
  ResourceRankingResponse,
  ResourceResponse,
  SpringPage,
} from '@/types'

export const resourcesApi = {
  getResources: (params?: PageParams) =>
    apiClient
      .get<SpringPage<ResourceResponse>>('/api/resources', { params: toPageableParams(params) })
      .then((res) => res.data),

  getResourceDetail: (resourceId: number) =>
    apiClient.get<ResourceResponse>(`/api/resources/${resourceId}`).then((res) => res.data),

  getAvailability: (resourceId: number, date: string) =>
    apiClient
      .get<ResourceAvailabilityResponse>(`/api/resources/${resourceId}/availability`, { params: { date } })
      .then((res) => res.data),

  getPopularResources: (limit = 10) =>
    apiClient
      .get<PopularResourceResponse[]>('/api/resources/popular', { params: { limit } })
      .then((res) => res.data),

  getTopRankings: (limit = 10) =>
    apiClient
      .get<ResourceRankingResponse[]>('/api/resources/rankings', { params: { limit } })
      .then((res) => res.data),

  getResourceRanking: (resourceId: number) =>
    apiClient.get<ResourceRankingResponse>(`/api/resources/${resourceId}/ranking`).then((res) => res.data),
}
