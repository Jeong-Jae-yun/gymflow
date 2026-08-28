import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronDown, LogOut, User } from 'lucide-react'
import { useAuth } from '@/context/useAuth'
import { userRoleLabels } from '@/utils/labels'
import { cn } from '@/utils/cn'

interface UserMenuProps {
  variant?: 'light' | 'dark'
}

export function UserMenu({ variant = 'light' }: UserMenuProps) {
  const { user, logout } = useAuth()
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return

    function handlePointerDown(event: PointerEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }
    window.addEventListener('pointerdown', handlePointerDown)
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown)
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  if (!user) return null

  return (
    <div className="relative" ref={containerRef}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        className={cn(
          'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
          variant === 'dark' ? 'hover:bg-neutral-800' : 'hover:bg-neutral-100',
        )}
      >
        <span className="flex size-7 items-center justify-center rounded-full bg-neutral-200 text-neutral-600">
          <User className="size-4" aria-hidden="true" />
        </span>
        <span className={cn('hidden font-medium sm:inline', variant === 'dark' ? 'text-neutral-100' : 'text-neutral-800')}>
          {user.name}
        </span>
        <ChevronDown
          className={cn('size-4', variant === 'dark' ? 'text-neutral-400' : 'text-neutral-400')}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-20 mt-2 w-56 rounded-md border border-neutral-200 bg-white py-1 shadow-md"
        >
          <div className="border-b border-neutral-100 px-3 py-2">
            <p className="truncate text-sm font-medium text-neutral-900">{user.name}</p>
            <p className="truncate text-xs text-neutral-500">{user.email}</p>
            <p className="mt-1 text-xs text-neutral-400">{userRoleLabels[user.role]}</p>
          </div>
          <Link
            to="/profile"
            role="menuitem"
            onClick={() => setOpen(false)}
            className="block px-3 py-2 text-sm text-neutral-700 hover:bg-neutral-50"
          >
            내 프로필
          </Link>
          <button
            type="button"
            role="menuitem"
            onClick={logout}
            className="flex w-full items-center gap-2 px-3 py-2 text-left text-sm text-danger-600 hover:bg-danger-50"
          >
            <LogOut className="size-4" aria-hidden="true" />
            로그아웃
          </button>
        </div>
      )}
    </div>
  )
}
