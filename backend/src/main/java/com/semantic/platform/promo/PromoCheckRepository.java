package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoCheckRepository extends JpaRepository<PromoCheck, Long> {
    List<PromoCheck> findByRunIdOrderByIdAsc(Long runId);
}
