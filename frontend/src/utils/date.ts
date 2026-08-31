import { format, parseISO, differenceInMinutes, isValid } from 'date-fns'
import { ko } from 'date-fns/locale'

/**
 * The backend has no timezone information anywhere in its contract — every
 * timestamp is a Java `LocalDateTime` serialized as an offset-less ISO string
 * (e.g. "2026-08-28T10:15:00"). We treat every such string as wall-clock time
 * in the browser's local timezone and never apply UTC conversion, so a
 * reservation created for "14:00" always reads back as "14:00".
 */

const BACKEND_LOCAL_DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"

/** Formats a JS Date as the LocalDateTime string the backend expects in request bodies/params. */
export function toBackendDateTime(date: Date): string {
  return format(date, BACKEND_LOCAL_DATE_TIME_PATTERN)
}

function safeParseISO(value: string): Date | null {
  const parsed = parseISO(value)
  return isValid(parsed) ? parsed : null
}

export function formatDateTime(value: string): string {
  const date = safeParseISO(value)
  if (!date) return '-'
  return format(date, 'yyyy.MM.dd (EEE) HH:mm', { locale: ko })
}

export function formatDate(value: string): string {
  const date = safeParseISO(value)
  if (!date) return '-'
  return format(date, 'yyyy.MM.dd (EEE)', { locale: ko })
}

export function formatTime(value: string): string {
  const date = safeParseISO(value)
  if (!date) return '-'
  return format(date, 'HH:mm', { locale: ko })
}

export function formatTimeRange(startAt: string, endAt: string): string {
  const start = safeParseISO(startAt)
  const end = safeParseISO(endAt)
  if (!start || !end) return '-'
  return `${format(start, 'HH:mm')} - ${format(end, 'HH:mm')}`
}

/** Minutes between two backend LocalDateTime strings. */
export function minutesBetween(startAt: string, endAt: string): number {
  const start = safeParseISO(startAt)
  const end = safeParseISO(endAt)
  if (!start || !end) return 0
  return differenceInMinutes(end, start)
}

export function formatDuration(totalMinutes: number): string {
  if (totalMinutes <= 0) return '0분'
  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (hours === 0) return `${minutes}분`
  if (minutes === 0) return `${hours}시간`
  return `${hours}시간 ${minutes}분`
}

export function isPast(value: string): boolean {
  const date = safeParseISO(value)
  if (!date) return false
  return date.getTime() < Date.now()
}
