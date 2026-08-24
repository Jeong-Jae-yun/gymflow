package com.gymflow.domain.resource.domain.repository;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ResourceRepositoryTest {

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Resource persistResource(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        entityManager.persist(resource);
        return resource;
    }

    private void persistReservationPolicy(Resource resource) {
        ReservationPolicy policy = ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(60)
                .build();
        entityManager.persist(policy);
    }

    @Test
    @DisplayName("Resource 목록을 Page 형태로 조회하며 ReservationPolicy도 함께 조회된다")
    void findAll_ShouldReturnPageOfResourcesWithReservationPolicy() {
        // given
        Resource resource = persistResource("Chest Press A-1");
        persistReservationPolicy(resource);
        entityManager.flush();
        entityManager.clear();

        // when
        Page<Resource> page = resourceRepository.findAll(PageRequest.of(0, 10));

        // then
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getReservationPolicy()).isNotNull();
        assertThat(page.getContent().get(0).getReservationPolicy().getMaxDuration()).isEqualTo(60);
    }

    @Test
    @DisplayName("Resource 상세를 ReservationPolicy와 함께 조회한다")
    void findWithReservationPolicyById_ShouldReturnResourceWithPolicy() {
        // given
        Resource resource = persistResource("Chest Press A-1");
        persistReservationPolicy(resource);
        entityManager.flush();
        entityManager.clear();

        // when
        Optional<Resource> found = resourceRepository.findWithReservationPolicyById(resource.getId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getReservationPolicy()).isNotNull();
        assertThat(found.get().getReservationPolicy().getSlotDuration()).isEqualTo(15);
    }

    @Test
    @DisplayName("존재하지 않는 ID로 조회하면 빈 값을 반환한다")
    void findWithReservationPolicyById_WithNonExistentId_ShouldReturnEmpty() {
        // when
        Optional<Resource> found = resourceRepository.findWithReservationPolicyById(999_999L);

        // then
        assertThat(found).isEmpty();
    }
}
