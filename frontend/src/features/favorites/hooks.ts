import { useMemo } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { favoritesApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'

export function useFavorites() {
  return useQuery({
    queryKey: queryKeys.favorites.all(),
    queryFn: favoritesApi.list,
  })
}

export function useFavoriteResourceIds(): Set<number> {
  const { data } = useFavorites()
  return useMemo(() => new Set((data ?? []).map((favorite) => favorite.resourceId)), [data])
}

export function useAddFavorite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (resourceId: number) => favoritesApi.add(resourceId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.favorites.all() }),
  })
}

export function useRemoveFavorite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (resourceId: number) => favoritesApi.remove(resourceId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.favorites.all() }),
  })
}
