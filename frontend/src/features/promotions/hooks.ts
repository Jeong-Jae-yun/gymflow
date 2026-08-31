import { useMutation, useQueryClient } from '@tanstack/react-query'
import { promotionsApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'

function useInvalidateAfterPromotionResponse() {
  const queryClient = useQueryClient()
  return () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.waitingQueues.all() })
    queryClient.invalidateQueries({ queryKey: queryKeys.reservations.all() })
  }
}

export function useAcceptPromotion() {
  const invalidate = useInvalidateAfterPromotionResponse()
  return useMutation({
    mutationFn: (promotionId: number) => promotionsApi.accept(promotionId),
    onSuccess: invalidate,
  })
}

export function useRejectPromotion() {
  const invalidate = useInvalidateAfterPromotionResponse()
  return useMutation({
    mutationFn: (promotionId: number) => promotionsApi.reject(promotionId),
    onSuccess: invalidate,
  })
}
