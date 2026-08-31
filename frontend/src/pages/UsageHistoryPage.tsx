import { useMemo, useState } from 'react'
import { History } from 'lucide-react'
import { EmptyState, ErrorState, Input, PageHeader, Pagination, Skeleton } from '@/components/ui'
import { ResourceLabel } from '@/components/ResourceLabel'
import { useMyUsageHistories, useMyUsageStatistics } from '@/features/usageHistory/hooks'
import { useResourcesByIds } from '@/features/resources/useResourcesByIds'
import { formatDateTime, formatDuration, formatTimeRange, toBackendDateTime } from '@/utils/date'
import { resourceTypeLabels } from '@/utils/labels'

const PAGE_SIZE = 10

export function UsageHistoryPage() {
  const [page, setPage] = useState(0)
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  const dateRangeError = fromDate && toDate && fromDate > toDate ? '조회 시작일은 종료일보다 이전이어야 합니다.' : null

  const from = !dateRangeError && fromDate ? toBackendDateTime(new Date(`${fromDate}T00:00:00`)) : undefined
  const to = !dateRangeError && toDate ? toBackendDateTime(new Date(`${toDate}T23:59:59`)) : undefined

  const statisticsQuery = useMyUsageStatistics(from, to)
  const historiesQuery = useMyUsageHistories({ page, size: PAGE_SIZE, from, to })

  const resourceMap = useResourcesByIds(
    (historiesQuery.data?.content ?? []).map((history) => history.resourceId),
  )

  const maxResourceMinutes = useMemo(
    () => Math.max(1, ...(statisticsQuery.data?.resourceUsages.map((usage) => usage.totalUsageMinutes) ?? [1])),
    [statisticsQuery.data],
  )

  return (
    <div>
      <PageHeader title="이용 내역" description="지금까지 이용한 Resource와 통계를 확인하세요." />

      <div className="mb-6 flex flex-col gap-3 rounded-lg border border-neutral-200 bg-white p-4 sm:flex-row sm:items-end">
        <div className="flex-1">
          <label htmlFor="from-date" className="mb-1 block text-xs font-medium text-neutral-600">
            조회 시작일
          </label>
          <Input
            id="from-date"
            type="date"
            value={fromDate}
            onChange={(event) => {
              setFromDate(event.target.value)
              setPage(0)
            }}
          />
        </div>
        <div className="flex-1">
          <label htmlFor="to-date" className="mb-1 block text-xs font-medium text-neutral-600">
            조회 종료일
          </label>
          <Input
            id="to-date"
            type="date"
            value={toDate}
            onChange={(event) => {
              setToDate(event.target.value)
              setPage(0)
            }}
          />
        </div>
      </div>

      {dateRangeError && (
        <p role="alert" className="mb-6 rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
          {dateRangeError}
        </p>
      )}

      {!dateRangeError && statisticsQuery.data && statisticsQuery.data.totalUsageCount > 0 && (
        <section className="mb-8 grid grid-cols-1 gap-4 lg:grid-cols-3">
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
          <div className="rounded-lg border border-neutral-200 bg-white p-5 lg:col-span-1">
            <p className="mb-3 text-sm text-neutral-500">Resource별 이용 현황</p>
            <div className="flex flex-col gap-3">
              {statisticsQuery.data.resourceUsages.slice(0, 4).map((usage) => (
                <div key={usage.resourceId}>
                  <div className="flex items-center justify-between text-xs text-neutral-600">
                    <span className="truncate font-medium">{usage.resourceName}</span>
                    <span>{usage.usageCount}회</span>
                  </div>
                  <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-neutral-100">
                    <div
                      className="h-full rounded-full bg-brand-500"
                      style={{ width: `${(usage.totalUsageMinutes / maxResourceMinutes) * 100}%` }}
                    />
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>
      )}

      {historiesQuery.isPending && (
        <div className="flex flex-col gap-3">
          {Array.from({ length: 5 }).map((_, index) => (
            <Skeleton key={index} className="h-16 w-full" />
          ))}
        </div>
      )}

      {historiesQuery.isError && <ErrorState error={historiesQuery.error} onRetry={() => historiesQuery.refetch()} />}

      {historiesQuery.data && historiesQuery.data.content.length === 0 && (
        <EmptyState icon={History} title="이용 내역이 없습니다" description="체크아웃을 완료한 예약이 이곳에 기록됩니다." />
      )}

      {historiesQuery.data && historiesQuery.data.content.length > 0 && (
        <>
          <div className="hidden overflow-hidden rounded-lg border border-neutral-200 bg-white md:block">
            <table className="w-full text-sm">
              <thead className="border-b border-neutral-200 bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
                <tr>
                  <th className="px-4 py-3">Resource</th>
                  <th className="px-4 py-3">이용 시간</th>
                  <th className="px-4 py-3">소요 시간</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {historiesQuery.data.content.map((history) => (
                  <tr key={history.id}>
                    <td className="px-4 py-3">
                      <ResourceLabel resource={resourceMap.get(history.resourceId)} resourceId={history.resourceId} />
                    </td>
                    <td className="px-4 py-3 text-neutral-600">
                      {formatDateTime(history.startedAt)} ({formatTimeRange(history.startedAt, history.endedAt)})
                    </td>
                    <td className="px-4 py-3 text-neutral-600">{formatDuration(history.duration)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex flex-col gap-3 md:hidden">
            {historiesQuery.data.content.map((history) => (
              <div key={history.id} className="rounded-lg border border-neutral-200 bg-white p-4">
                <p className="text-xs font-medium text-neutral-500">{resourceTypeLabels[history.resourceType]}</p>
                <ResourceLabel resource={resourceMap.get(history.resourceId)} resourceId={history.resourceId} />
                <p className="mt-1 text-sm text-neutral-500">
                  {formatDateTime(history.startedAt)} · {formatDuration(history.duration)}
                </p>
              </div>
            ))}
          </div>

          <div className="mt-6">
            <Pagination page={page} totalPages={historiesQuery.data.totalPages} onPageChange={setPage} />
          </div>
        </>
      )}
    </div>
  )
}
