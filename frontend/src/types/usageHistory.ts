import type { ResourceStatus, ResourceType } from './resource'

export interface UsageHistoryResponse {
  id: number
  reservationId: number
  resourceId: number
  resourceName: string
  resourceType: ResourceType
  resourceStatus: ResourceStatus
  startedAt: string
  endedAt: string
  duration: number
}

export interface ResourceUsageStatisticsResponse {
  resourceId: number
  resourceName: string
  resourceType: ResourceType
  resourceStatus: ResourceStatus
  usageCount: number
  totalUsageMinutes: number
}

export interface UsageStatisticsResponse {
  totalUsageCount: number
  totalUsageMinutes: number
  resourceUsages: ResourceUsageStatisticsResponse[]
}

export interface AdminResourceUsageStatisticsResponse {
  resourceId: number
  resourceName: string
  resourceType: ResourceType
  resourceStatus: ResourceStatus
  totalUsageCount: number
  totalUsageMinutes: number
}
