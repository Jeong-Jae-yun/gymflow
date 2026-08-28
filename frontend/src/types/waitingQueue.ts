export type WaitingQueueStatus = 'WAITING' | 'PROMOTED' | 'CANCELLED'

export interface WaitingQueueResponse {
  waitingQueueId: number
  resourceId: number
  startAt: string
  endAt: string
  status: WaitingQueueStatus
  createdAt: string
  waitingRank: number | null
  promotionId: number | null
}

export interface WaitingQueueCreateRequest {
  resourceId: number
  startAt: string
  endAt: string
}
