export type ResourceType =
  | 'MACHINE'
  | 'PT_ROOM'
  | 'LOCKER'
  | 'STRETCH_ZONE'
  | 'SAUNA'
  | 'SHOWER_ROOM'

export type ResourceStatus = 'ACTIVE' | 'INACTIVE' | 'MAINTENANCE'

export interface ReservationPolicySummary {
  slotDuration: number
  minDuration: number
  maxDuration: number
}

export interface ResourceResponse {
  id: number
  name: string
  resourceType: ResourceType
  status: ResourceStatus
  capacity: number
  description: string | null
  reservationPolicy: ReservationPolicySummary | null
  imageUrl: string | null
}

export interface PopularResourceResponse {
  resourceId: number
  reservationCount: number
  rank: number
}

export interface AvailabilitySlot {
  startAt: string
  endAt: string
  available: boolean
}

export interface ResourceAvailabilityResponse {
  resourceId: number
  date: string
  slotDuration: number
  minDuration: number
  maxDuration: number
  slots: AvailabilitySlot[]
}

export interface ResourceRankingResponse {
  rank: number | null
  resourceId: number
  resourceName: string
  resourceType: ResourceType
  resourceStatus: ResourceStatus
  score: number
}

export interface AdminResourceResponse {
  id: number
  name: string
  type: ResourceType
  status: ResourceStatus
  capacity: number
  description: string | null
  slotDuration: number
  minDuration: number
  maxDuration: number
  imageUrl: string | null
  createdAt: string
  updatedAt: string
}

export interface AdminResourceCreateRequest {
  name: string
  type: ResourceType
  capacity: number
  description?: string
  slotDuration: number
  minDuration: number
  maxDuration: number
}

export interface AdminResourceUpdateRequest {
  name: string
  capacity: number
  description?: string
  slotDuration: number
  minDuration: number
  maxDuration: number
}

export interface AdminResourceStatusUpdateRequest {
  status: ResourceStatus
}

export interface ResourceImageResponse {
  resourceId: number
  imageUrl: string
}
