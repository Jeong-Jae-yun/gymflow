import { forwardRef } from 'react'
import type { SelectHTMLAttributes } from 'react'
import { ChevronDown } from 'lucide-react'
import { cn } from '@/utils/cn'

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  hasError?: boolean
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, hasError = false, children, ...props }, ref) => {
    return (
      <div className="relative">
        <select
          ref={ref}
          className={cn(
            'h-10 w-full appearance-none rounded-md border bg-white px-3 pr-9 text-sm text-neutral-900',
            'transition-colors duration-150',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1',
            'disabled:cursor-not-allowed disabled:bg-neutral-100 disabled:text-neutral-400',
            hasError
              ? 'border-danger-500 focus-visible:ring-danger-500'
              : 'border-neutral-300 focus-visible:ring-brand-500',
            className,
          )}
          {...props}
        >
          {children}
        </select>
        <ChevronDown
          className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-neutral-500"
          aria-hidden="true"
        />
      </div>
    )
  },
)
Select.displayName = 'Select'
