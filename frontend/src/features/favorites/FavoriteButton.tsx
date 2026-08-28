import type { MouseEvent } from 'react'
import { Heart } from 'lucide-react'
import { useAddFavorite, useRemoveFavorite } from './hooks'
import { cn } from '@/utils/cn'
import { getErrorMessage } from '@/utils/getErrorMessage'

interface FavoriteButtonProps {
  resourceId: number
  isFavorite: boolean
  size?: 'sm' | 'md'
  onError?: (message: string) => void
}

export function FavoriteButton({ resourceId, isFavorite, size = 'md', onError }: FavoriteButtonProps) {
  const addFavorite = useAddFavorite()
  const removeFavorite = useRemoveFavorite()
  const pending = addFavorite.isPending || removeFavorite.isPending

  function handleClick(event: MouseEvent) {
    event.preventDefault()
    event.stopPropagation()
    const mutation = isFavorite ? removeFavorite : addFavorite
    mutation.mutate(resourceId, {
      onError: (error) => onError?.(getErrorMessage(error, '즐겨찾기 처리에 실패했습니다.')),
    })
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={pending}
      aria-pressed={isFavorite}
      aria-label={isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가'}
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full border transition-colors',
        size === 'sm' ? 'size-8' : 'size-9',
        isFavorite
          ? 'border-danger-200 bg-danger-50 text-danger-600 hover:bg-danger-100'
          : 'border-neutral-200 bg-white text-neutral-400 hover:border-neutral-300 hover:text-neutral-600',
        'disabled:opacity-60',
      )}
    >
      <Heart className={cn('size-4', isFavorite && 'fill-current')} aria-hidden="true" />
    </button>
  )
}
