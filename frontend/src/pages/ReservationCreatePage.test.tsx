import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ReservationCreatePage } from './ReservationCreatePage'
import type { ResourceResponse } from '@/types'

const resource: ResourceResponse = {
  id: 5,
  name: '스쿼트 랙 A',
  resourceType: 'MACHINE',
  status: 'ACTIVE',
  capacity: 1,
  description: null,
  reservationPolicy: { slotDuration: 30, minDuration: 30, maxDuration: 90 },
  imageUrl: null,
}

const mockCreateMutate = vi.fn()
const mockRegisterWaitingQueueMutate = vi.fn()

vi.mock('@/features/resources/hooks', () => ({
  useResourceDetail: () => ({ data: resource, isPending: false, isError: false, refetch: vi.fn() }),
}))

vi.mock('@/features/reservations/hooks', () => ({
  useCreateReservation: () => ({ mutate: mockCreateMutate, isPending: false }),
}))

vi.mock('@/features/waitingQueue/hooks', () => ({
  useRegisterWaitingQueue: () => ({ mutate: mockRegisterWaitingQueueMutate, isPending: false }),
}))

vi.mock('@/context/useToast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/resources/5/reserve']}>
      <Routes>
        <Route path="/resources/:resourceId/reserve" element={<ReservationCreatePage />} />
      </Routes>
    </MemoryRouter>,
  )
}

/** A year from now, so the test never depends on real wall-clock "now vs. today" edge cases. */
function futureDateString(): string {
  const date = new Date()
  date.setFullYear(date.getFullYear() + 1)
  return date.toISOString().slice(0, 10)
}

describe('ReservationCreatePage — duration options & validation', () => {
  beforeEach(() => {
    mockCreateMutate.mockClear()
  })

  it('builds duration options as multiples of slotDuration between min and max', () => {
    renderPage()

    const durationSelect = screen.getByLabelText(/이용 시간/) as HTMLSelectElement
    const optionLabels = Array.from(durationSelect.options).map((option) => option.textContent)

    expect(optionLabels).toEqual(['이용 시간을 선택하세요', '30분', '1시간', '1시간 30분'])
  })

  it('blocks moving to the review step until a start time is selected', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '다음' }))

    expect(await screen.findByText('예약 시작 시간을 선택해 주세요.')).toBeInTheDocument()
    expect(screen.queryByText('예약 확정')).not.toBeInTheDocument()
  })

  it('advances to the review step once a future time and duration are chosen, then submits', async () => {
    const user = userEvent.setup()
    renderPage()

    fireEvent.change(screen.getByLabelText(/예약 날짜/), { target: { value: futureDateString() } })
    fireEvent.change(screen.getByLabelText(/시작 시간/), { target: { value: '10:00' } })
    await user.selectOptions(screen.getByLabelText(/이용 시간/), '60')
    await user.click(screen.getByRole('button', { name: '다음' }))

    expect(await screen.findByRole('button', { name: '예약 확정' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '예약 확정' }))

    expect(mockCreateMutate).toHaveBeenCalledWith(
      { resourceId: 5, startAt: `${futureDateString()}T10:00:00`, duration: 60 },
      expect.anything(),
    )
  })
})
