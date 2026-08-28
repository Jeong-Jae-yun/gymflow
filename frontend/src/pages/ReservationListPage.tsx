import { useState } from 'react'
import { Link } from 'react-router-dom'
import { CalendarX, ChevronRight } from 'lucide-react'
import { Badge, EmptyState, ErrorState, PageHeader, Pagination, Skeleton, Button } from '@/components/ui'
import { ResourceLabel } from '@/components/ResourceLabel'
import { useMyReservations } from '@/features/reservations/hooks'
import { useResourcesByIds } from '@/features/resources/useResourcesByIds'
import { formatTimeRange, formatDate } from '@/utils/date'
import { reservationStatusLabels, reservationStatusTones } from '@/utils/labels'

const PAGE_SIZE = 10

export function ReservationListPage() {
  const [page, setPage] = useState(0)
  const reservationsQuery = useMyReservations({
    page,
    size: PAGE_SIZE,
    sort: { property: 'startAt', direction: 'DESC' },
  })

  const resourceMap = useResourcesByIds(
    (reservationsQuery.data?.content ?? []).map((reservation) => reservation.resourceId),
  )

  return (
    <div>
      <PageHeader
        title="내 예약"
        description="예약 내역을 확인하고 체크인, 연장, 취소를 관리하세요."
        actions={
          <Link to="/resources">
            <Button variant="secondary" size="sm">
              새 예약 만들기
            </Button>
          </Link>
        }
      />

      {reservationsQuery.isPending && (
        <div className="flex flex-col gap-3">
          {Array.from({ length: 5 }).map((_, index) => (
            <Skeleton key={index} className="h-16 w-full" />
          ))}
        </div>
      )}

      {reservationsQuery.isError && (
        <ErrorState error={reservationsQuery.error} onRetry={() => reservationsQuery.refetch()} />
      )}

      {reservationsQuery.data && reservationsQuery.data.content.length === 0 && (
        <EmptyState
          icon={CalendarX}
          title="예약 내역이 없습니다"
          description="Resource를 둘러보고 첫 예약을 만들어 보세요."
          action={
            <Link to="/resources">
              <Button variant="secondary" size="sm">
                Resource 둘러보기
              </Button>
            </Link>
          }
        />
      )}

      {reservationsQuery.data && reservationsQuery.data.content.length > 0 && (
        <>
          {/* Desktop table */}
          <div className="hidden overflow-hidden rounded-lg border border-neutral-200 bg-white md:block">
            <table className="w-full text-sm">
              <thead className="border-b border-neutral-200 bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Resource</th>
                  <th className="px-4 py-3">날짜</th>
                  <th className="px-4 py-3">시간</th>
                  <th className="px-4 py-3">상태</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {reservationsQuery.data.content.map((reservation) => (
                  <tr key={reservation.reservationId} className="hover:bg-neutral-50">
                    <td className="px-4 py-3">
                      <ResourceLabel resource={resourceMap.get(reservation.resourceId)} resourceId={reservation.resourceId} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDate(reservation.startAt)}</td>
                    <td className="px-4 py-3 text-neutral-600">
                      {formatTimeRange(reservation.startAt, reservation.endAt)}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={reservationStatusTones[reservation.status]}>
                        {reservationStatusLabels[reservation.status]}
                      </Badge>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Link
                        to={`/reservations/${reservation.reservationId}`}
                        className="inline-flex items-center text-neutral-400 hover:text-neutral-700"
                        aria-label="예약 상세 보기"
                      >
                        <ChevronRight className="size-4" aria-hidden="true" />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Mobile cards */}
          <div className="flex flex-col gap-3 md:hidden">
            {reservationsQuery.data.content.map((reservation) => (
              <Link
                key={reservation.reservationId}
                to={`/reservations/${reservation.reservationId}`}
                className="flex items-center justify-between gap-3 rounded-lg border border-neutral-200 bg-white p-4"
              >
                <div className="min-w-0">
                  <ResourceLabel resource={resourceMap.get(reservation.resourceId)} resourceId={reservation.resourceId} />
                  <p className="mt-1 text-sm text-neutral-500">
                    {formatDate(reservation.startAt)} · {formatTimeRange(reservation.startAt, reservation.endAt)}
                  </p>
                </div>
                <Badge tone={reservationStatusTones[reservation.status]}>
                  {reservationStatusLabels[reservation.status]}
                </Badge>
              </Link>
            ))}
          </div>

          <div className="mt-6">
            <Pagination page={page} totalPages={reservationsQuery.data.totalPages} onPageChange={setPage} />
          </div>
        </>
      )}
    </div>
  )
}
