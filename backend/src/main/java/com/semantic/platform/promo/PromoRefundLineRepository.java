package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoRefundLineRepository extends JpaRepository<PromoRefundLine, Long> {
    List<PromoRefundLine> findByOrderIdOrderByBusinessDateAsc(String orderId);
    List<PromoRefundLine> findAllByOrderByIdAsc();
}
