import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AppLayout } from './AppLayout'

vi.mock('@/context/useAuth', () => ({
  useAuth: () => ({ isAdmin: false, user: null, logout: vi.fn() }),
}))

vi.mock('@/features/waitingQueue/useWaitingQueueLiveIndicator', () => ({
  useWaitingQueueLiveIndicator: () => false,
}))

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<div>대시보드 홈</div>} />
          <Route path="/reservations" element={<div>내 예약 페이지</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('AppLayout — logo home navigation', () => {
  it('renders the GymFlow logo as a link pointing to "/"', () => {
    renderAt('/reservations')

    const homeLink = screen.getByRole('link', { name: 'GymFlow 홈으로 이동' })
    expect(homeLink).toHaveAttribute('href', '/')
  })

  it('navigates to the dashboard home via SPA routing when the logo is clicked', async () => {
    const user = userEvent.setup()
    renderAt('/reservations')

    expect(screen.getByText('내 예약 페이지')).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'GymFlow 홈으로 이동' }))

    expect(screen.getByText('대시보드 홈')).toBeInTheDocument()
    expect(screen.queryByText('내 예약 페이지')).not.toBeInTheDocument()
  })
})
