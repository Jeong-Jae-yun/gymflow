import { createContext } from 'react'

export type ToastTone = 'success' | 'danger' | 'info'

export interface Toast {
  id: number
  tone: ToastTone
  title: string
  description?: string
}

export interface ToastContextValue {
  showToast: (toast: Omit<Toast, 'id'>) => void
}

export const ToastContext = createContext<ToastContextValue | null>(null)
