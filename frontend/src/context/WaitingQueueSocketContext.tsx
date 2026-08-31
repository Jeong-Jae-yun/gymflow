import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type { Client } from '@stomp/stompjs'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from './useAuth'
import { useToast } from './useToast'
import { getStoredToken } from '@/utils/tokenStorage'
import { queryKeys } from '@/api/queryKeys'
import { createWaitingQueueStompClient } from '@/websocket/stompClient'
import { WaitingQueueSocketContext } from './waiting-queue-socket-context'
import type { WaitingQueuePromotedEvent } from '@/types'

/**
 * Owns the STOMP connection lifecycle for the whole authenticated session:
 * connects once login is established, reconnects automatically on drops
 * (handled internally by @stomp/stompjs via reconnectDelay), and tears the
 * connection down on logout. HTTP remains the source of truth — on every
 * PROMOTED event we invalidate the waiting-queue query rather than trusting
 * the socket payload as new client state.
 */
export function WaitingQueueSocketProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const clientRef = useRef<Client | null>(null)
  const [connected, setConnected] = useState(false)
  const [latestPromotion, setLatestPromotion] = useState<WaitingQueuePromotedEvent | null>(null)

  useEffect(() => {
    if (status !== 'authenticated') {
      clientRef.current?.deactivate()
      clientRef.current = null
      setConnected(false)
      return
    }

    const token = getStoredToken()
    if (!token) return

    const client = createWaitingQueueStompClient({
      token,
      onConnect: () => setConnected(true),
      onDisconnect: () => setConnected(false),
      onPromoted: (event) => {
        setLatestPromotion(event)
        queryClient.invalidateQueries({ queryKey: queryKeys.waitingQueues.all() })
        showToast({
          tone: 'success',
          title: '대기열에서 승급되었습니다',
          description: '예약 가능한 자리가 생겼습니다. 대기열에서 수락 여부를 선택해 주세요.',
        })
      },
    })
    clientRef.current = client
    client.activate()

    return () => {
      client.deactivate()
      clientRef.current = null
      setConnected(false)
    }
  }, [status, queryClient, showToast])

  return (
    <WaitingQueueSocketContext.Provider value={{ connected, latestPromotion }}>
      {children}
    </WaitingQueueSocketContext.Provider>
  )
}
