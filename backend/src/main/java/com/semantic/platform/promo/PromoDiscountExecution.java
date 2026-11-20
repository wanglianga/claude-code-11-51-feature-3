package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 订单优惠执行明细：保留原始订单级金额与 POS 执行顺序（叠加顺序）。
 * allocation_basis / basis_evidence 为空表示合同与 POS 证据不足，运行期列为未分摊。
 */
@Entity
@Table(name = "promo_discount_execution")
public class PromoDiscountExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "execution_id", nullable = false, unique = true)
    public String executionId;

    @Column(name = "order_id", nullable = false)
    public String orderId;

    @Column(name = "campaign_code", nullable = false)
    public String campaignCode;

    @Column(name = "rule_version_id")
    public Long ruleVersionId;

    @Column(name = "rule_id")
    public String ruleId;

    @Column(name = "execution_type", nullable = false)
    public String executionType;

    @Column(nullable = false)
    public String scope = "order";

    @Column(name = "order_line_id")
    public String orderLineId;

    @Column(name = "gift_line_id")
    public String giftLineId;

    @Column(nullable = false)
    public BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false)
    public String currency = "CNY";

    @Column(name = "tax_basis", nullable = false)
    public String taxBasis = "tax_inclusive";

    @Column(name = "allocation_basis")
    public String allocationBasis;

    @Column(name = "basis_evidence")
    public String basisEvidence;

    @Column(name = "applied_seq", nullable = false)
    public Integer appliedSeq;

    public String note;

    @Column(name = "created_batch")
    public Long createdBatch;
}
