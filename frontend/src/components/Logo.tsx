import { Dumbbell } from 'lucide-react'
import { cn } from '@/utils/cn'

export function Logo({ className }: { className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2 font-semibold tracking-tight text-neutral-900', className)}>
      <span className="flex size-7 items-center justify-center rounded-md bg-brand-600 text-white">
        <Dumbbell className="size-4" aria-hidden="true" />
      </span>
      GymFlow
    </span>
  )
}
