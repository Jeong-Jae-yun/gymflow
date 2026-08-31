import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '@/context/useAuth'

/** Keeps an already-logged-in user off /login and /signup. */
export function PublicOnlyRoute() {
  const { status } = useAuth()

  if (status === 'authenticated') {
    return <Navigate to="/" replace />
  }

  return <Outlet />
}
