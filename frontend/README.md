# GymFlow Frontend

Production-quality web client for the GymFlow reservation platform, built directly against the
actual GymFlow Backend API contract (Spring Boot, `backend/`). No endpoint, DTO shape, or enum
value in this app is invented — everything is modeled from `backend/src/main/java`.

## Stack

- **React 18 + TypeScript (strict)** — UI and type system
- **Vite** — dev server / production bundler
- **React Router v6** — routing, nested layouts, route guards
- **TanStack Query v5** — server state, caching, mutations
- **Axios** — HTTP client with a typed error-normalization layer
- **@stomp/stompjs** — STOMP over native WebSocket for waiting-queue promotion events
- **Tailwind CSS v4** — design tokens + utility styling (CSS-first `@theme`, no `tailwind.config.js`)
- **React Hook Form** — form state/validation
- **date-fns** — date formatting (Korean locale)
- **lucide-react** — icon system
- **Vitest + React Testing Library** — unit/behavior tests

Dependencies were kept deliberately minimal: no Redux (TanStack Query covers server state, a
small `AuthContext` covers session state), no Zod (backend validation rules are simple enough for
plain `react-hook-form` rules), no chart library (statistics are small aggregate numbers, not time
series — a chart would need fabricated granularity the API doesn't provide).

## Getting started

```bash
npm install
npm run dev       # http://localhost:5173, proxies /api and /ws to the local backend
```

### Local backend connection

The dev server proxies `/api/**` and `/ws` to `http://localhost:8080` (see `vite.config.ts`), so
the app talks to the backend without any CORS configuration on the backend side (the backend's
`SecurityConfig` does not enable CORS at all — same-origin via proxy is the only way this works in
dev without touching backend code). Start the backend separately, e.g.:

```bash
cd ../backend
./gradlew bootRun
```

To point the dev proxy at a different backend host/port, set `VITE_BACKEND_TARGET` before running
`npm run dev` (this variable is read by `vite.config.ts` itself and is never bundled into the
client).

### Environment variables

See `.env.example`. Both variables are optional:

- `VITE_API_BASE_URL` — only needed if the frontend is hosted on a different origin than the
  backend. Defaults to same-origin (`''`), which works through the Vite proxy in dev and behind
  the project's nginx reverse proxy in production.
- `VITE_WS_URL` — same idea for the STOMP endpoint; defaults to `ws(s)://<current host>/ws`.

Vite bundles every `VITE_`-prefixed variable into the client build, so nothing secret can live
here — there is nothing secret in this app to begin with (no API keys, no AWS/DB credentials).

### Production API strategy

The app never hardcodes an EC2 IP or any specific deployment host. Production is expected to serve
the built frontend from the same origin as the backend (the repository's `nginx` config already
reverse-proxies `/` and `/ws` to the backend container), so relative `/api` and `/ws` requests
resolve correctly with zero configuration. This frontend build was not deployed as part of this
task — only implemented and locally verified.

## Scripts

```bash
npm run dev        # start dev server
npm run build       # tsc -b && vite build (fails the build on any type error)
npm run preview     # preview the production build locally
npm run lint         # eslint .
npm test             # vitest (watch mode)
npm run test:run     # vitest run (single pass, used in CI/verification)
```

## Architecture

```
src/
  api/          axios client, per-domain API modules, query key factory, error normalization
  components/   generic reusable UI (components/ui) + a few cross-feature widgets
  context/      AuthContext, ToastContext, WaitingQueueSocketContext (STOMP lifecycle)
  features/     feature-scoped hooks/components (resources, reservations, waitingQueue,
                promotions, favorites, usageHistory, admin) — colocated by domain, not by type
  layouts/      AuthLayout (public), AppLayout (user shell), AdminLayout (distinct admin shell)
  pages/        route-level page components (lazy-loaded)
  routes/       ProtectedRoute, AdminRoute, PublicOnlyRoute guards
  types/        TypeScript models mirroring backend DTOs/enums 1:1
  utils/        date formatting, enum→label maps, className helper, error message helper
  websocket/    STOMP client factory
```

### Authentication

`POST /api/auth/login` → `{ accessToken, tokenType }` → token stored → `GET /api/users/me` →
`AuthContext` state. There is no refresh token and no logout endpoint in the backend, so logout is
purely local: clear the token and reset the TanStack Query cache.

**Token storage: `localStorage`.** Chosen over `sessionStorage`/in-memory so a page reload during a
reservation flow doesn't force a fresh login (there's no refresh token to silently re-auth with).
The tradeoff is that `localStorage` is readable by any script on the page if the app were ever
compromised by XSS — mitigated by never using `dangerouslySetInnerHTML`, relying on React's default
escaping everywhere, and keeping the token lifetime bounded by the backend's
`JWT_ACCESS_TOKEN_EXPIRATION` setting. This is a deliberate, documented tradeoff, not an oversight.

