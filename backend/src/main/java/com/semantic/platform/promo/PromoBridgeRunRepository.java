package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PromoBridgeRunRepository extends JpaRepository<PromoBridgeRun, Long> {
    List<PromoBridgeRun> findAllByOrderByCreatedAtDesc();
    Optional<PromoBridgeRun> findByRunId(Long runId);
    List<PromoBridgeRun> findByMetricKeyOrderByCreatedAtDesc(String metricKey);
}
