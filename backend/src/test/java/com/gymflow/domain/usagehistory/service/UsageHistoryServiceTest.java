package com.gymflow.domain.usagehistory.service;

import com.gymflow.domain.reservation.domain.entity.Reservation;
import com.gymflow.domain.resource.domain.entity.Resource;
import com.gymflow.domain.resource.domain.enumtype.ResourceStatus;
import com.gymflow.domain.resource.domain.enumtype.ResourceType;
import com.gymflow.domain.resource.domain.repository.ResourceRepository;
import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.UsageHistoryRepository;
import com.gymflow.domain.usagehistory.domain.repository.projection.ResourceUsageStatisticsProjection;
import com.gymflow.domain.usagehistory.domain.repository.projection.UsageSummaryProjection;
import com.gymflow.domain.usagehistory.dto.response.AdminResourceUsageStatisticsResponse;
import com.gymflow.domain.usagehistory.dto.response.UsageStatisticsResponse;
import com.gymflow.domain.user.domain.entity.User;
import com.gymflow.domain.user.domain.enumtype.UserRole;
import com.gymflow.global.common.dto.PageResponse;
import com.gymflow.global.common.exception.BusinessException;
import com.gymflow.global.common.exception.ErrorCode;
import com.gymflow.global.security.principal.CustomUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageHistoryServiceTest {

    private static final Long CURRENT_USER_ID = 1L;

    @Mock
    private UsageHistoryRepository usageHistoryRepository;

    @Mock
    private ResourceRepository resourceRepository;

    private UsageHistoryService usageHistoryService;

    @BeforeEach
    void setUp() {
        usageHistoryService = new UsageHistoryService(usageHistoryRepository, resourceRepository);

        CustomUserDetails principal = new CustomUserDetails(CURRENT_USER_ID, "test@gymflow.com", UserRole.USER);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Resource resource(Long id, String name, ResourceStatus status) {
        Resource resource = Resource.builder()
                .name(name)
                .type(ResourceType.MACHINE)
                .capacity(1)
                .build();
        ReflectionTestUtils.setField(resource, "id", id);
        ReflectionTestUtils.setField(resource, "status", status);
        return resource;
    }

    private UsageHistory usageHistory(Long id, Resource resource, LocalDateTime startedAt, LocalDateTime endedAt) {
        User user = User.builder().email("test@gymflow.com").password("securePassword123").name("Tester").build();
        ReflectionTestUtils.setField(user, "id", CURRENT_USER_ID);

        Reservation reservation = Reservation.builder()
                .reservationBatchId(UUID.randomUUID())
                .user(user)
                .resource(resource)
                .startAt(startedAt)
                .endAt(endedAt)
                .build();
        ReflectionTestUtils.setField(reservation, "id", 100L);

        UsageHistory usageHistory = UsageHistory.builder()
                .reservation(reservation)
                .user(user)
                .resource(resource)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build();
        ReflectionTestUtils.setField(usageHistory, "id", id);
        return usageHistory;
    }

    @Test
    @DisplayName("내 이용 이력은 PageResponse로 변환되어 반환된다")
    void getMyUsageHistories_ShouldReturnPageResponse() {
        // given
        Resource resource = resource(10L, "Chest Press A-1", ResourceStatus.ACTIVE);
        UsageHistory history = usageHistory(1L,
                resource, LocalDateTime.of(2026, 8, 12, 14, 0), LocalDateTime.of(2026, 8, 12, 14, 20));
        Page<UsageHistory> page = new PageImpl<>(List.of(history), PageRequest.of(0, 20), 1);
        when(usageHistoryRepository.findMyUsageHistories(anyLong(), any(), any(), any())).thenReturn(page);

        // when
        PageResponse<?> response = usageHistoryService.getMyUsageHistories(0, 20, null, null);

        // then
        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }

    @Test
    @DisplayName("from이 to와 같으면 INVALID_DATE_RANGE 예외가 발생한다")
    void getMyUsageHistories_WithFromEqualToTo_ShouldThrowInvalidDateRange() {
        // given
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 1, 0, 0);

        // when & then
        assertThatThrownBy(() -> usageHistoryService.getMyUsageHistories(0, 20, sameTime, sameTime))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("from이 to보다 이후이면 INVALID_DATE_RANGE 예외가 발생한다")
    void getMyUsageHistories_WithFromAfterTo_ShouldThrowInvalidDateRange() {
        // given
        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 1, 0, 0);

        // when & then
        assertThatThrownBy(() -> usageHistoryService.getMyUsageHistories(0, 20, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("내 이용 통계는 총 이용 횟수/시간과 Resource별 통계를 포함한다")
    void getMyStatistics_ShouldReturnAggregatedStatistics() {
        // given
        UsageSummaryProjection summary = mock(UsageSummaryProjection.class);
        when(summary.getTotalUsageCount()).thenReturn(3L);
        when(summary.getTotalUsageMinutes()).thenReturn(90L);

        ResourceUsageStatisticsProjection resourceStat = mock(ResourceUsageStatisticsProjection.class);
        when(resourceStat.getResourceId()).thenReturn(10L);
        when(resourceStat.getResourceName()).thenReturn("Chest Press A-1");
        when(resourceStat.getResourceType()).thenReturn(ResourceType.MACHINE);
        when(resourceStat.getResourceStatus()).thenReturn(ResourceStatus.ACTIVE);
        when(resourceStat.getUsageCount()).thenReturn(3L);
        when(resourceStat.getTotalUsageMinutes()).thenReturn(90L);

        when(usageHistoryRepository.findUsageSummaryByUserId(anyLong(), any(), any())).thenReturn(summary);
        when(usageHistoryRepository.findResourceUsageStatisticsByUserId(anyLong(), any(), any()))
                .thenReturn(List.of(resourceStat));

        // when
        UsageStatisticsResponse response = usageHistoryService.getMyStatistics(null, null);

        // then
        assertThat(response.totalUsageCount()).isEqualTo(3L);
        assertThat(response.totalUsageMinutes()).isEqualTo(90L);
        assertThat(response.resourceUsages()).hasSize(1);
        assertThat(response.resourceUsages().get(0).resourceId()).isEqualTo(10L);
        assertThat(response.resourceUsages().get(0).usageCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("UsageHistory가 없으면 통계는 0/0/빈 목록을 반환한다")
    void getMyStatistics_WithNoUsageHistory_ShouldReturnZeroStatistics() {
        // given
        UsageSummaryProjection summary = mock(UsageSummaryProjection.class);
        when(summary.getTotalUsageCount()).thenReturn(0L);
        when(summary.getTotalUsageMinutes()).thenReturn(0L);

        when(usageHistoryRepository.findUsageSummaryByUserId(anyLong(), any(), any())).thenReturn(summary);
        when(usageHistoryRepository.findResourceUsageStatisticsByUserId(anyLong(), any(), any()))
                .thenReturn(List.of());

        // when
        UsageStatisticsResponse response = usageHistoryService.getMyStatistics(null, null);

        // then
        assertThat(response.totalUsageCount()).isZero();
        assertThat(response.totalUsageMinutes()).isZero();
        assertThat(response.resourceUsages()).isEmpty();
    }

    @Test
    @DisplayName("관리자 Resource 통계는 Resource 정보와 집계 결과를 함께 반환한다")
    void getResourceStatistics_WithExistingResource_ShouldReturnStatistics() {
        // given
        Resource resource = resource(10L, "Chest Press A-1", ResourceStatus.INACTIVE);
        when(resourceRepository.findById(10L)).thenReturn(Optional.of(resource));

        UsageSummaryProjection summary = mock(UsageSummaryProjection.class);
        when(summary.getTotalUsageCount()).thenReturn(7L);
        when(summary.getTotalUsageMinutes()).thenReturn(210L);
        when(usageHistoryRepository.findUsageSummaryByResourceId(anyLong(), any(), any())).thenReturn(summary);

        // when
        AdminResourceUsageStatisticsResponse response = usageHistoryService.getResourceStatistics(10L, null, null);

        // then
        assertThat(response.resourceId()).isEqualTo(10L);
        assertThat(response.resourceStatus()).isEqualTo(ResourceStatus.INACTIVE);
        assertThat(response.totalUsageCount()).isEqualTo(7L);
        assertThat(response.totalUsageMinutes()).isEqualTo(210L);
    }

    @Test
    @DisplayName("존재하지 않는 Resource의 통계를 조회하면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void getResourceStatistics_WithNonExistentResource_ShouldThrowResourceNotFound() {
        // given
        when(resourceRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> usageHistoryService.getResourceStatistics(999L, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(usageHistoryRepository, never()).findUsageSummaryByResourceId(anyLong(), any(), any());
    }

    @Test
    @DisplayName("관리자 Resource 통계 조회에서 기간이 잘못되면 Resource 조회 없이 INVALID_DATE_RANGE 예외가 발생한다")
    void getResourceStatistics_WithInvalidDateRange_ShouldThrowBeforeLookup() {
        // given
        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 1, 0, 0);

        // when & then
        assertThatThrownBy(() -> usageHistoryService.getResourceStatistics(10L, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_DATE_RANGE);

        verify(resourceRepository, never()).findById(anyLong());
    }
}
