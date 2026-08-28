import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { cn } from '@/utils/cn'

interface EmptyStateProps {
  icon?: LucideIcon
  title: string
  description?: string
  action?: ReactNode
  className?: string
}

export function EmptyState({ icon: Icon, title, description, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed border-neutral-300 px-6 py-16 text-center',
        className,
      )}
    >
      {Icon && (
        <div className="flex size-12 items-center justify-center rounded-full bg-neutral-100 text-neutral-400">
          <Icon className="size-6" aria-hidden="true" />
        </div>
      )}
      <div className="flex flex-col gap-1">
        <p className="text-sm font-medium text-neutral-800">{title}</p>
        {description && <p className="text-sm text-neutral-500">{description}</p>}
      </div>
      {action}
    </div>
  )
}
