export type PromotionStatus = 'OFFERED' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED'

export interface PromotionAcceptResponse {
  promotionId: number
  promotionStatus: PromotionStatus
  reservationId: number
  checkInDeadline: string
}

/** Payload pushed over STOMP to /user/queue/waiting-queue. */
export interface WaitingQueuePromotedEvent {
  promotionId: number
  waitingQueueId: number
  userId: number
  resourceId: number
  startAt: string
  endAt: string
  promotedAt: string
}
