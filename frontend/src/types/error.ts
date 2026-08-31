/**
 * Backend GlobalExceptionHandler response shape: { status, message }.
 */
export interface ErrorResponse {
  status: number
  message: string
}

export interface ApiError {
  status: number
  message: string
  /** true when the request never reached the backend (network/offline). */
  isNetworkError: boolean
}
