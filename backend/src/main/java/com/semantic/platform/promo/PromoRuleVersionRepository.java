package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PromoRuleVersionRepository extends JpaRepository<PromoRuleVersion, Long> {
    List<PromoRuleVersion> findByCampaignCodeOrderByVersionDesc(String campaignCode);
    Optional<PromoRuleVersion> findFirstByCampaignCodeAndStatusOrderByVersionDesc(String campaignCode, String status);
    List<PromoRuleVersion> findByStatus(String status);
    List<PromoRuleVersion> findByCampaignCodeAndStatus(String campaignCode, String status);
}
