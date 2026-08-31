import { useState } from 'react'
import { Link } from 'react-router-dom'
import { BarChart3, Dumbbell, Pencil, Plus } from 'lucide-react'
import { Badge, Button, EmptyState, ErrorState, PageHeader, Pagination, Skeleton } from '@/components/ui'
import { useResources } from '@/features/resources/hooks'
import { resourceStatusLabels, resourceStatusTones, resourceTypeLabels } from '@/utils/labels'

const PAGE_SIZE = 15

/**
 * There is no GET /api/admin/resources list endpoint (AdminResourceController
 * only exposes single-resource routes). ADMIN users are also plain
 * authenticated users for every non-admin endpoint, so this table reuses the
 * public GET /api/resources listing — a documented Frontend Integration
 * Limitation rather than a fabricated admin-only endpoint.
 */
export function AdminResourceListPage() {
  const [page, setPage] = useState(0)
  const resourcesQuery = useResources({ page, size: PAGE_SIZE, sort: { property: 'id', direction: 'ASC' } })

  return (
    <div>
      <PageHeader
        title="Resource 관리"
        description="시설과 장비를 등록하고 상태, 이미지, 통계를 관리하세요."
        actions={
          <Link to="/admin/resources/new">
            <Button leftIcon={<Plus className="size-4" aria-hidden="true" />}>새 Resource</Button>
          </Link>
        }
      />

      {resourcesQuery.isPending && (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 6 }).map((_, index) => (
            <Skeleton key={index} className="h-12 w-full" />
          ))}
        </div>
      )}

      {resourcesQuery.isError && <ErrorState error={resourcesQuery.error} onRetry={() => resourcesQuery.refetch()} />}

      {resourcesQuery.data && resourcesQuery.data.content.length === 0 && (
        <EmptyState
          icon={Dumbbell}
          title="등록된 Resource가 없습니다"
          description="새 Resource를 등록해 예약을 받을 수 있도록 하세요."
          action={
            <Link to="/admin/resources/new">
              <Button size="sm">새 Resource 등록</Button>
            </Link>
          }
        />
      )}

      {resourcesQuery.data && resourcesQuery.data.content.length > 0 && (
        <>
          <div className="overflow-hidden rounded-lg border border-neutral-200 bg-white">
            <table className="w-full text-sm">
              <thead className="border-b border-neutral-200 bg-neutral-50 text-left text-xs font-medium uppercase tracking-wide text-neutral-500">
                <tr>
                  <th className="px-4 py-3">ID</th>
                  <th className="px-4 py-3">이름</th>
                  <th className="hidden px-4 py-3 sm:table-cell">유형</th>
                  <th className="px-4 py-3">상태</th>
                  <th className="hidden px-4 py-3 sm:table-cell">정원</th>
                  <th className="px-4 py-3 text-right">관리</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-neutral-100">
                {resourcesQuery.data.content.map((resource) => (
                  <tr key={resource.id} className="hover:bg-neutral-50">
                    <td className="px-4 py-3 text-neutral-400">{resource.id}</td>
                    <td className="px-4 py-3 font-medium text-neutral-900">{resource.name}</td>
                    <td className="hidden px-4 py-3 text-neutral-600 sm:table-cell">
                      {resourceTypeLabels[resource.resourceType]}
                    </td>
                    <td className="px-4 py-3">
                      <Badge tone={resourceStatusTones[resource.status]}>{resourceStatusLabels[resource.status]}</Badge>
                    </td>
                    <td className="hidden px-4 py-3 text-neutral-600 sm:table-cell">{resource.capacity}명</td>
                    <td className="px-4 py-3">
                      <div className="flex justify-end gap-1">
                        <Link
                          to={`/admin/resources/${resource.id}/statistics`}
                          className="inline-flex size-8 items-center justify-center rounded-md text-neutral-400 hover:bg-neutral-100 hover:text-neutral-700"
                          aria-label="통계 보기"
                        >
                          <BarChart3 className="size-4" aria-hidden="true" />
                        </Link>
                        <Link
                          to={`/admin/resources/${resource.id}/edit`}
                          className="inline-flex size-8 items-center justify-center rounded-md text-neutral-400 hover:bg-neutral-100 hover:text-neutral-700"
                          aria-label="수정"
                        >
                          <Pencil className="size-4" aria-hidden="true" />
                        </Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-6">
            <Pagination page={page} totalPages={resourcesQuery.data.totalPages} onPageChange={setPage} />
          </div>
        </>
      )}
    </div>
  )
}
