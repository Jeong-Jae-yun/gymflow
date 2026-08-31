import type { PageParams } from './pageParams'

/** Centralized TanStack Query key factory, one entry per resource family. */
export const queryKeys = {
  me: () => ['me'] as const,

  resources: {
    all: () => ['resources'] as const,
    list: (params?: PageParams) => ['resources', 'list', params ?? {}] as const,
    detail: (id: number) => ['resources', 'detail', id] as const,
    popular: (limit: number) => ['resources', 'popular', limit] as const,
    rankings: (limit: number) => ['resources', 'rankings', limit] as const,
    ranking: (id: number) => ['resources', 'ranking', id] as const,
    availability: (id: number, date: string) => ['resources', 'availability', id, date] as const,
  },

  adminResources: {
    detail: (id: number) => ['admin', 'resources', 'detail', id] as const,
    statistics: (id: number, from?: string, to?: string) =>
      ['admin', 'resources', 'statistics', id, from ?? null, to ?? null] as const,
  },

  reservations: {
    all: () => ['reservations'] as const,
    list: (params?: PageParams) => ['reservations', 'list', params ?? {}] as const,
    detail: (id: number) => ['reservations', 'detail', id] as const,
  },

  favorites: {
    all: () => ['favorites'] as const,
  },

  waitingQueues: {
    all: () => ['waitingQueues'] as const,
    list: (params?: PageParams) => ['waitingQueues', 'list', params ?? {}] as const,
  },

  usageHistories: {
    list: (params?: { page?: number; size?: number; from?: string; to?: string }) =>
      ['usageHistories', 'list', params ?? {}] as const,
    statistics: (from?: string, to?: string) =>
      ['usageHistories', 'statistics', from ?? null, to ?? null] as const,
  },
} as const
