package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoRefundAdjustmentRepository extends JpaRepository<PromoRefundAdjustment, Long> {
    List<PromoRefundAdjustment> findByRunIdOrderByOrderIdAsc(Long runId);
    List<PromoRefundAdjustment> findByRunIdAndOrderId(Long runId, String orderId);
}
