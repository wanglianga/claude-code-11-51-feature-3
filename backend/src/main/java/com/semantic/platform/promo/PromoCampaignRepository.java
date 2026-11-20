package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PromoCampaignRepository extends JpaRepository<PromoCampaign, Long> {
    Optional<PromoCampaign> findByCampaignCode(String campaignCode);
    List<PromoCampaign> findAllByOrderByCampaignCodeAsc();
}
