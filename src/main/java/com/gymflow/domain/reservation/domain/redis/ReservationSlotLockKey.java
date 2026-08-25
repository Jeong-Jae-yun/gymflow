package com.gymflow.domain.reservation.domain.redis;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class ReservationSlotLockKey {

    private static final String KEY_PREFIX = "gymflow:lock:reservation-slot:";
    static final int SLOT_MINUTES = 5;

    private ReservationSlotLockKey() {
    }

    public static String from(Long resourceId, LocalDateTime slotStart) {
        return KEY_PREFIX + resourceId + ":" + slotStart;
    }

    public static List<LocalDateTime> canonicalSlots(LocalDateTime startAt, LocalDateTime endAt) {
        List<LocalDateTime> slots = new ArrayList<>();
        LocalDateTime current = floorToSlot(startAt);
        while (current.isBefore(endAt)) {
            slots.add(current);
            current = current.plusMinutes(SLOT_MINUTES);
        }
        return slots;
    }

    private static LocalDateTime floorToSlot(LocalDateTime time) {
        LocalDateTime truncated = time.truncatedTo(ChronoUnit.MINUTES);
        int remainder = truncated.getMinute() % SLOT_MINUTES;
        return truncated.minusMinutes(remainder);
    }
}
