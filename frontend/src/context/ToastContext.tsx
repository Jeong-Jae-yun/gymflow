import { useCallback, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react'
import { cn } from '@/utils/cn'
import { ToastContext } from './toast-context'
import type { Toast, ToastTone } from './toast-context'

const toneStyles: Record<ToastTone, { icon: typeof CheckCircle2; classes: string }> = {
  success: { icon: CheckCircle2, classes: 'border-success-500/30 bg-success-50 text-success-700' },
  danger: { icon: AlertCircle, classes: 'border-danger-500/30 bg-danger-50 text-danger-700' },
  info: { icon: Info, classes: 'border-brand-500/30 bg-brand-50 text-brand-700' },
}

let nextId = 1

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])

  const removeToast = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id))
  }, [])

  const showToast = useCallback(
    (toast: Omit<Toast, 'id'>) => {
      const id = nextId++
      setToasts((current) => [...current, { ...toast, id }])
      window.setTimeout(() => removeToast(id), 5000)
    },
    [removeToast],
  )

  const value = useMemo(() => ({ showToast }), [showToast])

  return (
    <ToastContext.Provider value={value}>
      {children}
      {createPortal(
        <div className="pointer-events-none fixed inset-x-0 top-4 z-[100] flex flex-col items-center gap-2 px-4 sm:items-end sm:right-4 sm:left-auto">
          {toasts.map((toast) => {
            const { icon: Icon, classes } = toneStyles[toast.tone]
            return (
              <div
                key={toast.id}
                role="status"
                className={cn(
                  'pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-md border bg-white p-4 shadow-md',
                  classes,
                )}
              >
                <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
                <div className="flex-1">
                  <p className="text-sm font-medium">{toast.title}</p>
                  {toast.description && <p className="mt-0.5 text-sm opacity-90">{toast.description}</p>}
                </div>
                <button
                  type="button"
                  onClick={() => removeToast(toast.id)}
                  aria-label="알림 닫기"
                  className="rounded p-0.5 hover:bg-black/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
                >
                  <X className="size-4" aria-hidden="true" />
                </button>
              </div>
            )
          })}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  )
}
