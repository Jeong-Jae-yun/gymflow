import { apiClient } from './client'
import type { LoginRequest, LoginResponse, UserResponse, UserSignUpRequest } from '@/types'

export const authApi = {
  login: (payload: LoginRequest) =>
    apiClient.post<LoginResponse>('/api/auth/login', payload).then((res) => res.data),

  signUp: (payload: UserSignUpRequest) =>
    apiClient.post<UserResponse>('/api/users/signup', payload).then((res) => res.data),

  getMe: () => apiClient.get<UserResponse>('/api/users/me').then((res) => res.data),
}
