export type UserRole = 'USER' | 'ADMIN'
export type UserStatus = 'ACTIVE' | 'INACTIVE'

export interface UserResponse {
  id: number
  email: string
  name: string
  role: UserRole
  status: UserStatus
  createdAt: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
}

export interface UserSignUpRequest {
  email: string
  password: string
  name: string
}
