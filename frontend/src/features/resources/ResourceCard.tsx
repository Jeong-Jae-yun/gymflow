import { Link } from 'react-router-dom'
import { Users } from 'lucide-react'
import { Badge, Card } from '@/components/ui'
import { ImageWithFallback } from '@/components/ImageWithFallback'
import { FavoriteButton } from '@/features/favorites/FavoriteButton'
import { resourceStatusLabels, resourceStatusTones, resourceTypeLabels } from '@/utils/labels'
import type { ResourceResponse } from '@/types'

interface ResourceCardProps {
  resource: ResourceResponse
  isFavorite: boolean
  onFavoriteError?: (message: string) => void
}

export function ResourceCard({ resource, isFavorite, onFavoriteError }: ResourceCardProps) {
  return (
    <Card className="group relative flex flex-col overflow-hidden transition-shadow hover:shadow-sm">
      <Link to={`/resources/${resource.id}`} className="flex flex-1 flex-col focus:outline-none">
        <div className="relative">
          <ImageWithFallback src={resource.imageUrl} alt={resource.name} />
          <div className="absolute right-2 top-2">
            <FavoriteButton resourceId={resource.id} isFavorite={isFavorite} size="sm" onError={onFavoriteError} />
          </div>
        </div>
        <div className="flex flex-1 flex-col gap-2 p-4">
          <div className="flex items-start justify-between gap-2">
            <div>
              <p className="text-xs font-medium text-neutral-500">{resourceTypeLabels[resource.resourceType]}</p>
              <h3 className="mt-0.5 truncate text-sm font-semibold text-neutral-900 group-hover:text-brand-700">
                {resource.name}
              </h3>
            </div>
            <Badge tone={resourceStatusTones[resource.status]}>{resourceStatusLabels[resource.status]}</Badge>
          </div>

          {resource.description && (
            <p className="line-clamp-2 text-sm text-neutral-500">{resource.description}</p>
          )}

          <div className="mt-auto flex items-center gap-1.5 pt-2 text-xs text-neutral-500">
            <Users className="size-3.5" aria-hidden="true" />
            정원 {resource.capacity}명
          </div>
        </div>
      </Link>
    </Card>
  )
}
