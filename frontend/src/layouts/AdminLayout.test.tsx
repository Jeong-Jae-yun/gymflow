import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AdminLayout } from './AdminLayout'

vi.mock('@/context/useAuth', () => ({
  useAuth: () => ({ isAdmin: true, user: null, logout: vi.fn() }),
}))

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AdminLayout />}>
          <Route path="/admin/resources" element={<div>Admin Resource 목록</div>} />
          <Route path="/admin/resources/:resourceId/statistics" element={<div>Resource 통계</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('AdminLayout — brand logo home navigation', () => {
  it('renders the GymFlow Admin brand as a link pointing to the Admin resource list (not the user home)', () => {
    renderAt('/admin/resources/5/statistics')

    const adminHomeLink = screen.getByRole('link', { name: 'Admin 홈으로 이동' })
    expect(adminHomeLink).toHaveAttribute('href', '/admin/resources')
  })

  it('navigates to the Admin resource list via SPA routing when the brand is clicked', async () => {
    const user = userEvent.setup()
    renderAt('/admin/resources/5/statistics')

    expect(screen.getByText('Resource 통계')).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Admin 홈으로 이동' }))

    expect(screen.getByText('Admin Resource 목록')).toBeInTheDocument()
    expect(screen.queryByText('Resource 통계')).not.toBeInTheDocument()
  })

  it('keeps the existing "사용자 화면으로" link pointing at the user home ("/") separately from the brand link', () => {
    renderAt('/admin/resources')

    const backToUserLink = screen.getByRole('link', { name: '사용자 화면으로' })
    expect(backToUserLink).toHaveAttribute('href', '/')
  })
})
