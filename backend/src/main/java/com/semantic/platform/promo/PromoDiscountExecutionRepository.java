package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoDiscountExecutionRepository extends JpaRepository<PromoDiscountExecution, Long> {
    List<PromoDiscountExecution> findByOrderIdOrderByAppliedSeqAsc(String orderId);
    List<PromoDiscountExecution> findAllByOrderByOrderIdAscAppliedSeqAsc();
}
