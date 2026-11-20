package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoInputVersionRepository extends JpaRepository<PromoInputVersion, Long> {
    List<PromoInputVersion> findByRunIdOrderByIdAsc(Long runId);
}
