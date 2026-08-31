/**
 * API base URL. Empty string means "same origin" — requests go through the
 * Vite dev proxy (`/api`) in development and through the same host in production
 * (e.g. behind nginx). Only set VITE_API_BASE_URL when the frontend is hosted
 * separately from the backend.
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

/**
 * WebSocket (STOMP) endpoint. Defaults to same-origin `/ws`, derived at runtime
 * from window.location so it works through the Vite proxy and behind nginx alike.
 */
export function resolveWsUrl(): string {
  const configured = import.meta.env.VITE_WS_URL as string | undefined
  if (configured) {
    return configured
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}
