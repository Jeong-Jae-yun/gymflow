import { apiClient } from './client'
import type { FavoriteResponse } from '@/types'

export const favoritesApi = {
  list: () => apiClient.get<FavoriteResponse[]>('/api/favorites').then((res) => res.data),

  add: (resourceId: number) =>
    apiClient.post<FavoriteResponse>(`/api/favorites/${resourceId}`).then((res) => res.data),

  remove: (resourceId: number) => apiClient.delete<void>(`/api/favorites/${resourceId}`),
}
