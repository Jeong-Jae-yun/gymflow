import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { reservationsApi } from '@/api'
import { queryKeys } from '@/api/queryKeys'
import type { PageParams } from '@/api/pageParams'
import type { CancelReservationRequest, ReservationCreateRequest, ReservationExtensionRequest } from '@/types'

export function useMyReservations(params?: PageParams) {
  return useQuery({
    queryKey: queryKeys.reservations.list(params),
    queryFn: () => reservationsApi.getMyReservations(params),
  })
}

export function useReservationDetail(reservationId: number) {
  return useQuery({
    queryKey: queryKeys.reservations.detail(reservationId),
    queryFn: () => reservationsApi.getDetail(reservationId),
    enabled: Number.isFinite(reservationId),
  })
}

export function useCreateReservation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: ReservationCreateRequest) => reservationsApi.create(payload),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.reservations.all() }),
  })
}

function useReservationTransition() {
  const queryClient = useQueryClient()
  return (reservationId: number) => {
    queryClient.invalidateQueries({ queryKey: queryKeys.reservations.all() })
    queryClient.invalidateQueries({ queryKey: queryKeys.reservations.detail(reservationId) })
  }
}

export function useCancelReservation() {
  const queryClient = useQueryClient()
  const onTransition = useReservationTransition()
  return useMutation({
    mutationFn: ({ reservationId, payload }: { reservationId: number; payload: CancelReservationRequest }) =>
      reservationsApi.cancel(reservationId, payload),
    onSuccess: (_data, variables) => onTransition(variables.reservationId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.waitingQueues.all() }),
  })
}

export function useExtendReservation() {
  const onTransition = useReservationTransition()
  return useMutation({
    mutationFn: ({ reservationId, payload }: { reservationId: number; payload: ReservationExtensionRequest }) =>
      reservationsApi.extend(reservationId, payload),
    onSuccess: (_data, variables) => onTransition(variables.reservationId),
  })
}

export function useCheckInReservation() {
  const onTransition = useReservationTransition()
  return useMutation({
    mutationFn: (reservationId: number) => reservationsApi.checkIn(reservationId),
    onSuccess: (_data, reservationId) => onTransition(reservationId),
  })
}

export function useCheckOutReservation() {
  const queryClient = useQueryClient()
  const onTransition = useReservationTransition()
  return useMutation({
    mutationFn: (reservationId: number) => reservationsApi.checkOut(reservationId),
    onSuccess: (_data, reservationId) => {
      onTransition(reservationId)
      queryClient.invalidateQueries({ queryKey: queryKeys.usageHistories.list() })
      queryClient.invalidateQueries({ queryKey: queryKeys.usageHistories.statistics() })
    },
  })
}
