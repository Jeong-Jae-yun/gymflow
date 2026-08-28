import type { ApiError } from './error'

// Registers ApiError as the default TError for every useQuery/useMutation call
// in the app, since apiClient's response interceptor always rejects with an
// ApiError-shaped object rather than a plain Error. See TanStack Query v5's
// module augmentation docs for the `Register` interface.
declare module '@tanstack/react-query' {
  interface Register {
    defaultError: ApiError
  }
}
