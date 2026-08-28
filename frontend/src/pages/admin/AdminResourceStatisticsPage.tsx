import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Badge, ErrorState, Input, PageHeader, PageSpinner, Skeleton } from '@/components/ui'
import { useAdminResource, useAdminResourceStatistics } from '@/features/admin/hooks'
import { formatDuration, toBackendDateTime } from '@/utils/date'
import { resourceStatusLabels, resourceStatusTones, resourceTypeLabels } from '@/utils/labels'

export function AdminResourceStatisticsPage() {
  const params = useParams<{ resourceId: string }>()
  const resourceId = Number(params.resourceId)
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  const dateRangeError = fromDate && toDate && fromDate > toDate ? '조회 시작일은 종료일보다 이전이어야 합니다.' : null
  const from = !dateRangeError && fromDate ? toBackendDateTime(new Date(`${fromDate}T00:00:00`)) : undefined
  const to = !dateRangeError && toDate ? toBackendDateTime(new Date(`${toDate}T23:59:59`)) : undefined

  const resourceQuery = useAdminResource(resourceId)
  const statisticsQuery = useAdminResourceStatistics(resourceId, { from, to })

  if (resourceQuery.isPending) {
    return <PageSpinner label="Resource 정보를 불러오는 중입니다..." />
  }

  if (resourceQuery.isError) {
    return <ErrorState error={resourceQuery.error} onRetry={() => resourceQuery.refetch()} />
  }

  const resource = resourceQuery.data

  return (
    <div className="mx-auto max-w-2xl">
      <Link
        to="/admin/resources"
        className="mb-4 inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Resource 목록
      </Link>

      <PageHeader
        title={`${resource.name} 통계`}
        description={
          <span className="flex items-center gap-2">
            {resourceTypeLabels[resource.type]}
            <Badge tone={resourceStatusTones[resource.status]}>{resourceStatusLabels[resource.status]}</Badge>
          </span>
        }
      />

      <div className="mb-6 flex flex-col gap-3 rounded-lg border border-neutral-200 bg-white p-4 sm:flex-row sm:items-end">
        <div className="flex-1">
          <label htmlFor="from-date" className="mb-1 block text-xs font-medium text-neutral-600">
            조회 시작일
          </label>
          <Input id="from-date" type="date" value={fromDate} onChange={(event) => setFromDate(event.target.value)} />
        </div>
        <div className="flex-1">
          <label htmlFor="to-date" className="mb-1 block text-xs font-medium text-neutral-600">
            조회 종료일
          </label>
          <Input id="to-date" type="date" value={toDate} onChange={(event) => setToDate(event.target.value)} />
        </div>
      </div>

      {dateRangeError && (
        <p role="alert" className="mb-6 rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {dateRangeError}
        </p>
      )}

      {!dateRangeError && statisticsQuery.isPending && (
        <div className="grid grid-cols-2 gap-4">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {!dateRangeError && statisticsQuery.isError && (
        <ErrorState error={statisticsQuery.error} onRetry={() => statisticsQuery.refetch()} />
      )}

      {!dateRangeError && statisticsQuery.data && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div className="rounded-lg border border-neutral-200 bg-white p-5">
            <p className="text-sm text-neutral-500">총 이용 횟수</p>
            <p className="mt-1 text-2xl font-semibold text-neutral-900">{statisticsQuery.data.totalUsageCount}회</p>
          </div>
          <div className="rounded-lg border border-neutral-200 bg-white p-5">
            <p className="text-sm text-neutral-500">총 이용 시간</p>
            <p className="mt-1 text-2xl font-semibold text-neutral-900">
              {formatDuration(statisticsQuery.data.totalUsageMinutes)}
            </p>
          </div>
        </div>
      )}
    </div>
  )
}
