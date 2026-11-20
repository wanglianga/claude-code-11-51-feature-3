package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoAllocationRepository extends JpaRepository<PromoAllocation, Long> {
    List<PromoAllocation> findByRunIdOrderByOrderIdAscAppliedSeqAsc(Long runId);
    List<PromoAllocation> findByRunIdAndOrderId(Long runId, String orderId);
}
