import { describe, expect, it } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import { normalizeApiError } from './errors'

function makeAxiosError(status: number, data?: unknown): AxiosError {
  return new AxiosError('Request failed', String(status), undefined, undefined, {
    status,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
    data,
  })
}

describe('normalizeApiError', () => {
  it('uses the backend ErrorResponse message when present', () => {
    const error = makeAxiosError(404, { status: 404, message: '존재하지 않는 예약입니다.' })

    const result = normalizeApiError(error)

    expect(result).toEqual({ status: 404, message: '존재하지 않는 예약입니다.', isNetworkError: false })
  })

  it('treats a response with no payload as a network error when there is no response at all', () => {
    const error = new AxiosError('Network Error')

    const result = normalizeApiError(error)

    expect(result.isNetworkError).toBe(true)
    expect(result.status).toBe(0)
  })

  it('falls back to a generic Korean message for a 500 with an unexpected body shape', () => {
    const error = makeAxiosError(500, '<html>Internal Server Error</html>')

    const result = normalizeApiError(error)

    expect(result.status).toBe(500)
    expect(result.isNetworkError).toBe(false)
    expect(result.message).toContain('서버 오류')
  })

  it('falls back to a generic message for a non-axios error', () => {
    const result = normalizeApiError(new Error('boom'))

    expect(result.status).toBe(0)
    expect(result.isNetworkError).toBe(false)
    expect(result.message.length).toBeGreaterThan(0)
  })

  it('falls back to a generic message for a 400 with an unexpected body shape', () => {
    const error = makeAxiosError(400, {})

    const result = normalizeApiError(error)

    expect(result.status).toBe(400)
    expect(result.isNetworkError).toBe(false)
  })
})
