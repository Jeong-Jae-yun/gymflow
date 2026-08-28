import { apiClient } from './client'
import { toPageableParams, type PageParams } from './pageParams'
import type {
  CancelReservationRequest,
  ReservationCreateRequest,
  ReservationExtensionRequest,
  ReservationResponse,
  SpringPage,
} from '@/types'

export const reservationsApi = {
  create: (payload: ReservationCreateRequest) =>
    apiClient.post<ReservationResponse>('/api/reservations', payload).then((res) => res.data),

  getMyReservations: (params?: PageParams) =>
    apiClient
      .get<SpringPage<ReservationResponse>>('/api/reservations', { params: toPageableParams(params) })
      .then((res) => res.data),

  getDetail: (reservationId: number) =>
    apiClient.get<ReservationResponse>(`/api/reservations/${reservationId}`).then((res) => res.data),

  cancel: (reservationId: number, payload: CancelReservationRequest) =>
    apiClient
      .patch<ReservationResponse>(`/api/reservations/${reservationId}/cancel`, payload)
      .then((res) => res.data),

  extend: (reservationId: number, payload: ReservationExtensionRequest) =>
    apiClient
      .patch<ReservationResponse>(`/api/reservations/${reservationId}/extend`, payload)
      .then((res) => res.data),

  checkIn: (reservationId: number) =>
    apiClient
      .patch<ReservationResponse>(`/api/reservations/${reservationId}/check-in`)
      .then((res) => res.data),

  checkOut: (reservationId: number) =>
    apiClient
      .patch<ReservationResponse>(`/api/reservations/${reservationId}/check-out`)
      .then((res) => res.data),
}
