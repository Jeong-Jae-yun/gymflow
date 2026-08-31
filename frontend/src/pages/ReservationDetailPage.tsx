import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, CalendarClock, LogIn, LogOut, RefreshCw, XCircle } from 'lucide-react'
import { Badge, Button, Card, ErrorState, Modal, PageSpinner, Select } from '@/components/ui'
import {
  useCancelReservation,
  useCheckInReservation,
  useCheckOutReservation,
  useExtendReservation,
  useReservationDetail,
} from '@/features/reservations/hooks'
import { useResourceDetail } from '@/features/resources/hooks'
import { useToast } from '@/context/useToast'
import { formatDateTime, minutesBetween, formatDuration } from '@/utils/date'
import { getErrorMessage } from '@/utils/getErrorMessage'
import { cancelReasonLabels, reservationStatusLabels, reservationStatusTones, resourceTypeLabels } from '@/utils/labels'
import { USER_SELECTABLE_CANCEL_REASONS } from '@/types'
import type { CancelReason } from '@/types'

const MAX_EXTENSION_COUNT = 2
const CHECK_IN_WINDOW_MINUTES = 5

export function ReservationDetailPage() {
  const params = useParams<{ reservationId: string }>()
  const reservationId = Number(params.reservationId)
  const navigate = useNavigate()
  const { showToast } = useToast()

  const reservationQuery = useReservationDetail(reservationId)
  const resourceQuery = useResourceDetail(reservationQuery.data?.resourceId ?? Number.NaN)

  const cancelReservation = useCancelReservation()
  const checkInReservation = useCheckInReservation()
  const checkOutReservation = useCheckOutReservation()
  const extendReservation = useExtendReservation()

  const [cancelDialogOpen, setCancelDialogOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState<CancelReason>('PERSONAL_REASON')
  const [extendDialogOpen, setExtendDialogOpen] = useState(false)
  const [extendDuration, setExtendDuration] = useState<number | null>(null)

  const reservation = reservationQuery.data
  const policy = resourceQuery.data?.reservationPolicy ?? null

  const canCheckInNow = useMemo(() => {
    if (!reservation) return false
    const now = Date.now()
    const start = new Date(reservation.startAt).getTime()
    return now >= start - CHECK_IN_WINDOW_MINUTES * 60_000 && now <= start
  }, [reservation])

  const extendOptions = useMemo(() => {
    if (!reservation || !policy) return []
    const currentDuration = minutesBetween(reservation.startAt, reservation.endAt)
    const options: number[] = []
    for (let value = policy.slotDuration; currentDuration + value <= policy.maxDuration; value += policy.slotDuration) {
      options.push(value)
    }
    return options
  }, [reservation, policy])

  if (reservationQuery.isPending) {
    return <PageSpinner label="예약 정보를 불러오는 중입니다..." />
  }

  if (reservationQuery.isError || !reservation) {
    return <ErrorState error={reservationQuery.error} onRetry={() => reservationQuery.refetch()} />
  }

  function handleCancel() {
    cancelReservation.mutate(
      { reservationId, payload: { cancelReason } },
      {
        onSuccess: () => {
          setCancelDialogOpen(false)
          showToast({ tone: 'success', title: '예약이 취소되었습니다' })
        },
        onError: (error) => {
          showToast({ tone: 'danger', title: '예약 취소 실패', description: getErrorMessage(error) })
        },
      },
    )
  }

  function handleCheckIn() {
    checkInReservation.mutate(reservationId, {
      onSuccess: () => showToast({ tone: 'success', title: '체크인 완료되었습니다' }),
      onError: (error) => showToast({ tone: 'danger', title: '체크인 실패', description: getErrorMessage(error) }),
    })
  }

  function handleCheckOut() {
    checkOutReservation.mutate(reservationId, {
      onSuccess: () => showToast({ tone: 'success', title: '체크아웃 완료되었습니다' }),
      onError: (error) => showToast({ tone: 'danger', title: '체크아웃 실패', description: getErrorMessage(error) }),
    })
  }

  function handleExtend() {
    if (!extendDuration) return
    extendReservation.mutate(
      { reservationId, payload: { duration: extendDuration } },
      {
        onSuccess: () => {
          setExtendDialogOpen(false)
          setExtendDuration(null)
          showToast({ tone: 'success', title: '예약이 연장되었습니다' })
        },
        onError: (error) => {
          showToast({ tone: 'danger', title: '연장 실패', description: getErrorMessage(error) })
        },
      },
    )
  }

  return (
    <div className="mx-auto max-w-2xl">
      <button
        type="button"
        onClick={() => navigate('/reservations')}
        className="mb-4 inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        내 예약 목록
      </button>

      <Card className="p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            {resourceQuery.data && (
              <p className="text-xs font-medium text-neutral-500">
                {resourceTypeLabels[resourceQuery.data.resourceType]}
              </p>
            )}
            <Link
              to={`/resources/${reservation.resourceId}`}
              className="mt-0.5 inline-block text-lg font-semibold text-neutral-900 hover:text-brand-700"
            >
              {resourceQuery.data?.name ?? `Resource #${reservation.resourceId}`}
            </Link>
          </div>
          <Badge tone={reservationStatusTones[reservation.status]}>
            {reservationStatusLabels[reservation.status]}
          </Badge>
        </div>

        <div className="mt-6 flex items-center gap-3 rounded-md bg-neutral-50 p-4 text-sm">
          <CalendarClock className="size-4 text-neutral-400" aria-hidden="true" />
          <div>
            <p className="font-medium text-neutral-900">{formatDateTime(reservation.startAt)}</p>
            <p className="text-neutral-500">
              {formatDuration(minutesBetween(reservation.startAt, reservation.endAt))} 이용
              {reservation.extensionCount > 0 && ` · ${reservation.extensionCount}회 연장됨`}
            </p>
          </div>
        </div>

        {reservation.cancelReason && (
          <p className="mt-4 text-sm text-neutral-500">
            취소 사유: {cancelReasonLabels[reservation.cancelReason]}
          </p>
        )}

        {(reservation.status === 'CONFIRMED' || reservation.status === 'CHECKED_IN') && (
          <div className="mt-6 flex flex-wrap gap-2">
            {reservation.status === 'CONFIRMED' && (
              <>
                <Button
                  leftIcon={<LogIn className="size-4" aria-hidden="true" />}
                  onClick={handleCheckIn}
                  loading={checkInReservation.isPending}
                  disabled={!canCheckInNow}
                  title={!canCheckInNow ? '체크인은 시작 5분 전부터 가능합니다.' : undefined}
                >
                  체크인
                </Button>
                <Button
                  variant="secondary"
                  leftIcon={<XCircle className="size-4" aria-hidden="true" />}
                  onClick={() => setCancelDialogOpen(true)}
                >
                  예약 취소
                </Button>
              </>
            )}
            {reservation.status === 'CHECKED_IN' && (
              <>
                <Button
                  leftIcon={<LogOut className="size-4" aria-hidden="true" />}
                  onClick={handleCheckOut}
                  loading={checkOutReservation.isPending}
                >
                  체크아웃
                </Button>
                <Button
                  variant="secondary"
                  leftIcon={<RefreshCw className="size-4" aria-hidden="true" />}
                  onClick={() => setExtendDialogOpen(true)}
                  disabled={reservation.extensionCount >= MAX_EXTENSION_COUNT || extendOptions.length === 0}
                  title={
                    reservation.extensionCount >= MAX_EXTENSION_COUNT
                      ? '최대 연장 횟수를 모두 사용했습니다.'
                      : extendOptions.length === 0
                        ? '최대 이용 시간에 도달했습니다.'
                        : undefined
                  }
                >
                  연장하기
                </Button>
              </>
            )}
          </div>
        )}
      </Card>

      <Modal
        open={cancelDialogOpen}
        onClose={() => setCancelDialogOpen(false)}
        title="예약을 취소할까요?"
        description="취소된 예약은 되돌릴 수 없습니다."
        footer={
          <>
            <Button variant="secondary" onClick={() => setCancelDialogOpen(false)}>
              닫기
            </Button>
            <Button variant="destructive" onClick={handleCancel} loading={cancelReservation.isPending}>
              취소하기
            </Button>
          </>
        }
      >
        <label className="mb-1.5 block text-sm font-medium text-neutral-800" htmlFor="cancel-reason">
          취소 사유
        </label>
        <Select id="cancel-reason" value={cancelReason} onChange={(event) => setCancelReason(event.target.value as CancelReason)}>
          {USER_SELECTABLE_CANCEL_REASONS.map((reason) => (
            <option key={reason} value={reason}>
              {cancelReasonLabels[reason]}
            </option>
          ))}
        </Select>
      </Modal>

      <Modal
        open={extendDialogOpen}
        onClose={() => setExtendDialogOpen(false)}
        title="예약 연장"
        description="현재 이용 시간에 추가할 시간을 선택하세요."
        footer={
          <>
            <Button variant="secondary" onClick={() => setExtendDialogOpen(false)}>
              닫기
            </Button>
            <Button onClick={handleExtend} loading={extendReservation.isPending} disabled={!extendDuration}>
              연장하기
            </Button>
          </>
        }
      >
        <label className="mb-1.5 block text-sm font-medium text-neutral-800" htmlFor="extend-duration">
          연장 시간
        </label>
        <Select
          id="extend-duration"
          value={extendDuration ?? ''}
          onChange={(event) => setExtendDuration(Number(event.target.value) || null)}
        >
          <option value="" disabled>
            연장 시간을 선택하세요
          </option>
          {extendOptions.map((option) => (
            <option key={option} value={option}>
              {formatDuration(option)}
            </option>
          ))}
        </Select>
      </Modal>
    </div>
  )
}
