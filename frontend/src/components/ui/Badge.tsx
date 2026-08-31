import type { ReactNode } from 'react'
import { cn } from '@/utils/cn'
import type { BadgeTone } from '@/utils/labels'

const toneClasses: Record<BadgeTone, string> = {
  brand: 'bg-brand-50 text-brand-700 ring-1 ring-inset ring-brand-200',
  success: 'bg-success-50 text-success-700 ring-1 ring-inset ring-success-500/30',
  warning: 'bg-warning-50 text-warning-700 ring-1 ring-inset ring-warning-500/30',
  danger: 'bg-danger-50 text-danger-700 ring-1 ring-inset ring-danger-500/30',
  neutral: 'bg-neutral-100 text-neutral-700 ring-1 ring-inset ring-neutral-300',
  accent: 'bg-accent-300/20 text-accent-700 ring-1 ring-inset ring-accent-400/50',
}

interface BadgeProps {
  tone?: BadgeTone
  children: ReactNode
  className?: string
  dotted?: boolean
}

/** Status is always conveyed by the label text, not color alone. */
export function Badge({ tone = 'neutral', children, className, dotted = false }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium',
        toneClasses[tone],
        className,
      )}
    >
      {dotted && <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />}
      {children}
    </span>
  )
}
