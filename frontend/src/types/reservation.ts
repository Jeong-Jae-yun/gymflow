export type ReservationStatus = 'CONFIRMED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW'

export type CancelReason =
  | 'PERSONAL_REASON'
  | 'SCHEDULE_CHANGE'
  | 'WRONG_RESERVATION'
  | 'OTHER'
  | 'PROMOTION_CHECKIN_TIMEOUT'

/** Reasons a user may select when cancelling; PROMOTION_CHECKIN_TIMEOUT is system-assigned only. */
export const USER_SELECTABLE_CANCEL_REASONS: CancelReason[] = [
  'PERSONAL_REASON',
  'SCHEDULE_CHANGE',
  'WRONG_RESERVATION',
  'OTHER',
]

export interface ReservationResponse {
  reservationBatchId: string
  reservationId: number
  resourceId: number
  startAt: string
  endAt: string
  status: ReservationStatus
  cancelReason: CancelReason | null
  extensionCount: number
}

export interface ReservationCreateRequest {
  resourceId: number
  startAt: string
  duration: number
}

export interface CancelReservationRequest {
  cancelReason: CancelReason
}

export interface ReservationExtensionRequest {
  duration: number
}
