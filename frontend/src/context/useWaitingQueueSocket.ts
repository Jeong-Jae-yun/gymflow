import { useContext } from 'react'
import { WaitingQueueSocketContext } from './waiting-queue-socket-context'
import type { WaitingQueueSocketContextValue } from './waiting-queue-socket-context'

export function useWaitingQueueSocket(): WaitingQueueSocketContextValue {
  return useContext(WaitingQueueSocketContext)
}
