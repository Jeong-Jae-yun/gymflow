import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Dumbbell, Flame } from 'lucide-react'
import { PageHeader, EmptyState, ErrorState, Pagination, Skeleton } from '@/components/ui'
import { ResourceCard } from '@/features/resources/ResourceCard'
import { useResources, useTopRankings } from '@/features/resources/hooks'
import { useFavoriteResourceIds } from '@/features/favorites/hooks'
import { useToast } from '@/context/useToast'
import { resourceTypeLabels } from '@/utils/labels'

const PAGE_SIZE = 12

export function ResourceListPage() {
  const [page, setPage] = useState(0)
  const { showToast } = useToast()

  const resourcesQuery = useResources({ page, size: PAGE_SIZE, sort: { property: 'id', direction: 'ASC' } })
  const rankingsQuery = useTopRankings(6)
  const favoriteIds = useFavoriteResourceIds()

  function handleFavoriteError(message: string) {
    showToast({ tone: 'danger', title: '즐겨찾기 처리 실패', description: message })
  }

  return (
    <div>
      <PageHeader title="Resource" description="예약 가능한 시설과 장비를 확인하고 예약하세요." />

      {rankingsQuery.data && rankingsQuery.data.length > 0 && (
        <section className="mb-8">
          <h2 className="mb-3 flex items-center gap-1.5 text-sm font-semibold text-neutral-700">
            <Flame className="size-4 text-accent-500" aria-hidden="true" />
            인기 Resource
          </h2>
          <div className="flex gap-3 overflow-x-auto pb-2">
            {rankingsQuery.data.map((ranking) => (
              <Link
                key={ranking.resourceId}
                to={`/resources/${ranking.resourceId}`}
                className="flex w-56 shrink-0 items-center gap-3 rounded-lg border border-neutral-200 bg-white p-3 hover:border-brand-300 hover:bg-brand-50/40"
              >
                <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-accent-50 text-sm font-semibold text-accent-700">
                  {ranking.rank ?? '-'}
                </span>
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium text-neutral-900">{ranking.resourceName}</p>
                  <p className="truncate text-xs text-neutral-500">{resourceTypeLabels[ranking.resourceType]}</p>
                </div>
              </Link>
            ))}
          </div>
        </section>
      )}

      {resourcesQuery.isPending && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 8 }).map((_, index) => (
            <Skeleton key={index} className="h-64 w-full" />
          ))}
        </div>
      )}

      {resourcesQuery.isError && (
        <ErrorState error={resourcesQuery.error} onRetry={() => resourcesQuery.refetch()} />
      )}

      {resourcesQuery.data && resourcesQuery.data.content.length === 0 && (
        <EmptyState
          icon={Dumbbell}
          title="등록된 Resource가 없습니다"
          description="관리자가 Resource를 등록하면 이곳에 표시됩니다."
        />
      )}

      {resourcesQuery.data && resourcesQuery.data.content.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {resourcesQuery.data.content.map((resource) => (
              <ResourceCard
                key={resource.id}
                resource={resource}
                isFavorite={favoriteIds.has(resource.id)}
                onFavoriteError={handleFavoriteError}
              />
            ))}
          </div>
          <div className="mt-8">
            <Pagination page={page} totalPages={resourcesQuery.data.totalPages} onPageChange={setPage} />
          </div>
        </>
      )}
    </div>
  )
}
