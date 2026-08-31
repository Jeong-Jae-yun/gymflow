import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ReservationCreatePage } from './ReservationCreatePage'
import type { ResourceAvailabilityResponse, ResourceResponse } from '@/types'

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

/** A year from now, so the test never depends on real wall-clock "now vs. today" edge cases. */
function futureDateString(): string {
  const date = new Date()
  date.setFullYear(date.getFullYear() + 1)
  return date.toISOString().slice(0, 10)
}

function availabilityFor(date: string): ResourceAvailabilityResponse {
  return {
    resourceId: 5,
    date,
    slotDuration: 30,
    minDuration: 30,
    maxDuration: 90,
    slots: [
      { startAt: `${date}T09:00:00`, endAt: `${date}T09:30:00`, available: true },
      { startAt: `${date}T09:30:00`, endAt: `${date}T10:00:00`, available: false },
      { startAt: `${date}T10:00:00`, endAt: `${date}T10:30:00`, available: true },
      { startAt: `${date}T14:00:00`, endAt: `${date}T14:30:00`, available: true },
    ],
  }
}

let mockAvailabilityData: ResourceAvailabilityResponse | undefined

vi.mock('@/features/resources/hooks', () => ({
  useResourceDetail: () => ({ data: resource, isPending: false, isError: false, refetch: vi.fn() }),
  useResourceAvailability: () => ({
    data: mockAvailabilityData,
    isPending: mockAvailabilityData === undefined,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
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

describe('ReservationCreatePage — slot selection & duration options', () => {
  beforeEach(() => {
    mockCreateMutate.mockClear()
    mockAvailabilityData = availabilityFor(futureDateString())
  })

  it('builds duration options as multiples of slotDuration between min and max', () => {
    renderPage()

    const durationSelect = screen.getByLabelText(/이용 시간/) as HTMLSelectElement
    const optionLabels = Array.from(durationSelect.options).map((option) => option.textContent)

    expect(optionLabels).toEqual(['이용 시간을 선택하세요', '30분', '1시간', '1시간 30분'])
  })

  it('renders available slots as clickable and unavailable slots as disabled', () => {
    renderPage()

    expect(screen.getByRole('button', { name: '09:00' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '09:30' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '10:00' })).toBeEnabled()
  })

  it('shows a loading skeleton while availability is pending', () => {
    mockAvailabilityData = undefined
    renderPage()

    expect(screen.queryByRole('button', { name: '09:00' })).not.toBeInTheDocument()
  })

  it('blocks moving to the review step until a start time is selected', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '다음' }))

    expect(await screen.findByText('예약 시작 시간을 선택해 주세요.')).toBeInTheDocument()
    expect(screen.queryByText('예약 확정')).not.toBeInTheDocument()
  })

  it('advances to the review step once a slot and duration are chosen, then submits', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '10:00' }))
    await user.selectOptions(screen.getByLabelText(/이용 시간/), '60')
    await user.click(screen.getByRole('button', { name: '다음' }))

    expect(await screen.findByRole('button', { name: '예약 확정' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '예약 확정' }))

    expect(mockCreateMutate).toHaveBeenCalledWith(
      { resourceId: 5, startAt: `${futureDateString()}T10:00:00`, duration: 60 },
      expect.anything(),
    )
  })

  it('does not allow selecting a disabled (unavailable) slot', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '09:30' }))
    await user.selectOptions(screen.getByLabelText(/이용 시간/), '30')
    await user.click(screen.getByRole('button', { name: '다음' }))

    expect(await screen.findByText('예약 시작 시간을 선택해 주세요.')).toBeInTheDocument()
  })
})
