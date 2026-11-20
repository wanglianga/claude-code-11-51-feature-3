package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 原交易关联的退货行：指向原订单行与原优惠版本。
 * amount 为退货行标价；cash_amount 为 POS 实退现金证据（可空）。
 */
@Entity
@Table(name = "promo_refund_line")
public class PromoRefundLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "refund_line_id", nullable = false, unique = true)
    public String refundLineId;

    @Column(name = "refund_id", nullable = false)
    public String refundId;

    @Column(name = "order_id", nullable = false)
    public String orderId;

    @Column(name = "order_line_id", nullable = false)
    public String orderLineId;

    public String sku;

    @Column(nullable = false)
    public Integer quantity;

    @Column(nullable = false)
    public BigDecimal amount;

    @Column(name = "cash_amount")
    public BigDecimal cashAmount;

    @Column(name = "business_date", nullable = false)
    public LocalDate businessDate;

    @Column(name = "original_rule_version_id")
    public Long originalRuleVersionId;

    public String note;

    @Column(name = "created_batch")
    public Long createdBatch;
}
