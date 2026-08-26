package com.gymflow.domain.resource.domain.entity;

import com.gymflow.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reservation_policies",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_policy_resource",
                columnNames = "resource_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationPolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "resource_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_reservation_policy_resource")
    )
    private Resource resource;

    @Column(name = "slot_duration", nullable = false)
    private Integer slotDuration;

    @Column(name = "min_duration", nullable = false)
    private Integer minDuration;

    @Column(name = "max_duration", nullable = false)
    private Integer maxDuration;

    @Builder
    public ReservationPolicy(Resource resource, Integer slotDuration, Integer minDuration, Integer maxDuration) {
        validateResource(resource);
        validateDurations(slotDuration, minDuration, maxDuration);

        this.resource = resource;
        this.slotDuration = slotDuration;
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;

        resource.assignReservationPolicy(this);
    }

    public void update(Integer slotDuration, Integer minDuration, Integer maxDuration) {
        validateDurations(slotDuration, minDuration, maxDuration);
        this.slotDuration = slotDuration;
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
    }

    private static void validateResource(Resource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("ReservationPolicy는 Resource 없이 생성할 수 없습니다.");
        }
    }

    private static void validateDurations(Integer slotDuration, Integer minDuration, Integer maxDuration) {
        requirePositive(slotDuration, "slotDuration");
        requirePositive(minDuration, "minDuration");
        requirePositive(maxDuration, "maxDuration");

        if (minDuration > maxDuration) {
            throw new IllegalArgumentException("minDuration은 maxDuration보다 클 수 없습니다.");
        }
    }

    private static void requirePositive(Integer value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + "은(는) 1분 이상이어야 합니다.");
        }
    }
}
