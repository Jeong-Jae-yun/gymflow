import { createContext } from 'react'
import type { LoginRequest, UserResponse, UserSignUpRequest } from '@/types'

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated'

export interface AuthContextValue {
  user: UserResponse | null
  status: AuthStatus
  isAdmin: boolean
  login: (payload: LoginRequest) => Promise<void>
  signUp: (payload: UserSignUpRequest) => Promise<UserResponse>
  logout: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
