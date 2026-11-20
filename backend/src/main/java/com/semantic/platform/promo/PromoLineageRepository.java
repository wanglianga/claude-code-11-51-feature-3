package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoLineageRepository extends JpaRepository<PromoLineage, Long> {
    List<PromoLineage> findByRunIdAndLineKeyOrderByIdAsc(Long runId, String lineKey);
    void deleteByRunId(Long runId);
}
