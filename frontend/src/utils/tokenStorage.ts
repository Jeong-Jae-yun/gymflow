/**
 * Access-token persistence.
 *
 * The backend issues a stateless JWT access token with no refresh token and no
 * logout endpoint (see AuthController / UserController). We persist it in
 * localStorage rather than sessionStorage or in-memory state so a page reload
 * (a normal action during manual QA/demo of a reservation flow) does not force
 * a fresh login. The tradeoff is that localStorage is readable by any script
 * running on the page, so it is vulnerable if the app were ever compromised by
 * an XSS injection. We mitigate that by never using dangerouslySetInnerHTML,
 * relying on React's default escaping everywhere, and keeping the token
 * lifetime under the backend's configured JWT_ACCESS_TOKEN_EXPIRATION. This
 * choice is documented for the reader rather than hidden.
 */
const ACCESS_TOKEN_KEY = 'gymflow.accessToken'

export function getStoredToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function setStoredToken(token: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, token)
}

export function clearStoredToken(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
}
