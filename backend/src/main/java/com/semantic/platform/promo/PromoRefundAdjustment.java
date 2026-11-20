package com.semantic.platform.promo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 退款重算审计：原交易关联退货按原优惠版本与剩余商品重检资格后，现金与折让冲回各是多少。 */
@Entity
@Table(name = "promo_refund_adjustment")
public class PromoRefundAdjustment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "order_id", nullable = false)
    public String orderId;

    @Column(name = "refund_line_id")
    public String refundLineId;

    @Column(name = "order_line_id")
    public String orderLineId;

    @Column(name = "original_rule_version")
    public Long originalRuleVersion;

    public String method;

    @Column(name = "return_business_date")
    public LocalDate returnBusinessDate;

    @Column(name = "returned_list", nullable = false)
    public BigDecimal returnedList = BigDecimal.ZERO;

    @Column(name = "original_paid", nullable = false)
    public BigDecimal originalPaid = BigDecimal.ZERO;

    @Column(name = "remaining_paid", nullable = false)
    public BigDecimal remainingPaid = BigDecimal.ZERO;

    @Column(name = "refund_cash", nullable = false)
    public BigDecimal refundCash = BigDecimal.ZERO;

    @Column(name = "discount_reversal", nullable = false)
    public BigDecimal discountReversal = BigDecimal.ZERO;

    @Column(name = "reallocation_amount", nullable = false)
    public BigDecimal reallocationAmount = BigDecimal.ZERO;

    @Column(name = "threshold_lost", nullable = false)
    public Boolean thresholdLost = false;

    @Column(name = "gift_invalidated", nullable = false)
    public Boolean giftInvalidated = false;

    public String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    public String detailJson;
}
