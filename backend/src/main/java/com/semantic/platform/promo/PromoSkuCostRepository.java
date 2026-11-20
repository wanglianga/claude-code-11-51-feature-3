package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface PromoSkuCostRepository extends JpaRepository<PromoSkuCost, Long> {
    List<PromoSkuCost> findByCostVersionIdAndBusinessDateBetween(Long costVersionId, LocalDate from, LocalDate to);
    List<PromoSkuCost> findByCostVersionId(Long costVersionId);
}
