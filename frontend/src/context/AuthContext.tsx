import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import { clearStoredToken, getStoredToken, setStoredToken } from '@/utils/tokenStorage'
import { UNAUTHORIZED_EVENT } from '@/utils/authEvents'
import { AuthContext } from './auth-context'
import type { AuthContextValue, AuthStatus } from './auth-context'
import type { LoginRequest, UserSignUpRequest } from '@/types'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [hasToken, setHasToken] = useState(() => getStoredToken() !== null)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const meQuery = useQuery({
    queryKey: queryKeys.me(),
    queryFn: authApi.getMe,
    enabled: hasToken,
    retry: false,
    staleTime: 5 * 60 * 1000,
  })

  useEffect(() => {
    function handleUnauthorized() {
      setHasToken(false)
      queryClient.removeQueries({ queryKey: queryKeys.me() })
      navigate('/login', { replace: true })
    }
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  }, [navigate, queryClient])

  const login = useCallback(
    async (payload: LoginRequest) => {
      const response = await authApi.login(payload)
      setStoredToken(response.accessToken)
      setHasToken(true)
      await queryClient.invalidateQueries({ queryKey: queryKeys.me() })
    },
    [queryClient],
  )

  const signUp = useCallback(async (payload: UserSignUpRequest) => authApi.signUp(payload), [])

  const logout = useCallback(() => {
    clearStoredToken()
    setHasToken(false)
    queryClient.clear()
    navigate('/login', { replace: true })
  }, [navigate, queryClient])

  const status: AuthStatus = !hasToken
    ? 'unauthenticated'
    : meQuery.isPending
      ? 'loading'
      : meQuery.isError
        ? 'unauthenticated'
        : 'authenticated'

  const value = useMemo<AuthContextValue>(
    () => ({
      user: meQuery.data ?? null,
      status,
      isAdmin: meQuery.data?.role === 'ADMIN',
      login,
      signUp,
      logout,
    }),
    [meQuery.data, status, login, signUp, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
