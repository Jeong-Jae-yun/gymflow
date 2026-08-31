import type {
  CancelReason,
  PromotionStatus,
  ReservationStatus,
  ResourceStatus,
  ResourceType,
  UserRole,
  WaitingQueueStatus,
} from '@/types'

/**
 * Central mapping from backend enum values to user-facing Korean labels.
 * The raw enum value is always what's sent back to the API; these labels are
 * presentation-only and must never be sent in requests.
 */

export type BadgeTone = 'brand' | 'success' | 'warning' | 'danger' | 'neutral' | 'accent'

export const resourceTypeLabels: Record<ResourceType, string> = {
  MACHINE: '머신',
  PT_ROOM: 'PT룸',
  LOCKER: '락커',
  STRETCH_ZONE: '스트레칭존',
  SAUNA: '사우나',
  SHOWER_ROOM: '샤워실',
}

export const resourceStatusLabels: Record<ResourceStatus, string> = {
  ACTIVE: '이용 가능',
  INACTIVE: '운영 중지',
  MAINTENANCE: '점검 중',
}

export const resourceStatusTones: Record<ResourceStatus, BadgeTone> = {
  ACTIVE: 'success',
  INACTIVE: 'neutral',
  MAINTENANCE: 'warning',
}

export const reservationStatusLabels: Record<ReservationStatus, string> = {
  CONFIRMED: '예약 확정',
  CHECKED_IN: '체크인 완료',
  COMPLETED: '이용 완료',
  CANCELLED: '취소됨',
  NO_SHOW: '노쇼 처리됨',
}

export const reservationStatusTones: Record<ReservationStatus, BadgeTone> = {
  CONFIRMED: 'brand',
  CHECKED_IN: 'success',
  COMPLETED: 'neutral',
  CANCELLED: 'neutral',
  NO_SHOW: 'danger',
}

export const cancelReasonLabels: Record<CancelReason, string> = {
  PERSONAL_REASON: '개인 사정',
  SCHEDULE_CHANGE: '일정 변경',
  WRONG_RESERVATION: '잘못된 예약',
  OTHER: '기타',
  PROMOTION_CHECKIN_TIMEOUT: '승급 체크인 시간 초과',
}

export const waitingQueueStatusLabels: Record<WaitingQueueStatus, string> = {
  WAITING: '대기 중',
  PROMOTED: '처리 완료',
  CANCELLED: '취소됨',
}

export const waitingQueueStatusTones: Record<WaitingQueueStatus, BadgeTone> = {
  WAITING: 'brand',
  PROMOTED: 'accent',
  CANCELLED: 'neutral',
}

export const promotionStatusLabels: Record<PromotionStatus, string> = {
  OFFERED: '응답 대기 중',
  ACCEPTED: '수락됨',
  REJECTED: '거절됨',
  EXPIRED: '만료됨',
}

export const userRoleLabels: Record<UserRole, string> = {
  USER: '일반 회원',
  ADMIN: '관리자',
}
