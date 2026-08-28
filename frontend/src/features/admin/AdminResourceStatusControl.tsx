import { useState } from 'react'
import { Badge, Button, ConfirmDialog, Select } from '@/components/ui'
import { useChangeResourceStatus } from './hooks'
import { useToast } from '@/context/useToast'
import { getErrorMessage } from '@/utils/getErrorMessage'
import { resourceStatusLabels, resourceStatusTones } from '@/utils/labels'
import type { ResourceStatus } from '@/types'

const STATUS_OPTIONS: ResourceStatus[] = ['ACTIVE', 'INACTIVE', 'MAINTENANCE']

interface AdminResourceStatusControlProps {
  resourceId: number
  currentStatus: ResourceStatus
}

export function AdminResourceStatusControl({ resourceId, currentStatus }: AdminResourceStatusControlProps) {
  const [pendingStatus, setPendingStatus] = useState<ResourceStatus>(currentStatus)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const { showToast } = useToast()
  const changeStatus = useChangeResourceStatus()

  function handleApply() {
    if (pendingStatus === currentStatus) return
    setConfirmOpen(true)
  }

  function handleConfirm() {
    changeStatus.mutate(
      { resourceId, payload: { status: pendingStatus } },
      {
        onSuccess: () => {
          setConfirmOpen(false)
          showToast({ tone: 'success', title: '상태가 변경되었습니다' })
        },
        onError: (error) => {
          showToast({ tone: 'danger', title: '상태 변경 실패', description: getErrorMessage(error) })
        },
      },
    )
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      <Badge tone={resourceStatusTones[currentStatus]}>{resourceStatusLabels[currentStatus]}</Badge>
      <div className="flex items-center gap-2">
        <Select
          value={pendingStatus}
          onChange={(event) => setPendingStatus(event.target.value as ResourceStatus)}
          className="w-40"
        >
          {STATUS_OPTIONS.map((status) => (
            <option key={status} value={status}>
              {resourceStatusLabels[status]}
            </option>
          ))}
        </Select>
        <Button type="button" variant="secondary" size="sm" onClick={handleApply} disabled={pendingStatus === currentStatus}>
          상태 변경
        </Button>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Resource 상태를 변경할까요?"
        description={`${resourceStatusLabels[currentStatus]} → ${resourceStatusLabels[pendingStatus]}로 변경합니다. 이용 중이거나 예약된 시간대가 있으면 변경이 거부될 수 있습니다.`}
        confirmLabel="변경"
        confirmVariant="primary"
        loading={changeStatus.isPending}
        onConfirm={handleConfirm}
        onCancel={() => {
          setConfirmOpen(false)
          setPendingStatus(currentStatus)
        }}
      />
    </div>
  )
}
