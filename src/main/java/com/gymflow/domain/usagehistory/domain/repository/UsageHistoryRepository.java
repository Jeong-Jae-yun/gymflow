package com.gymflow.domain.usagehistory.domain.repository;

import com.gymflow.domain.usagehistory.domain.entity.UsageHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageHistoryRepository extends JpaRepository<UsageHistory, Long> {
}
