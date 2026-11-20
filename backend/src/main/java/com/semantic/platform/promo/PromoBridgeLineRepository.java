package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoBridgeLineRepository extends JpaRepository<PromoBridgeLine, Long> {
    List<PromoBridgeLine> findByRunIdOrderByPeriodKeyAscLineKeyAsc(Long runId);
    void deleteByRunId(Long runId);
}
