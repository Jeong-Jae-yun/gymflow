import { apiClient } from './client'
import type {
  AdminResourceCreateRequest,
  AdminResourceResponse,
  AdminResourceStatusUpdateRequest,
  AdminResourceUpdateRequest,
  AdminResourceUsageStatisticsResponse,
  ResourceImageResponse,
} from '@/types'

export interface AdminResourceStatisticsParams {
  from?: string
  to?: string
}

export const adminResourcesApi = {
  getResource: (resourceId: number) =>
    apiClient.get<AdminResourceResponse>(`/api/admin/resources/${resourceId}`).then((res) => res.data),

  create: (payload: AdminResourceCreateRequest) =>
    apiClient.post<AdminResourceResponse>('/api/admin/resources', payload).then((res) => res.data),

  update: (resourceId: number, payload: AdminResourceUpdateRequest) =>
    apiClient
      .put<AdminResourceResponse>(`/api/admin/resources/${resourceId}`, payload)
      .then((res) => res.data),

  changeStatus: (resourceId: number, payload: AdminResourceStatusUpdateRequest) =>
    apiClient
      .patch<AdminResourceResponse>(`/api/admin/resources/${resourceId}/status`, payload)
      .then((res) => res.data),

  uploadImage: (resourceId: number, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return apiClient
      .put<ResourceImageResponse>(`/api/admin/resources/${resourceId}/image`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((res) => res.data)
  },

  deleteImage: (resourceId: number) => apiClient.delete<void>(`/api/admin/resources/${resourceId}/image`),

  getStatistics: (resourceId: number, params?: AdminResourceStatisticsParams) =>
    apiClient
      .get<AdminResourceUsageStatisticsResponse>(`/api/admin/resources/${resourceId}/statistics`, {
        params,
      })
      .then((res) => res.data),
}
