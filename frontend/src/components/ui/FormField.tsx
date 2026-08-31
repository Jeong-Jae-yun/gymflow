import type { ReactNode } from 'react'
import { useId } from 'react'
import { cn } from '@/utils/cn'

interface FormFieldProps {
  label: string
  htmlFor?: string
  required?: boolean
  error?: string
  helpText?: string
  children: (id: string, describedBy: string | undefined) => ReactNode
  className?: string
}

/**
 * Composes a label, help text, and inline validation error around any form
 * control. The control is rendered via a render-prop so it receives the
 * generated id/aria-describedby wiring without FormField needing to know its
 * shape.
 */
export function FormField({ label, htmlFor, required, error, helpText, children, className }: FormFieldProps) {
  const generatedId = useId()
  const id = htmlFor ?? generatedId
  const helpId = helpText ? `${id}-help` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [helpId, errorId].filter(Boolean).join(' ') || undefined

  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-medium text-neutral-800">
        {label}
        {required && (
          <span className="ml-0.5 text-danger-600" aria-hidden="true">
            *
          </span>
        )}
      </label>
      {children(id, describedBy)}
      {error ? (
        <p id={errorId} className="text-sm text-danger-600" role="alert">
          {error}
        </p>
      ) : helpText ? (
        <p id={helpId} className="text-sm text-neutral-500">
          {helpText}
        </p>
      ) : null}
    </div>
  )
}
