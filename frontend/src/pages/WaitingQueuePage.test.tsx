import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { WaitingQueuePage } from './WaitingQueuePage'
import type { WaitingQueueResponse } from '@/types'

const mockAcceptMutate = vi.fn()
const mockRejectMutate = vi.fn()
const mockCancelMutate = vi.fn()

const promotedItem: WaitingQueueResponse = {
  waitingQueueId: 10,
  resourceId: 5,
  startAt: '2026-08-28T14:00:00',
  endAt: '2026-08-28T14:30:00',
  status: 'PROMOTED',
  createdAt: '2026-08-28T10:00:00',
  waitingRank: null,
  promotionId: 42,
}

const waitingItem: WaitingQueueResponse = {
  waitingQueueId: 11,
  resourceId: 6,
  startAt: '2026-08-28T15:00:00',
  endAt: '2026-08-28T15:30:00',
  status: 'WAITING',
  createdAt: '2026-08-28T10:05:00',
  waitingRank: 2,
  promotionId: null,
}

/**
 * Represents a PROMOTED WaitingQueue whose Promotion has already been accepted/rejected —
 * the backend stops returning promotionId once the Promotion leaves OFFERED, which is what
 * keeps this card from showing actionable accept/reject buttons again after a refresh.
 */
const resolvedPromotionItem: WaitingQueueResponse = {
  waitingQueueId: 12,
  resourceId: 7,
  startAt: '2026-08-28T16:00:00',
  endAt: '2026-08-28T16:30:00',
  status: 'PROMOTED',
  createdAt: '2026-08-28T10:10:00',
  waitingRank: null,
  promotionId: null,
}

let mockWaitingQueueContent: WaitingQueueResponse[] = [promotedItem, waitingItem]

vi.mock('@/features/waitingQueue/hooks', () => ({
  useMyWaitingQueues: () => ({
    data: { content: mockWaitingQueueContent, totalPages: 1, number: 0 },
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  }),
  useCancelWaitingQueue: () => ({ mutate: mockCancelMutate, isPending: false }),
}))

vi.mock('@/features/promotions/hooks', () => ({
  useAcceptPromotion: () => ({ mutate: mockAcceptMutate, isPending: false }),
  useRejectPromotion: () => ({ mutate: mockRejectMutate, isPending: false }),
}))

vi.mock('@/features/resources/useResourcesByIds', () => ({
  useResourcesByIds: () => new Map(),
}))

vi.mock('@/context/useWaitingQueueSocket', () => ({
  useWaitingQueueSocket: () => ({ connected: true, latestPromotion: null }),
}))

vi.mock('@/context/useToast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))

describe('WaitingQueuePage — PROMOTED handling', () => {
  beforeEach(() => {
    mockAcceptMutate.mockClear()
    mockRejectMutate.mockClear()
    mockWaitingQueueContent = [promotedItem, waitingItem]
  })

  it('shows accept/reject actions only for the PROMOTED entry, using its promotionId', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <WaitingQueuePage />
      </MemoryRouter>,
    )

    expect(screen.getByText('승급됨')).toBeInTheDocument()
    expect(screen.getByText('대기 중')).toBeInTheDocument()

    const acceptButton = screen.getByRole('button', { name: '수락' })
    await user.click(acceptButton)

    expect(mockAcceptMutate).toHaveBeenCalledTimes(1)
    expect(mockAcceptMutate).toHaveBeenCalledWith(42, expect.anything())
  })

  it('sends the waiting-list entry (not the promoted one) through the plain cancel flow', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <WaitingQueuePage />
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: '대기열 나가기' }))
    await user.click(screen.getByRole('button', { name: '나가기' }))

    expect(mockCancelMutate).toHaveBeenCalledWith(11, expect.anything())
  })

  it('does not show accept/reject actions for a PROMOTED entry whose Promotion is already resolved (no promotionId)', () => {
    mockWaitingQueueContent = [resolvedPromotionItem]
    render(
      <MemoryRouter>
        <WaitingQueuePage />
      </MemoryRouter>,
    )

    // The card still reflects the historical "승급됨" status, but since accepting/rejecting
    // is no longer possible (backend omitted promotionId), the action buttons must not appear —
    // this is what prevents an already-accepted/rejected Promotion from re-surfacing after a refresh.
    expect(screen.getByText('승급됨')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '수락' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '거절' })).not.toBeInTheDocument()
  })
})
