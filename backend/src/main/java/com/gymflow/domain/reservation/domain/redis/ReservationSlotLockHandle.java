package com.gymflow.domain.reservation.domain.redis;

import java.util.List;

public record ReservationSlotLockHandle(List<SlotLock> slotLocks) {

    public record SlotLock(String redisKey, String token) {
    }
}
