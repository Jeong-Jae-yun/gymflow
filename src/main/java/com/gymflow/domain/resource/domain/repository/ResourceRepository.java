package com.gymflow.domain.resource.domain.repository;

import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

    @EntityGraph(attributePaths = "reservationPolicy")
    Optional<Resource> findWithReservationPolicyById(Long id);

    List<Resource> findAllByStatus(ResourceStatus status);
}
