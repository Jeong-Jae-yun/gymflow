import { apiClient } from './client'
import type { PromotionAcceptResponse } from '@/types'

export const promotionsApi = {
  accept: (promotionId: number) =>
    apiClient.post<PromotionAcceptResponse>(`/api/promotions/${promotionId}/accept`).then((res) => res.data),

  reject: (promotionId: number) => apiClient.post<void>(`/api/promotions/${promotionId}/reject`),
}
