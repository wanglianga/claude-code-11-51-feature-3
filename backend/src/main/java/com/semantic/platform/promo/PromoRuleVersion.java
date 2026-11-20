package com.semantic.platform.promo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 活动资格 / 叠加 / 分摊 / 退货重算规则（版本快照）。 */
@Entity
@Table(name = "promo_rule_version")
public class PromoRuleVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "campaign_code", nullable = false)
    public String campaignCode;

    @Column(nullable = false)
    public Integer version;

    @Column(nullable = false)
    public String status = "draft";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_json", nullable = false, columnDefinition = "jsonb")
    public String rulesJson;

    @Column(name = "effective_from")
    public LocalDate effectiveFrom;

    @Column(name = "effective_to")
    public LocalDate effectiveTo;

    @Column(name = "definition_hash")
    public String definitionHash;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;
}
