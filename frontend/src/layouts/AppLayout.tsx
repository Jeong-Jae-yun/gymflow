import { useState } from 'react'
import { Link, NavLink, Outlet } from 'react-router-dom'
import { Menu, Shield, X } from 'lucide-react'
import { Logo } from '@/components/Logo'
import { UserMenu } from '@/components/UserMenu'
import { useAuth } from '@/context/useAuth'
import { cn } from '@/utils/cn'
import { useWaitingQueueLiveIndicator } from '@/features/waitingQueue/useWaitingQueueLiveIndicator'

const NAV_ITEMS = [
  { to: '/', label: '대시보드', end: true },
  { to: '/resources', label: 'Resource' },
  { to: '/reservations', label: '내 예약' },
  { to: '/waiting-queue', label: '대기열' },
  { to: '/favorites', label: '즐겨찾기' },
  { to: '/usage-history', label: '이용 내역' },
]

function navLinkClass({ isActive }: { isActive: boolean }) {
  return cn(
    'rounded-md px-3 py-2 text-sm font-medium transition-colors',
    isActive ? 'bg-brand-50 text-brand-700' : 'text-neutral-600 hover:bg-neutral-100 hover:text-neutral-900',
  )
}

export function AppLayout() {
  const { isAdmin } = useAuth()
  const [mobileOpen, setMobileOpen] = useState(false)
  const hasActivePromotion = useWaitingQueueLiveIndicator()

  return (
    <div className="min-h-screen bg-neutral-50">
      <header className="sticky top-0 z-30 border-b border-neutral-200 bg-white/95 backdrop-blur-sm">
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
          <div className="flex items-center gap-8">
            <Link
              to="/"
              className="rounded-md transition-opacity hover:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2"
              aria-label="GymFlow 홈으로 이동"
            >
              <Logo />
            </Link>
            <nav className="hidden items-center gap-1 md:flex">
              {NAV_ITEMS.map((item) => (
                <NavLink key={item.to} to={item.to} end={item.end} className={navLinkClass}>
                  <span className="relative">
                    {item.label}
                    {item.to === '/waiting-queue' && hasActivePromotion && (
                      <span
                        className="absolute -right-2 -top-1 size-1.5 rounded-full bg-accent-500"
                        aria-hidden="true"
                      />
                    )}
                  </span>
                </NavLink>
              ))}
            </nav>
          </div>

          <div className="flex items-center gap-2">
            {isAdmin && (
              <NavLink
                to="/admin"
                className="hidden items-center gap-1.5 rounded-md border border-neutral-300 px-3 py-1.5 text-sm font-medium text-neutral-700 hover:bg-neutral-100 sm:inline-flex"
              >
                <Shield className="size-4" aria-hidden="true" />
                관리자
              </NavLink>
            )}
            <UserMenu />
            <button
              type="button"
              className="rounded-md p-2 text-neutral-600 hover:bg-neutral-100 md:hidden"
              aria-label={mobileOpen ? '메뉴 닫기' : '메뉴 열기'}
              aria-expanded={mobileOpen}
              onClick={() => setMobileOpen((v) => !v)}
            >
              {mobileOpen ? <X className="size-5" /> : <Menu className="size-5" />}
            </button>
          </div>
        </div>

        {mobileOpen && (
          <nav className="border-t border-neutral-200 px-4 py-2 md:hidden">
            <div className="flex flex-col gap-1">
              {NAV_ITEMS.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  onClick={() => setMobileOpen(false)}
                  className={navLinkClass}
                >
                  {item.label}
                </NavLink>
              ))}
              {isAdmin && (
                <NavLink to="/admin" onClick={() => setMobileOpen(false)} className={navLinkClass}>
                  관리자
                </NavLink>
              )}
            </div>
          </nav>
        )}
      </header>

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        <Outlet />
      </main>
    </div>
  )
}
