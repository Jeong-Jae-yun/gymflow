export interface SortParam {
  property: string
  direction?: 'ASC' | 'DESC'
}

export interface PageParams {
  page?: number
  size?: number
  sort?: SortParam
}

/** Builds Spring `Pageable` query params (page, size, sort=property,direction). */
export function toPageableParams(params?: PageParams): Record<string, string | number> {
  if (!params) return {}
  const query: Record<string, string | number> = {}
  if (params.page !== undefined) query.page = params.page
  if (params.size !== undefined) query.size = params.size
  if (params.sort) query.sort = `${params.sort.property},${params.sort.direction ?? 'ASC'}`
  return query
}
