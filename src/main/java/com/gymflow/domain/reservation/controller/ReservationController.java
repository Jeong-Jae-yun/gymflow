package com.gymflow.domain.reservation.controller;

import com.gymflow.domain.reservation.dto.request.CancelReservationRequest;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.reservation.dto.request.ReservationExtensionRequest;
import com.gymflow.domain.reservation.dto.response.ReservationResponse;
import com.gymflow.domain.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public ResponseEntity<ReservationResponse> create(@Valid @RequestBody ReservationCreateRequest request) {
        ReservationResponse response = reservationService.createReservation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<ReservationResponse>> getMyReservations(
            @PageableDefault(sort = "startAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<ReservationResponse> response = reservationService.getMyReservations(pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationResponse> getMyReservationDetail(@PathVariable Long reservationId) {
        ReservationResponse response = reservationService.getMyReservationDetail(reservationId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{reservationId}/cancel")
    public ResponseEntity<ReservationResponse> cancel(
            @PathVariable Long reservationId, @Valid @RequestBody CancelReservationRequest request) {
        ReservationResponse response = reservationService.cancelReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{reservationId}/extend")
    public ResponseEntity<ReservationResponse> extend(
            @PathVariable Long reservationId, @Valid @RequestBody ReservationExtensionRequest request) {
        ReservationResponse response = reservationService.extendReservation(reservationId, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{reservationId}/check-in")
    public ResponseEntity<ReservationResponse> checkIn(@PathVariable Long reservationId) {
        ReservationResponse response = reservationService.checkInReservation(reservationId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{reservationId}/check-out")
    public ResponseEntity<ReservationResponse> checkOut(@PathVariable Long reservationId) {
        ReservationResponse response = reservationService.checkOutReservation(reservationId);
        return ResponseEntity.ok(response);
    }
}
