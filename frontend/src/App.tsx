import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from '@/context/AuthContext'
import { WaitingQueueSocketProvider } from '@/context/WaitingQueueSocketContext'
import { ProtectedRoute } from '@/routes/ProtectedRoute'
import { AdminRoute } from '@/routes/AdminRoute'
import { PublicOnlyRoute } from '@/routes/PublicOnlyRoute'
import { AuthLayout } from '@/layouts/AuthLayout'
import { AppLayout } from '@/layouts/AppLayout'
import { AdminLayout } from '@/layouts/AdminLayout'
import { PageSpinner } from '@/components/ui'

const LoginPage = lazy(() => import('@/pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const SignUpPage = lazy(() => import('@/pages/SignUpPage').then((m) => ({ default: m.SignUpPage })))
const DashboardPage = lazy(() => import('@/pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const ResourceListPage = lazy(() => import('@/pages/ResourceListPage').then((m) => ({ default: m.ResourceListPage })))
const ResourceDetailPage = lazy(() =>
  import('@/pages/ResourceDetailPage').then((m) => ({ default: m.ResourceDetailPage })),
)
const ReservationCreatePage = lazy(() =>
  import('@/pages/ReservationCreatePage').then((m) => ({ default: m.ReservationCreatePage })),
)
const ReservationListPage = lazy(() =>
  import('@/pages/ReservationListPage').then((m) => ({ default: m.ReservationListPage })),
)
const ReservationDetailPage = lazy(() =>
  import('@/pages/ReservationDetailPage').then((m) => ({ default: m.ReservationDetailPage })),
)
const FavoritesPage = lazy(() => import('@/pages/FavoritesPage').then((m) => ({ default: m.FavoritesPage })))
const WaitingQueuePage = lazy(() => import('@/pages/WaitingQueuePage').then((m) => ({ default: m.WaitingQueuePage })))
const UsageHistoryPage = lazy(() =>
  import('@/pages/UsageHistoryPage').then((m) => ({ default: m.UsageHistoryPage })),
)
const ProfilePage = lazy(() => import('@/pages/ProfilePage').then((m) => ({ default: m.ProfilePage })))
const AdminResourceListPage = lazy(() =>
  import('@/pages/admin/AdminResourceListPage').then((m) => ({ default: m.AdminResourceListPage })),
)
const AdminResourceFormPage = lazy(() =>
  import('@/pages/admin/AdminResourceFormPage').then((m) => ({ default: m.AdminResourceFormPage })),
)
const AdminResourceStatisticsPage = lazy(() =>
  import('@/pages/admin/AdminResourceStatisticsPage').then((m) => ({ default: m.AdminResourceStatisticsPage })),
)
const NotFoundPage = lazy(() => import('@/pages/NotFoundPage').then((m) => ({ default: m.NotFoundPage })))

export function App() {
  return (
    <AuthProvider>
      <WaitingQueueSocketProvider>
        <Suspense fallback={<PageSpinner />}>
          <Routes>
            <Route element={<PublicOnlyRoute />}>
              <Route element={<AuthLayout />}>
                <Route path="login" element={<LoginPage />} />
                <Route path="signup" element={<SignUpPage />} />
              </Route>
            </Route>

            <Route element={<ProtectedRoute />}>
              <Route element={<AppLayout />}>
                <Route index element={<DashboardPage />} />
                <Route path="resources" element={<ResourceListPage />} />
                <Route path="resources/:resourceId" element={<ResourceDetailPage />} />
                <Route path="resources/:resourceId/reserve" element={<ReservationCreatePage />} />
                <Route path="reservations" element={<ReservationListPage />} />
                <Route path="reservations/:reservationId" element={<ReservationDetailPage />} />
                <Route path="favorites" element={<FavoritesPage />} />
                <Route path="waiting-queue" element={<WaitingQueuePage />} />
                <Route path="usage-history" element={<UsageHistoryPage />} />
                <Route path="profile" element={<ProfilePage />} />
              </Route>
            </Route>

            <Route path="admin" element={<AdminRoute />}>
              <Route element={<AdminLayout />}>
                <Route index element={<Navigate to="resources" replace />} />
                <Route path="resources" element={<AdminResourceListPage />} />
                <Route path="resources/new" element={<AdminResourceFormPage />} />
                <Route path="resources/:resourceId/edit" element={<AdminResourceFormPage />} />
                <Route path="resources/:resourceId/statistics" element={<AdminResourceStatisticsPage />} />
              </Route>
            </Route>

            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </Suspense>
      </WaitingQueueSocketProvider>
    </AuthProvider>
  )
}
