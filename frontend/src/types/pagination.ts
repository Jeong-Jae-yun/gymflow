/**
 * Spring Data `Page<T>` default Jackson serialization shape.
 * Used by GET /api/resources, /api/reservations, /api/waiting-queues.
 */
export interface SpringPage<T> {
  content: T[]
  number: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
  empty: boolean
  numberOfElements: number
}

/**
 * Custom `PageResponse<T>` shape used by GET /api/usage-histories.
 */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}
