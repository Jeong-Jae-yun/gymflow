import { describe, expect, it } from 'vitest'
import { formatDuration, formatTimeRange, minutesBetween, toBackendDateTime } from './date'

describe('toBackendDateTime', () => {
  it('formats without a timezone offset, matching the backend LocalDateTime shape', () => {
    const date = new Date(2026, 7, 28, 14, 5, 0) // August is month index 7

    expect(toBackendDateTime(date)).toBe('2026-08-28T14:05:00')
  })
})

describe('minutesBetween', () => {
  it('computes whole minutes between two backend LocalDateTime strings', () => {
    expect(minutesBetween('2026-08-28T10:00:00', '2026-08-28T11:30:00')).toBe(90)
  })

  it('returns 0 for unparsable input instead of throwing', () => {
    expect(minutesBetween('not-a-date', '2026-08-28T11:30:00')).toBe(0)
  })
})

describe('formatDuration', () => {
  it('renders minutes-only durations under an hour', () => {
    expect(formatDuration(45)).toBe('45분')
  })

  it('renders whole-hour durations without a minutes suffix', () => {
    expect(formatDuration(120)).toBe('2시간')
  })

  it('renders combined hours and minutes', () => {
    expect(formatDuration(90)).toBe('1시간 30분')
  })

  it('treats zero or negative durations as 0분', () => {
    expect(formatDuration(0)).toBe('0분')
    expect(formatDuration(-10)).toBe('0분')
  })
})

describe('formatTimeRange', () => {
  it('renders HH:mm - HH:mm', () => {
    expect(formatTimeRange('2026-08-28T09:00:00', '2026-08-28T10:30:00')).toBe('09:00 - 10:30')
  })
})
