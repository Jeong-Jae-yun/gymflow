import type { ResourceResponse } from '@/types'
import { resourceTypeLabels } from '@/utils/labels'

interface ResourceLabelProps {
  resource: ResourceResponse | undefined
  resourceId: number
}

export function ResourceLabel({ resource, resourceId }: ResourceLabelProps) {
  if (!resource) {
    return <span className="text-neutral-400">Resource #{resourceId}</span>
  }
  return (
    <span>
      <span className="font-medium text-neutral-900">{resource.name}</span>
      <span className="ml-1.5 text-xs text-neutral-400">{resourceTypeLabels[resource.resourceType]}</span>
    </span>
  )
}
