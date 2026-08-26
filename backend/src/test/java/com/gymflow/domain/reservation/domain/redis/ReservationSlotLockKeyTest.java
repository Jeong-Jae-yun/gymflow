package com.gymflow.domain.reservation.domain.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationSlotLockKeyTest {

    private static final Long RESOURCE_ID = 101L;

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 8, 20, hour, minute);
    }

    @Test
    @DisplayName("14:00~14:15는 14:00, 14:05, 14:10 세 개의 5분 슬롯으로 분해된다")
    void canonicalSlots_With15MinuteRange_ShouldReturnThreeSlots() {
        List<LocalDateTime> slots = ReservationSlotLockKey.canonicalSlots(at(14, 0), at(14, 15));

        assertThat(slots).containsExactly(at(14, 0), at(14, 5), at(14, 10));
    }

    @Test
    @DisplayName("14:05~14:20는 14:05, 14:10, 14:15 세 개의 5분 슬롯으로 분해된다")
    void canonicalSlots_WithOffsetRange_ShouldReturnThreeSlots() {
        List<LocalDateTime> slots = ReservationSlotLockKey.canonicalSlots(at(14, 5), at(14, 20));

        assertThat(slots).containsExactly(at(14, 5), at(14, 10), at(14, 15));
    }

    @Test
    @DisplayName("14:00~14:05는 14:00 하나의 슬롯으로 분해된다")
    void canonicalSlots_With5MinuteRange_ShouldReturnSingleSlot() {
        List<LocalDateTime> slots = ReservationSlotLockKey.canonicalSlots(at(14, 0), at(14, 5));

        assertThat(slots).containsExactly(at(14, 0));
    }

    @Test
    @DisplayName("맞닿아 있을 뿐 겹치지 않는 14:00~14:15와 14:15~14:30은 슬롯을 공유하지 않는다")
    void canonicalSlots_WithAdjacentRanges_ShouldNotShareAnySlot() {
        List<LocalDateTime> first = ReservationSlotLockKey.canonicalSlots(at(14, 0), at(14, 15));
        List<LocalDateTime> second = ReservationSlotLockKey.canonicalSlots(at(14, 15), at(14, 30));

        assertThat(Set.copyOf(first)).doesNotContainAnyElementsOf(second);
    }

    @Test
    @DisplayName("겹치는 14:00~14:30와 14:20~14:35는 최소 하나의 슬롯을 공유한다")
    void canonicalSlots_WithOverlappingRanges_ShouldShareAtLeastOneSlot() {
        List<LocalDateTime> first = ReservationSlotLockKey.canonicalSlots(at(14, 0), at(14, 30));
        List<LocalDateTime> second = ReservationSlotLockKey.canonicalSlots(at(14, 20), at(14, 35));

        Set<LocalDateTime> shared = first.stream()
                .filter(second::contains)
                .collect(Collectors.toSet());

        assertThat(shared).containsExactlyInAnyOrder(at(14, 20), at(14, 25));
    }

    @Test
    @DisplayName("startAt/endAt이 5분 단위로 정렬되어 있지 않아도 실제로 겹치는 구간은 슬롯을 공유한다")
    void canonicalSlots_WithUnalignedSeconds_ShouldStillShareSlotWhenOverlapping() {
        LocalDateTime unalignedStart = at(14, 0).plusSeconds(37);
        LocalDateTime extendStart = unalignedStart.plusMinutes(15); // 실제 연장 delta 시작
        LocalDateTime extendEnd = extendStart.plusMinutes(15);
        LocalDateTime concurrentCreateStart = extendStart.plusMinutes(5);
        LocalDateTime concurrentCreateEnd = concurrentCreateStart.plusMinutes(15);

        List<LocalDateTime> extendSlots = ReservationSlotLockKey.canonicalSlots(extendStart, extendEnd);
        List<LocalDateTime> createSlots = ReservationSlotLockKey.canonicalSlots(concurrentCreateStart, concurrentCreateEnd);

        boolean sharesSlot = extendSlots.stream().anyMatch(createSlots::contains);
        assertThat(sharesSlot).isTrue();
    }

    @Test
    @DisplayName("서로 다른 Resource의 Lock Key는 동일 시간대라도 다르다")
    void from_WithDifferentResources_ShouldProduceDifferentKeys() {
        String key1 = ReservationSlotLockKey.from(RESOURCE_ID, at(14, 0));
        String key2 = ReservationSlotLockKey.from(202L, at(14, 0));

        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).contains("gymflow:lock:reservation-slot:").contains(RESOURCE_ID.toString());
    }
}
