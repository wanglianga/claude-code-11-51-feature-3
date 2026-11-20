package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** 分摊审计：每个订单级优惠如何分摊到各行，保留原始金额与未分摊金额。 */
@Entity
@Table(name = "promo_allocation")
public class PromoAllocation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "order_id", nullable = false)
    public String orderId;

    @Column(name = "execution_id", nullable = false)
    public String executionId;

    @Column(name = "campaign_code")
    public String campaignCode;

    @Column(name = "order_line_id")
    public String orderLineId;

    @Column(name = "rule_version_id")
    public Long ruleVersionId;

    @Column(name = "applied_seq")
    public Integer appliedSeq;

    public String basis;

    @Column(name = "original_amount", nullable = false)
    public BigDecimal originalAmount = BigDecimal.ZERO;

    @Column(name = "allocated_amount", nullable = false)
    public BigDecimal allocatedAmount = BigDecimal.ZERO;

    @Column(name = "unallocated_amount", nullable = false)
    public BigDecimal unallocatedAmount = BigDecimal.ZERO;

    @Column(name = "eligible_amount")
    public BigDecimal eligibleAmount;

    @Column(name = "eligible_quantity")
    public Integer eligibleQuantity;

    @Column(name = "remainder_cents", nullable = false)
    public Integer remainderCents = 0;

    @Column(nullable = false)
    public String phase = "original";

    @Column(nullable = false)
    public String status;

    public String reason;
}
