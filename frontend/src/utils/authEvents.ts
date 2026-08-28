/**
 * Decouples the Axios client (outside React) from AuthContext (inside React).
 * When a 401 is received on an already-authenticated request, the client
 * dispatches this event; AuthProvider listens for it, clears the session, and
 * redirects to /login. We avoid a naive retry loop since there is no refresh
 * token to retry with.
 */
export const UNAUTHORIZED_EVENT = 'gymflow:unauthorized'

export function dispatchUnauthorized(): void {
  window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
}
