import axios from 'axios'
import { API_BASE_URL } from '@/utils/env'
import { clearStoredToken, getStoredToken } from '@/utils/tokenStorage'
import { dispatchUnauthorized } from '@/utils/authEvents'
import { normalizeApiError } from './errors'

const LOGIN_PATH = '/api/auth/login'

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10_000,
  headers: {
    Accept: 'application/json',
  },
})

apiClient.interceptors.request.use((config) => {
  const token = getStoredToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const apiError = normalizeApiError(error)
    const requestUrl = axios.isAxiosError(error) ? (error.config?.url ?? '') : ''
    const isLoginRequest = requestUrl.includes(LOGIN_PATH)

    // A 401 on the login request itself just means "wrong credentials" — let the
    // form handle it. A 401 on any other request means the session has expired.
    if (apiError.status === 401 && !isLoginRequest) {
      clearStoredToken()
      dispatchUnauthorized()
    }

    return Promise.reject(apiError)
  },
)
