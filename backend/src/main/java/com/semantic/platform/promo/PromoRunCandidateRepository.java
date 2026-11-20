package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoRunCandidateRepository extends JpaRepository<PromoRunCandidate, Long> {
    List<PromoRunCandidate> findAllByOrderByCreatedAtDesc();
    List<PromoRunCandidate> findByStatusOrderByCreatedAtDesc(String status);
}
