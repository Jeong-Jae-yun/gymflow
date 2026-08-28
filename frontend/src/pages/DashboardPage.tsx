import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { CalendarClock, Heart, Hourglass, TrendingUp } from 'lucide-react'
import { Badge, Button, Card, Skeleton } from '@/components/ui'
import { ResourceLabel } from '@/components/ResourceLabel'
import { useAuth } from '@/context/useAuth'
import { useMyReservations } from '@/features/reservations/hooks'
import { useMyWaitingQueues } from '@/features/waitingQueue/hooks'
import { useFavorites } from '@/features/favorites/hooks'
import { useMyUsageStatistics } from '@/features/usageHistory/hooks'
import { useResourcesByIds } from '@/features/resources/useResourcesByIds'
import { formatDateTime, formatDuration, formatTimeRange, toBackendDateTime } from '@/utils/date'
import { reservationStatusLabels, reservationStatusTones, waitingQueueStatusLabels, waitingQueueStatusTones } from '@/utils/labels'

const UPCOMING_STATUSES = new Set(['CONFIRMED', 'CHECKED_IN'])

function startOfMonth(): Date {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), 1)
}

export function DashboardPage() {
  const { user } = useAuth()

  const reservationsQuery = useMyReservations({ page: 0, size: 5, sort: { property: 'startAt', direction: 'ASC' } })
  const waitingQueuesQuery = useMyWaitingQueues({ page: 0, size: 5, sort: { property: 'createdAt', direction: 'DESC' } })
  const favoritesQuery = useFavorites()
  const monthlyStatsQuery = useMyUsageStatistics(toBackendDateTime(startOfMonth()), toBackendDateTime(new Date()))

  const upcomingReservations = useMemo(
    () => (reservationsQuery.data?.content ?? []).filter((r) => UPCOMING_STATUSES.has(r.status)).slice(0, 3),
    [reservationsQuery.data],
  )
  const activeWaitingQueues = useMemo(
    () => (waitingQueuesQuery.data?.content ?? []).filter((w) => w.status !== 'CANCELLED').slice(0, 3),
    [waitingQueuesQuery.data],
  )

  const resourceMap = useResourcesByIds([
    ...upcomingReservations.map((r) => r.resourceId),
    ...activeWaitingQueues.map((w) => w.resourceId),
  ])

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-xl font-semibold tracking-tight text-neutral-900 sm:text-2xl">
          {user?.name}님, 안녕하세요
        </h1>
        <p className="mt-1 text-sm text-neutral-500">현재 예약 및 대기 현황을 한눈에 확인하세요.</p>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card className="p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-neutral-700">
              <CalendarClock className="size-4 text-brand-600" aria-hidden="true" />
              다가오는 예약
            </h2>
            <Link to="/reservations" className="text-xs font-medium text-brand-600 hover:text-brand-700">
              전체 보기
            </Link>
          </div>

          {reservationsQuery.isPending && <Skeleton className="h-24 w-full" />}

          {reservationsQuery.isSuccess && upcomingReservations.length === 0 && (
            <EmptyRow message="예정된 예약이 없습니다." ctaLabel="예약하러 가기" ctaTo="/resources" />
          )}

          <div className="flex flex-col gap-2">
            {upcomingReservations.map((reservation) => (
              <Link
                key={reservation.reservationId}
                to={`/reservations/${reservation.reservationId}`}
                className="flex items-center justify-between gap-3 rounded-md border border-neutral-100 px-3 py-2 hover:bg-neutral-50"
              >
                <div className="min-w-0">
                  <ResourceLabel resource={resourceMap.get(reservation.resourceId)} resourceId={reservation.resourceId} />
                  <p className="mt-0.5 text-xs text-neutral-500">
                    {formatDateTime(reservation.startAt)} ({formatTimeRange(reservation.startAt, reservation.endAt)})
                  </p>
                </div>
                <Badge tone={reservationStatusTones[reservation.status]}>
                  {reservationStatusLabels[reservation.status]}
                </Badge>
              </Link>
            ))}
          </div>
        </Card>

        <Card className="p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-neutral-700">
              <Hourglass className="size-4 text-accent-600" aria-hidden="true" />
              대기열 현황
            </h2>
            <Link to="/waiting-queue" className="text-xs font-medium text-brand-600 hover:text-brand-700">
              전체 보기
            </Link>
          </div>

          {waitingQueuesQuery.isPending && <Skeleton className="h-24 w-full" />}

          {waitingQueuesQuery.isSuccess && activeWaitingQueues.length === 0 && (
            <EmptyRow message="대기 중인 항목이 없습니다." />
          )}

          <div className="flex flex-col gap-2">
            {activeWaitingQueues.map((item) => (
              <Link
                key={item.waitingQueueId}
                to="/waiting-queue"
                className="flex items-center justify-between gap-3 rounded-md border border-neutral-100 px-3 py-2 hover:bg-neutral-50"
              >
                <div className="min-w-0">
                  <ResourceLabel resource={resourceMap.get(item.resourceId)} resourceId={item.resourceId} />
                  <p className="mt-0.5 text-xs text-neutral-500">{formatTimeRange(item.startAt, item.endAt)}</p>
                </div>
                <Badge tone={waitingQueueStatusTones[item.status]}>{waitingQueueStatusLabels[item.status]}</Badge>
              </Link>
            ))}
          </div>
        </Card>

        <Card className="p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-neutral-700">
              <Heart className="size-4 text-danger-500" aria-hidden="true" />
              즐겨찾기
            </h2>
            <Link to="/favorites" className="text-xs font-medium text-brand-600 hover:text-brand-700">
              전체 보기
            </Link>
          </div>
          {favoritesQuery.isPending && <Skeleton className="h-10 w-full" />}
          {favoritesQuery.isSuccess && (
            <p className="text-sm text-neutral-600">
              총 <span className="font-semibold text-neutral-900">{favoritesQuery.data.length}개</span>의 Resource를
              즐겨찾기에 등록했습니다.
            </p>
          )}
        </Card>

        <Card className="p-5">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="flex items-center gap-1.5 text-sm font-semibold text-neutral-700">
              <TrendingUp className="size-4 text-success-600" aria-hidden="true" />
              이번 달 이용 현황
            </h2>
            <Link to="/usage-history" className="text-xs font-medium text-brand-600 hover:text-brand-700">
              전체 보기
            </Link>
          </div>
          {monthlyStatsQuery.isPending && <Skeleton className="h-10 w-full" />}
          {monthlyStatsQuery.isSuccess && (
            <p className="text-sm text-neutral-600">
              이번 달 <span className="font-semibold text-neutral-900">{monthlyStatsQuery.data.totalUsageCount}회</span>{' '}
              이용, 총{' '}
              <span className="font-semibold text-neutral-900">
                {formatDuration(monthlyStatsQuery.data.totalUsageMinutes)}
              </span>
            </p>
          )}
        </Card>
      </div>
    </div>
  )
}

function EmptyRow({ message, ctaLabel, ctaTo }: { message: string; ctaLabel?: string; ctaTo?: string }) {
  return (
    <div className="flex flex-col items-start gap-2 rounded-md border border-dashed border-neutral-200 px-3 py-4 text-sm text-neutral-500">
      {message}
      {ctaLabel && ctaTo && (
        <Link to={ctaTo}>
          <Button variant="secondary" size="sm">
            {ctaLabel}
          </Button>
        </Link>
      )}
    </div>
  )
}
