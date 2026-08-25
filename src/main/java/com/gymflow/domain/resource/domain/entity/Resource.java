package com.gymflow.domain.resource.domain.entity;

import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "resources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Resource extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ResourceType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ResourceStatus status;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "description", length = 500)
    private String description;

    @OneToOne(mappedBy = "resource", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private ReservationPolicy reservationPolicy;

    @Builder
    public Resource(String name, ResourceType type, Integer capacity, String description) {
        validateCapacity(capacity);
        this.name = name;
        this.type = type;
        this.capacity = capacity;
        this.description = description;
        this.status = ResourceStatus.ACTIVE;
    }

    void assignReservationPolicy(ReservationPolicy reservationPolicy) {
        this.reservationPolicy = reservationPolicy;
    }

    public void update(String name, Integer capacity, String description) {
        validateCapacity(capacity);
        this.name = name;
        this.capacity = capacity;
        this.description = description;
    }

    public void changeStatus(ResourceStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status는 필수입니다.");
        }
        this.status = status;
    }

    private static void validateCapacity(Integer capacity) {
        if (capacity == null || capacity < 1) {
            throw new IllegalArgumentException("capacity는 1 이상이어야 합니다.");
        }
    }
}
