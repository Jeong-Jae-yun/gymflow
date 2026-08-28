import { apiClient } from './client'
import { toPageableParams, type PageParams } from './pageParams'
import type { SpringPage, WaitingQueueCreateRequest, WaitingQueueResponse } from '@/types'

export const waitingQueuesApi = {
  register: (payload: WaitingQueueCreateRequest) =>
    apiClient.post<WaitingQueueResponse>('/api/waiting-queues', payload).then((res) => res.data),

  getMyWaitingQueues: (params?: PageParams) =>
    apiClient
      .get<SpringPage<WaitingQueueResponse>>('/api/waiting-queues', { params: toPageableParams(params) })
      .then((res) => res.data),

  cancel: (waitingQueueId: number) => apiClient.delete<void>(`/api/waiting-queues/${waitingQueueId}`),
}
