import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ProtectedRoute } from './ProtectedRoute'

const mockUseAuth = vi.fn()
vi.mock('@/context/useAuth', () => ({
  useAuth: () => mockUseAuth(),
}))

function renderProtected() {
  return render(
    <MemoryRouter initialEntries={['/reservations']}>
      <Routes>
        <Route path="/login" element={<div>로그인 페이지</div>} />
        <Route element={<ProtectedRoute />}>
          <Route path="/reservations" element={<div>내 예약 페이지</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  it('shows a loading state while auth status is being resolved', () => {
    mockUseAuth.mockReturnValue({ status: 'loading' })

    renderProtected()

    expect(screen.getByText('인증 정보를 확인하는 중입니다...')).toBeInTheDocument()
    expect(screen.queryByText('내 예약 페이지')).not.toBeInTheDocument()
  })

  it('redirects to /login when unauthenticated', () => {
    mockUseAuth.mockReturnValue({ status: 'unauthenticated' })

    renderProtected()

    expect(screen.getByText('로그인 페이지')).toBeInTheDocument()
    expect(screen.queryByText('내 예약 페이지')).not.toBeInTheDocument()
  })

  it('renders the protected content when authenticated', () => {
    mockUseAuth.mockReturnValue({ status: 'authenticated' })

    renderProtected()

    expect(screen.getByText('내 예약 페이지')).toBeInTheDocument()
  })
})
