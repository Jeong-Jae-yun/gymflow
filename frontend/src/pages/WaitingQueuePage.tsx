import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Hourglass, Wifi, WifiOff } from 'lucide-react'
import { Badge, Button, Card, ConfirmDialog, EmptyState, ErrorState, PageHeader, Pagination, Skeleton } from '@/components/ui'
import { ResourceLabel } from '@/components/ResourceLabel'
import { useCancelWaitingQueue, useMyWaitingQueues } from '@/features/waitingQueue/hooks'
import { useAcceptPromotion, useRejectPromotion } from '@/features/promotions/hooks'
import { useResourcesByIds } from '@/features/resources/useResourcesByIds'
import { useWaitingQueueSocket } from '@/context/useWaitingQueueSocket'
import { useToast } from '@/context/useToast'
import { formatDateTime, formatTimeRange } from '@/utils/date'
import { getErrorMessage } from '@/utils/getErrorMessage'
import { waitingQueueStatusLabels, waitingQueueStatusTones } from '@/utils/labels'
import type { WaitingQueueResponse } from '@/types'

const PAGE_SIZE = 10

export function WaitingQueuePage() {
  const [page, setPage] = useState(0)
  const [cancelTarget, setCancelTarget] = useState<WaitingQueueResponse | null>(null)
  const [rejectTarget, setRejectTarget] = useState<WaitingQueueResponse | null>(null)
  const navigate = useNavigate()
  const { showToast } = useToast()
  const { connected } = useWaitingQueueSocket()

  const waitingQueuesQuery = useMyWaitingQueues({
    page,
    size: PAGE_SIZE,
    sort: { property: 'createdAt', direction: 'DESC' },
  })
  const resourceMap = useResourcesByIds(
    (waitingQueuesQuery.data?.content ?? []).map((item) => item.resourceId),
  )

  const cancelWaitingQueue = useCancelWaitingQueue()
  const acceptPromotion = useAcceptPromotion()
  const rejectPromotion = useRejectPromotion()

  function handleCancelConfirm() {
    if (!cancelTarget) return
    cancelWaitingQueue.mutate(cancelTarget.waitingQueueId, {
      onSuccess: () => {
        setCancelTarget(null)
        showToast({ tone: 'success', title: '대기열에서 나갔습니다' })
      },
      onError: (error) => {
        showToast({ tone: 'danger', title: '대기열 취소 실패', description: getErrorMessage(error) })
      },
    })
  }

  function handleAccept(item: WaitingQueueResponse) {
    if (!item.promotionId) return
    acceptPromotion.mutate(item.promotionId, {
      onSuccess: (response) => {
        showToast({
          tone: 'success',
          title: '승급을 수락했습니다',
          description: `${formatDateTime(response.checkInDeadline)}까지 체크인해야 예약이 유지됩니다.`,
        })
        navigate(`/reservations/${response.reservationId}`)
      },
      onError: (error) => {
        showToast({ tone: 'danger', title: '승급 수락 실패', description: getErrorMessage(error) })
      },
    })
  }

  function handleRejectConfirm() {
    if (!rejectTarget?.promotionId) return
    rejectPromotion.mutate(rejectTarget.promotionId, {
      onSuccess: () => {
        setRejectTarget(null)
        showToast({ tone: 'info', title: '승급을 거절했습니다' })
      },
      onError: (error) => {
        showToast({ tone: 'danger', title: '승급 거절 실패', description: getErrorMessage(error) })
      },
    })
  }

  return (
    <div>
      <PageHeader
        title="대기열"
        description="예약이 마감된 시간대에 등록한 대기열과 승급 현황을 확인하세요."
        actions={
          <span className="flex items-center gap-1.5 text-xs text-neutral-400">
            {connected ? (
              <>
                <Wifi className="size-3.5 text-success-600" aria-hidden="true" />
                실시간 연결됨
              </>
            ) : (
              <>
                <WifiOff className="size-3.5" aria-hidden="true" />
                실시간 연결 대기 중
              </>
            )}
          </span>
        }
      />

      {waitingQueuesQuery.isPending && (
        <div className="flex flex-col gap-3">
          {Array.from({ length: 4 }).map((_, index) => (
            <Skeleton key={index} className="h-20 w-full" />
          ))}
        </div>
      )}

      {waitingQueuesQuery.isError && (
        <ErrorState error={waitingQueuesQuery.error} onRetry={() => waitingQueuesQuery.refetch()} />
      )}

      {waitingQueuesQuery.data && waitingQueuesQuery.data.content.length === 0 && (
        <EmptyState
          icon={Hourglass}
          title="대기 중인 항목이 없습니다"
          description="예약이 마감된 시간대에서 대기열 등록을 시도하면 이곳에 표시됩니다."
        />
      )}

      {waitingQueuesQuery.data && waitingQueuesQuery.data.content.length > 0 && (
        <>
          <div className="flex flex-col gap-3">
            {waitingQueuesQuery.data.content.map((item) => {
              const isPromoted = item.status === 'PROMOTED' && item.promotionId
              return (
                <Card
                  key={item.waitingQueueId}
                  className={isPromoted ? 'border-accent-400 bg-accent-300/10 p-4' : 'p-4'}
                >
                  <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                      <ResourceLabel resource={resourceMap.get(item.resourceId)} resourceId={item.resourceId} />
                      <p className="mt-1 text-sm text-neutral-500">
                        {formatDateTime(item.startAt)} · {formatTimeRange(item.startAt, item.endAt)}
                      </p>
                    </div>
                    <div className="flex items-center gap-3">
                      {item.status === 'WAITING' && item.waitingRank !== null && (
                        <span className="text-sm text-neutral-500">
                          현재 <span className="font-semibold text-neutral-900">{item.waitingRank}</span>번째 대기
                        </span>
                      )}
                      <Badge tone={waitingQueueStatusTones[item.status]}>
                        {waitingQueueStatusLabels[item.status]}
                      </Badge>
                    </div>
                  </div>

                  {isPromoted && (
                    <div className="mt-3 flex flex-col gap-2 border-t border-accent-400/30 pt-3 sm:flex-row">
                      <p className="flex-1 text-sm text-accent-800">
                        자리가 생겼습니다! 수락하면 짧은 시간 안에 체크인해야 예약이 유지됩니다.
                      </p>
                      <div className="flex gap-2">
                        <Button variant="secondary" size="sm" onClick={() => setRejectTarget(item)}>
                          거절
                        </Button>
                        <Button
                          size="sm"
                          onClick={() => handleAccept(item)}
                          loading={acceptPromotion.isPending}
                        >
                          수락
                        </Button>
                      </div>
                    </div>
                  )}

                  {item.status === 'WAITING' && (
                    <div className="mt-3 flex justify-end border-t border-neutral-100 pt-3">
                      <Button variant="ghost" size="sm" onClick={() => setCancelTarget(item)}>
                        대기열 나가기
                      </Button>
                    </div>
                  )}
                </Card>
              )
            })}
          </div>

          <div className="mt-6">
            <Pagination page={page} totalPages={waitingQueuesQuery.data.totalPages} onPageChange={setPage} />
          </div>
        </>
      )}

      <ConfirmDialog
        open={!!cancelTarget}
        title="대기열에서 나가시겠습니까?"
        description="나가면 대기 순번이 사라지며 다시 등록해야 합니다."
        confirmLabel="나가기"
        loading={cancelWaitingQueue.isPending}
        onConfirm={handleCancelConfirm}
        onCancel={() => setCancelTarget(null)}
      />

      <ConfirmDialog
        open={!!rejectTarget}
        title="승급을 거절하시겠습니까?"
        description="거절하면 다음 순번의 대기자에게 기회가 넘어가며 되돌릴 수 없습니다."
        confirmLabel="거절하기"
        loading={rejectPromotion.isPending}
        onConfirm={handleRejectConfirm}
        onCancel={() => setRejectTarget(null)}
      />
    </div>
  )
}
