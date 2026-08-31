import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Clock, Timer, Users } from 'lucide-react'
import { Badge, Button, ErrorState, PageSpinner } from '@/components/ui'
import { ImageWithFallback } from '@/components/ImageWithFallback'
import { FavoriteButton } from '@/features/favorites/FavoriteButton'
import { useFavoriteResourceIds } from '@/features/favorites/hooks'
import { useResourceDetail } from '@/features/resources/hooks'
import { useToast } from '@/context/useToast'
import { resourceStatusLabels, resourceStatusTones, resourceTypeLabels } from '@/utils/labels'

export function ResourceDetailPage() {
  const params = useParams<{ resourceId: string }>()
  const resourceId = Number(params.resourceId)
  const navigate = useNavigate()
  const { showToast } = useToast()

  const resourceQuery = useResourceDetail(resourceId)
  const favoriteIds = useFavoriteResourceIds()

  if (resourceQuery.isPending) {
    return <PageSpinner label="Resource 정보를 불러오는 중입니다..." />
  }

  if (resourceQuery.isError) {
    return <ErrorState error={resourceQuery.error} onRetry={() => resourceQuery.refetch()} />
  }

  const resource = resourceQuery.data
  const canReserve = resource.status === 'ACTIVE' && resource.reservationPolicy !== null

  return (
    <div className="mx-auto max-w-3xl">
      <button
        type="button"
        onClick={() => navigate(-1)}
        className="mb-4 inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        뒤로가기
      </button>

      <div className="overflow-hidden rounded-lg border border-neutral-200 bg-white">
        <ImageWithFallback src={resource.imageUrl} alt={resource.name} className="max-h-80" />

        <div className="p-6">
          <div className="flex items-start justify-between gap-4">
            <div>
              <p className="text-sm font-medium text-neutral-500">{resourceTypeLabels[resource.resourceType]}</p>
              <h1 className="mt-1 text-xl font-semibold text-neutral-900">{resource.name}</h1>
            </div>
            <div className="flex items-center gap-2">
              <Badge tone={resourceStatusTones[resource.status]}>{resourceStatusLabels[resource.status]}</Badge>
              <FavoriteButton
                resourceId={resource.id}
                isFavorite={favoriteIds.has(resource.id)}
                onError={(message) => showToast({ tone: 'danger', title: '즐겨찾기 처리 실패', description: message })}
              />
            </div>
          </div>

          {resource.description && (
            <p className="mt-4 whitespace-pre-line text-sm leading-relaxed text-neutral-600">
              {resource.description}
            </p>
          )}

          <div className="mt-6 grid grid-cols-2 gap-4 rounded-md bg-neutral-50 p-4 sm:grid-cols-4">
            <InfoStat icon={Users} label="정원" value={`${resource.capacity}명`} />
            {resource.reservationPolicy && (
              <>
                <InfoStat icon={Clock} label="예약 단위" value={`${resource.reservationPolicy.slotDuration}분`} />
                <InfoStat icon={Timer} label="최소 이용" value={`${resource.reservationPolicy.minDuration}분`} />
                <InfoStat icon={Timer} label="최대 이용" value={`${resource.reservationPolicy.maxDuration}분`} />
              </>
            )}
          </div>

          <div className="mt-6 flex flex-col gap-2 sm:flex-row">
            {canReserve ? (
              <Link to={`/resources/${resource.id}/reserve`} className="sm:flex-1">
                <Button className="w-full" size="lg">
                  예약하기
                </Button>
              </Link>
            ) : (
              <div className="flex-1 rounded-md border border-dashed border-neutral-300 px-4 py-3 text-center text-sm text-neutral-500">
                {resource.status === 'MAINTENANCE'
                  ? '현재 점검 중이라 예약할 수 없습니다.'
                  : resource.status === 'INACTIVE'
                    ? '현재 운영이 중지되어 예약할 수 없습니다.'
                    : '예약 정책이 설정되지 않아 예약할 수 없습니다.'}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function InfoStat({ icon: Icon, label, value }: { icon: typeof Users; label: string; value: string }) {
  return (
    <div className="flex flex-col items-center gap-1 text-center">
      <Icon className="size-4 text-neutral-400" aria-hidden="true" />
      <p className="text-sm font-semibold text-neutral-900">{value}</p>
      <p className="text-xs text-neutral-500">{label}</p>
    </div>
  )
}
