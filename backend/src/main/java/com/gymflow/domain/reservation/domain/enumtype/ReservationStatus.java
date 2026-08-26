package com.gymflow.domain.reservation.domain.enumtype;

import java.util.Set;

public enum ReservationStatus {
    CONFIRMED,
    CHECKED_IN,
    COMPLETED,
    CANCELLED,
    NO_SHOW;

    public static final Set<ReservationStatus> OCCUPYING_STATUSES = Set.of(CONFIRMED, CHECKED_IN);
}
