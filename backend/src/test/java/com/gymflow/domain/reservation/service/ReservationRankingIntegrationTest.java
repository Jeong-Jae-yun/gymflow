package com.gymflow.domain.reservation.service;

import com.gymflow.TestcontainersConfiguration;
import com.gymflow.domain.reservation.dto.request.ReservationCreateRequest;
import com.gymflow.domain.resource.domain.entity.ReservationPolicy;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.redis.ResourceRankingRedisRepository;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.domain.user.domain.repository.UserRepository;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Testcontainers MySQL + Redis를 사용해 "Reservation MySQL 저장 성공 -> Redis
 * Ranking score +1"이 실제로 연결되어 있는지 검증한다. Redis Ranking 장애 시 Reservation
 * 생성이 실패하지 않는지는 여러 SpringBootTest가 공유하는 Testcontainers Redis 컨테이너를
 * 직접 중단시키는 위험을 피하기 위해 Mockito 기반 ReservationServiceTest에서 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationRankingIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ResourceRankingRedisRepository resourceRankingRedisRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Long persistUser(String email) {
        User user = User.builder()
                .email(email)
                .password("securePassword123")
                .name("Ranking E2E Tester")
                .build();
        return userRepository.save(user).getId();
    }

    private Long persistResourceWithPolicy(String name) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReservationPolicy.builder()
                .resource(resource)
                .slotDuration(15)
                .minDuration(15)
                .maxDuration(60)
                .build();
        return resourceRepository.save(resource).getId();
    }

    private void authenticateAs(Long userId) {
        CustomUserDetails principal = new CustomUserDetails(userId, "user-" + userId + "@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    @DisplayName("동일 Resource에 예약을 여러 번 성공시키면 Redis Ranking score가 성공 횟수만큼 누적된다")
    void createReservation_WithMultipleSuccessfulReservations_ShouldAccumulateRankingScore() {
        // given
        Long resourceId = persistResourceWithPolicy("Ranking E2E Resource " + System.nanoTime());
        Long userId = persistUser("ranking-e2e-" + System.nanoTime() + "@gymflow.com");
        LocalDateTime baseStartAt =
                LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        int successfulReservationCount = 4;

        // when: 서로 겹치지 않는 시간대로 동일 Resource에 예약을 4번 성공시킨다
        authenticateAs(userId);
        for (int i = 0; i < successfulReservationCount; i++) {
            LocalDateTime startAt = baseStartAt.plusMinutes(15L * i);
            reservationService.createReservation(new ReservationCreateRequest(resourceId, startAt, 15));
        }

        // then
        assertThat(resourceRankingRedisRepository.findScore(resourceId)).contains((long) successfulReservationCount);
    }
}
