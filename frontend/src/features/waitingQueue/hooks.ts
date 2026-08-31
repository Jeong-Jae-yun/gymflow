import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { waitingQueuesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import type { PageParams } from '@/api/pageParams'
import type { WaitingQueueCreateRequest } from '@/types'

export function useMyWaitingQueues(params?: PageParams) {
  return useQuery({
    queryKey: queryKeys.waitingQueues.list(params),
    queryFn: () => waitingQueuesApi.getMyWaitingQueues(params),
  })
}

export function useRegisterWaitingQueue() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: WaitingQueueCreateRequest) => waitingQueuesApi.register(payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.waitingQueues.all() }),
  })
}

export function useCancelWaitingQueue() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (waitingQueueId: number) => waitingQueuesApi.cancel(waitingQueueId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.waitingQueues.all() }),
  })
}
