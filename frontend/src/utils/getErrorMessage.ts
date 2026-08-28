const DEFAULT_FALLBACK = '요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.'

/**
 * apiClient's response interceptor always rejects with an ApiError-shaped
 * object ({ status, message, isNetworkError }), so call sites can pull a
 * display-ready message out of any caught error without re-checking axios.
 */
export function getErrorMessage(error: unknown, fallback: string = DEFAULT_FALLBACK): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const message = (error as { message: unknown }).message
    if (typeof message === 'string' && message.length > 0) {
      return message
    }
  }
  return fallback
}
