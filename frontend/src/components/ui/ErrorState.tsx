import { AlertTriangle, WifiOff } from 'lucide-react'
import { Button } from './Button'
import type { ApiError } from '@/types'

interface ErrorStateProps {
  error?: ApiError | Error | null
  title?: string
  onRetry?: () => void
  className?: string
}

/** Generic query-failure UI: distinguishes offline/network errors from server errors and offers a retry. */
export function ErrorState({ error, title, onRetry, className }: ErrorStateProps) {
  const isNetworkError = !!error && 'isNetworkError' in error && error.isNetworkError
  const message = error && 'message' in error ? error.message : '알 수 없는 오류가 발생했습니다.'

  return (
    <div className={`flex flex-col items-center justify-center gap-3 rounded-lg border border-neutral-200 bg-white px-6 py-16 text-center ${className ?? ''}`}>
      <div className="flex size-12 items-center justify-center rounded-full bg-danger-50 text-danger-600">
        {isNetworkError ? (
          <WifiOff className="size-6" aria-hidden="true" />
        ) : (
          <AlertTriangle className="size-6" aria-hidden="true" />
        )}
      </div>
      <div className="flex flex-col gap-1">
        <p className="text-sm font-medium text-neutral-800">{title ?? '데이터를 불러오지 못했습니다'}</p>
        <p className="max-w-sm text-sm text-neutral-500">{message}</p>
      </div>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          다시 시도
        </Button>
      )}
    </div>
  )
}
