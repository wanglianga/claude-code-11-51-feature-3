package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 供应商补偿凭证：毛利桥的独立抵减/回补事实。
 * 不作为顾客销售收入，也不作为第二次优惠抵减折让。
 */
@Entity
@Table(name = "promo_supplier_compensation")
public class PromoSupplierCompensation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "comp_id", nullable = false, unique = true)
    public String compId;

    @Column(name = "campaign_code", nullable = false)
    public String campaignCode;

    public String sku;

    @Column(name = "store_id")
    public String storeId;

    @Column(name = "business_date", nullable = false)
    public LocalDate businessDate;

    @Column(name = "period_start")
    public LocalDate periodStart;

    @Column(name = "period_end")
    public LocalDate periodEnd;

    @Column(nullable = false)
    public BigDecimal amount;

    @Column(nullable = false)
    public String currency = "CNY";

    @Column(name = "tax_basis", nullable = false)
    public String taxBasis = "tax_inclusive";

    @Column(name = "tax_rate", nullable = false)
    public BigDecimal taxRate = new BigDecimal("0.1300");

    @Column(name = "contract_ref")
    public String contractRef;

    /** rebate=按合同计入毛利分析；cost_reduction=按成本回补处理。均独立列示。 */
    @Column(name = "margin_treatment", nullable = false)
    public String marginTreatment = "rebate";

    @Column(name = "evidence_ref")
    public String evidenceRef;

    @Column(name = "settles_execution_id")
    public String settlesExecutionId;

    @Column(nullable = false)
    public String status = "confirmed";

    @Column(name = "received_at", nullable = false)
    public OffsetDateTime receivedAt = OffsetDateTime.now();

    @Column(name = "created_batch")
    public Long createdBatch;
}
