import axios from 'axios'
import type { ApiError, ErrorResponse } from '@/types'

const GENERIC_FALLBACK_MESSAGE = '알 수 없는 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'
const NETWORK_FALLBACK_MESSAGE = '서버에 연결할 수 없습니다. 네트워크 상태 또는 서버 실행 여부를 확인해 주세요.'
const SERVER_FALLBACK_MESSAGE = '일시적인 서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * Normalizes any error thrown by Axios into the backend's actual ErrorResponse
 * shape ({ status, message }) wherever possible, falling back to
 * frontend-friendly Korean copy when the backend is unreachable or returns an
 * unexpected payload (e.g. a 500 from an upstream proxy with an HTML body).
 */
export function normalizeApiError(error: unknown): ApiError {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return { status: 0, message: NETWORK_FALLBACK_MESSAGE, isNetworkError: true }
    }

    const { status, data } = error.response
    const payload = data as Partial<ErrorResponse> | undefined
    if (payload && typeof payload.message === 'string') {
      return { status, message: payload.message, isNetworkError: false }
    }

    if (status >= 500) {
      return { status, message: SERVER_FALLBACK_MESSAGE, isNetworkError: false }
    }

    return { status, message: GENERIC_FALLBACK_MESSAGE, isNetworkError: false }
  }

  return { status: 0, message: GENERIC_FALLBACK_MESSAGE, isNetworkError: false }
}
