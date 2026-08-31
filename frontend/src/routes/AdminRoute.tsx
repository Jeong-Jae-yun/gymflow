import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '@/context/useAuth'
import { PageSpinner } from '@/components/ui'

/**
 * UX-level gate only. The backend's SecurityConfig independently enforces
 * `hasRole("ADMIN")` on every /api/admin/** call, so hiding admin routes here
 * is about not showing a USER a page full of 403s — it is not the security
 * boundary.
 */
export function AdminRoute() {
  const { status, isAdmin } = useAuth()

  if (status === 'loading') {
    return <PageSpinner label="권한을 확인하는 중입니다..." />
  }

  if (status === 'unauthenticated') {
    return <Navigate to="/login" replace />
  }

  if (!isAdmin) {
    return <Navigate to="/" replace />
  }

  return <Outlet />
}
