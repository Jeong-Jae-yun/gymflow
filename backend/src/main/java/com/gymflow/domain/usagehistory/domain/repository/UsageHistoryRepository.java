package com.gymflow.domain.usagehistory.domain.repository;

import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import com.gymflow.domain.usagehistory.domain.repository.projection.ResourceUsageStatisticsProjection;
import com.gymflow.domain.usagehistory.domain.repository.projection.UsageSummaryProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UsageHistoryRepository extends JpaRepository<UsageHistory, Long> {

    @Query(value = """
            select uh
            from UsageHistory uh
            join fetch uh.resource r
            where uh.user.id = :userId
              and (:from is null or uh.startedAt >= :from)
              and (:to is null or uh.startedAt < :to)
            order by uh.startedAt desc
            """,
            countQuery = """
            select count(uh)
            from UsageHistory uh
            where uh.user.id = :userId
              and (:from is null or uh.startedAt >= :from)
              and (:to is null or uh.startedAt < :to)
            """)
    Page<UsageHistory> findMyUsageHistories(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query("""
            select
                count(uh) as totalUsageCount,
                coalesce(sum(uh.duration), 0) as totalUsageMinutes
            from UsageHistory uh
            where uh.user.id = :userId
              and (:from is null or uh.startedAt >= :from)
              and (:to is null or uh.startedAt < :to)
            """)
    UsageSummaryProjection findUsageSummaryByUserId(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
            select
                r.id as resourceId,
                r.name as resourceName,
                r.type as resourceType,
                r.status as resourceStatus,
                count(uh) as usageCount,
                coalesce(sum(uh.duration), 0) as totalUsageMinutes
            from UsageHistory uh
            join uh.resource r
            where uh.user.id = :userId
              and (:from is null or uh.startedAt >= :from)
              and (:to is null or uh.startedAt < :to)
            group by r.id, r.name, r.type, r.status
            order by count(uh) desc
            """)
    List<ResourceUsageStatisticsProjection> findResourceUsageStatisticsByUserId(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
            select
                count(uh) as totalUsageCount,
                coalesce(sum(uh.duration), 0) as totalUsageMinutes
            from UsageHistory uh
            where uh.resource.id = :resourceId
              and (:from is null or uh.startedAt >= :from)
              and (:to is null or uh.startedAt < :to)
            """)
    UsageSummaryProjection findUsageSummaryByResourceId(
            @Param("resourceId") Long resourceId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
