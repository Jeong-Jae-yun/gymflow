import { Link } from 'react-router-dom'
import { useQueries } from '@tanstack/react-query'
import { Heart } from 'lucide-react'
import { PageHeader, Button, EmptyState, ErrorState, Skeleton } from '@/components/ui'
import { ResourceCard } from '@/features/resources/ResourceCard'
import { useFavorites } from '@/features/favorites/hooks'
import { useToast } from '@/context/useToast'
import { resourcesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'

/**
 * FavoriteResponse only carries { favoriteId, resourceId, createdAt } — the
 * backend has no "favorites with resource details" endpoint. We fan out one
 * GET /api/resources/{id} per favorite via useQueries; this is a documented
 * Frontend Integration Limitation (N+1) rather than a fabricated batch API.
 */
export function FavoritesPage() {
  const favoritesQuery = useFavorites()
  const { showToast } = useToast()

  const favorites = favoritesQuery.data ?? []

  const resourceQueries = useQueries({
    queries: favorites.map((favorite) => ({
      queryKey: queryKeys.resources.detail(favorite.resourceId),
      queryFn: () => resourcesApi.getResourceDetail(favorite.resourceId),
    })),
  })

  function handleFavoriteError(message: string) {
    showToast({ tone: 'danger', title: '즐겨찾기 처리 실패', description: message })
  }

  return (
    <div>
      <PageHeader title="즐겨찾기" description="자주 이용하는 Resource를 빠르게 확인하세요." />

      {favoritesQuery.isPending && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => (
            <Skeleton key={index} className="h-64 w-full" />
          ))}
        </div>
      )}

      {favoritesQuery.isError && <ErrorState error={favoritesQuery.error} onRetry={() => favoritesQuery.refetch()} />}

      {favoritesQuery.isSuccess && favorites.length === 0 && (
        <EmptyState
          icon={Heart}
          title="즐겨찾기한 Resource가 없습니다"
          description="자주 이용하는 시설을 즐겨찾기에 추가하면 이곳에서 바로 확인할 수 있습니다."
          action={
            <Link to="/resources">
              <Button variant="secondary" size="sm">
                Resource 둘러보기
              </Button>
            </Link>
          }
        />
      )}

      {favoritesQuery.isSuccess && favorites.length > 0 && (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {resourceQueries.map((query, index) => {
            if (query.isPending) return <Skeleton key={favorites[index].favoriteId} className="h-64 w-full" />
            if (query.isError || !query.data) return null
            return (
              <ResourceCard
                key={favorites[index].favoriteId}
                resource={query.data}
                isFavorite
                onFavoriteError={handleFavoriteError}
              />
            )
          })}
        </div>
      )}
    </div>
  )
}
