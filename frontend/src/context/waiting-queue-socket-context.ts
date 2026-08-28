import { createContext } from 'react'
import type { WaitingQueuePromotedEvent } from '@/types'

export interface WaitingQueueSocketContextValue {
  connected: boolean
  latestPromotion: WaitingQueuePromotedEvent | null
}

export const WaitingQueueSocketContext = createContext<WaitingQueueSocketContextValue>({
  connected: false,
  latestPromotion: null,
})