A 401 on any authenticated request (not the login request itself) clears the session and redirects
to `/login` — there is no retry loop, since there is nothing to refresh.

### TanStack Query

One query key factory (`src/api/queryKeys.ts`) for the whole app. Mutations invalidate only the
query families they actually affect (e.g. cancelling a reservation invalidates reservations *and*
waiting queues, since cancellation can trigger a promotion; nothing else is touched).

### Route guards

`ProtectedRoute` and `AdminRoute` are UX conveniences — they redirect unauthenticated/non-admin
users away from pages that would otherwise 401/403. They are not the security boundary; the backend
(`SecurityConfig`, `hasRole("ADMIN")` on `/api/admin/**`) is.

### WebSocket / STOMP

Connects to `/ws` (native WebSocket, no SockJS — matches `WebSocketConfig`), sends
`Authorization: Bearer <token>` on `CONNECT` (matches `StompAuthChannelInterceptor`), subscribes to
`/user/queue/waiting-queue`. On a `WaitingQueuePromotedEvent`, the client invalidates the
waiting-queue query (HTTP stays the source of truth) and shows a toast; it never treats the socket
payload as authoritative client state. Reconnects automatically (`reconnectDelay`); the connection
is torn down on logout via `WaitingQueueSocketContext`.

## Known Frontend Integration Limitations

These are cases where the ideal UI would need a backend capability that doesn't exist. The backend
was not modified to work around them; each is degraded gracefully instead:

1. **No favorites-with-resource-details endpoint.** `GET /api/favorites` only returns
   `{ favoriteId, resourceId, createdAt }`. The Favorites page fans out one
   `GET /api/resources/{id}` per favorite (via `useQueries`) to show a real resource card. Same
   pattern applies to Reservation/WaitingQueue/UsageHistory list rows, which only carry
   `resourceId` and are enriched the same way, bounded by page size.
2. **No admin resource list endpoint.** `AdminResourceController` only exposes single-resource
   routes (`GET/PUT/PATCH /api/admin/resources/{id}`, plus create). The Admin Resource table reuses
   the public `GET /api/resources` listing, since an ADMIN is also a normal authenticated user for
   every non-admin endpoint.
3. **No reservation availability/calendar endpoint.** The backend only validates a submitted
   `(resourceId, startAt, duration)` on `POST /api/reservations`; there is no "show me open slots"
   API. The reservation flow lets the user pick any date/time/duration within the resource's
   policy and relies on the server's conflict response (`409 RESERVATION_TIME_CONFLICT` /
   `RESERVATION_PROMOTION_RESERVED`) to surface unavailability, at which point the UI offers
   waiting-queue registration for that exact slot.
4. **No profile-update endpoint.** `UserController` only has `POST /signup` and `GET /me`, so the
   Profile page is intentionally read-only.
5. **No logout/refresh-token endpoint.** See the Token storage note above.

## Testing

`npm run test:run` executes the Vitest suite (jsdom environment, React Testing Library). Coverage
focuses on behavior with real consequences rather than snapshot noise: token/session handling,
route guards, API error normalization, date formatting, resource image fallback, admin image
client-side validation, and the waiting-queue PROMOTED + `promotionId` accept/reject path.

### E2E

No Playwright/Cypress suite was added — for this app's current size, the Vitest/RTL coverage above
plus the manual browser checklist below cover the meaningful risk more cheaply than standing up a
browser automation pipeline. If the app grows (more write flows, more cross-page state), Playwright
covering the reservation → conflict → waiting-queue → promotion happy path would be the first
addition.

## Manual browser verification checklist

Run the backend locally (`cd backend && ./gradlew bootRun`, requires MySQL/Redis — see
`compose.yaml`) and the frontend (`npm run dev`), then walk through:

- [ ] Sign up, log in, log out
- [ ] `GET /api/users/me` reflected on the Profile page
- [ ] Resource list, detail, image rendering + fallback when `imageUrl` is null
- [ ] Add/remove a favorite from both the list and detail views
- [ ] Create a reservation (happy path), then force a conflict (book the same slot twice) and
      register for the waiting queue from the conflict screen
- [ ] Reservation list/detail: check-in, check-out, extend, cancel — actions only appear when the
      current status allows them
- [ ] Waiting queue list shows position; open two browser sessions (or a second reservation) to
      trigger a promotion and confirm the toast + accept/reject flow over the live WebSocket
      connection
- [ ] Usage history + statistics after completing a reservation (check-in → check-out)
- [ ] As a USER, confirm `/admin` redirects away
- [ ] As an ADMIN, manage resources: create, edit, change status, upload/replace/delete image,
      view statistics
- [ ] Stop the backend and confirm the frontend shows a network-error state instead of a blank
      screen
- [ ] Resize to mobile/tablet/desktop widths and confirm no horizontal overflow, nav collapses,
      and touch targets stay usable
