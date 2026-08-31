import { Client } from '@stomp/stompjs'
import { resolveWsUrl } from '@/utils/env'
import type { WaitingQueuePromotedEvent } from '@/types'

interface CreateWaitingQueueStompClientParams {
  token: string
  onPromoted: (event: WaitingQueuePromotedEvent) => void
  onConnect?: () => void
  onDisconnect?: () => void
}

/**
 * The backend exposes a single native WebSocket/STOMP endpoint at /ws (no
 * SockJS fallback — see WebSocketConfig), authenticated via an `Authorization:
 * Bearer <token>` STOMP CONNECT header (StompAuthChannelInterceptor), and
 * pushes WaitingQueuePromotedEvent to the per-user destination
 * `/user/queue/waiting-queue` (WaitingQueueDestination.WAITING_QUEUE_QUEUE via
 * convertAndSendToUser). There are no other server destinations to subscribe
 * to and no client-to-server (/app/**) destinations in use.
 */
export function createWaitingQueueStompClient({
  token,
  onPromoted,
  onConnect,
  onDisconnect,
}: CreateWaitingQueueStompClientParams): Client {
  const client = new Client({
    brokerURL: resolveWsUrl(),
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      onConnect?.()
      client.subscribe('/user/queue/waiting-queue', (message) => {
        try {
          const event = JSON.parse(message.body) as WaitingQueuePromotedEvent
          onPromoted(event)
        } catch {
          // Ignore malformed payloads rather than crashing the socket handler.
        }
      })
    },
    onWebSocketClose: () => onDisconnect?.(),
  })

  return client
}
