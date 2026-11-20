package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 活动主数据：税基声明、供应商补偿的合同处理方式。 */
@Entity
@Table(name = "promo_campaign")
public class PromoCampaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "campaign_code", nullable = false, unique = true)
    public String campaignCode;

    @Column(nullable = false)
    public String name;

    @Column(name = "contract_ref")
    public String contractRef;

    @Column(name = "tax_basis", nullable = false)
    public String taxBasis = "tax_inclusive";

    @Column(name = "supplier_comp_treatment")
    public String supplierCompTreatment = "rebate";

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
