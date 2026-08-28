import { NavLink, Outlet } from 'react-router-dom'
import { ArrowLeft, Shield } from 'lucide-react'
import { UserMenu } from '@/components/UserMenu'
import { cn } from '@/utils/cn'

const ADMIN_NAV_ITEMS = [{ to: '/admin/resources', label: 'Resource 관리' }]

function navLinkClass({ isActive }: { isActive: boolean }) {
  return cn(
    'rounded-md px-3 py-2 text-sm font-medium transition-colors',
    isActive ? 'bg-neutral-800 text-white' : 'text-neutral-300 hover:bg-neutral-800 hover:text-white',
  )
}

/** Deliberately styled distinctly from AppLayout (dark top bar) so ADMIN and USER experiences never look interchangeable. */
export function AdminLayout() {
  return (
    <div className="min-h-screen bg-neutral-50">
      <header className="sticky top-0 z-30 bg-neutral-900 text-white">
        <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
          <div className="flex items-center gap-8">
            <span className="inline-flex items-center gap-2 font-semibold tracking-tight">
              <span className="flex size-7 items-center justify-center rounded-md bg-accent-500 text-neutral-900">
                <Shield className="size-4" aria-hidden="true" />
              </span>
              GymFlow Admin
            </span>
            <nav className="hidden items-center gap-1 md:flex">
              {ADMIN_NAV_ITEMS.map((item) => (
                <NavLink key={item.to} to={item.to} className={navLinkClass}>
                  {item.label}
                </NavLink>
              ))}
            </nav>
          </div>

          <div className="flex items-center gap-3">
            <NavLink
              to="/"
              className="hidden items-center gap-1.5 rounded-md border border-neutral-700 px-3 py-1.5 text-sm text-neutral-200 hover:bg-neutral-800 sm:inline-flex"
            >
              <ArrowLeft className="size-4" aria-hidden="true" />
              사용자 화면으로
            </NavLink>
            <UserMenu variant="dark" />
          </div>
        </div>
        <nav className="flex items-center gap-1 border-t border-neutral-800 px-4 py-2 md:hidden">
          {ADMIN_NAV_ITEMS.map((item) => (
            <NavLink key={item.to} to={item.to} className={navLinkClass}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </header>

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        <Outlet />
      </main>
    </div>
  )
}
