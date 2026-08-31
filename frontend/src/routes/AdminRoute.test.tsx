import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AdminRoute } from './AdminRoute'

const mockUseAuth = vi.fn()
vi.mock('@/context/useAuth', () => ({
  useAuth: () => mockUseAuth(),
}))

function renderAdminRoute() {
  return render(
    <MemoryRouter initialEntries={['/admin/resources']}>
      <Routes>
        <Route path="/login" element={<div>로그인 페이지</div>} />
        <Route path="/" element={<div>사용자 대시보드</div>} />
        <Route path="/admin" element={<AdminRoute />}>
          <Route path="resources" element={<div>관리자 Resource 목록</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdminRoute', () => {
  it('redirects to /login when unauthenticated', () => {
    mockUseAuth.mockReturnValue({ status: 'unauthenticated', isAdmin: false })

    renderAdminRoute()

    expect(screen.getByText('로그인 페이지')).toBeInTheDocument()
  })

  it('redirects a non-admin authenticated USER back to the app instead of exposing admin UI', () => {
    mockUseAuth.mockReturnValue({ status: 'authenticated', isAdmin: false })

    renderAdminRoute()

    expect(screen.getByText('사용자 대시보드')).toBeInTheDocument()
    expect(screen.queryByText('관리자 Resource 목록')).not.toBeInTheDocument()
  })

  it('renders admin content for an authenticated ADMIN', () => {
    mockUseAuth.mockReturnValue({ status: 'authenticated', isAdmin: true })

    renderAdminRoute()

    expect(screen.getByText('관리자 Resource 목록')).toBeInTheDocument()
  })
})
