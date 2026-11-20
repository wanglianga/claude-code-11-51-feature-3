package com.semantic.platform.promo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;

/** 毛利桥明细行：按 活动 × 商品 × 门店 × 期间 分列桥的每一层。 */
@Entity
@Table(name = "promo_bridge_line")
public class PromoBridgeLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "line_key", nullable = false)
    public String lineKey;

    @Column(name = "period_key", nullable = false)
    public String periodKey;

    @Column(name = "campaign_code")
    public String campaignCode;

    public String sku;

    @Column(name = "store_id")
    public String storeId;

    /** item | unallocated | compensation */
    @Column(name = "line_kind", nullable = false)
    public String lineKind;

    @Column(name = "list_sales", nullable = false)
    public BigDecimal listSales = BigDecimal.ZERO;

    @Column(name = "customer_concession", nullable = false)
    public BigDecimal customerConcession = BigDecimal.ZERO;

    @Column(name = "refund_reversal", nullable = false)
    public BigDecimal refundReversal = BigDecimal.ZERO;

    @Column(name = "reallocation_adjustment", nullable = false)
    public BigDecimal reallocationAdjustment = BigDecimal.ZERO;

    @Column(name = "net_sales", nullable = false)
    public BigDecimal netSales = BigDecimal.ZERO;

    @Column(name = "cost_of_sales", nullable = false)
    public BigDecimal costOfSales = BigDecimal.ZERO;

    @Column(name = "gross_margin", nullable = false)
    public BigDecimal grossMargin = BigDecimal.ZERO;

    @Column(name = "supplier_compensation", nullable = false)
    public BigDecimal supplierCompensation = BigDecimal.ZERO;

    @Column(name = "net_margin_contribution", nullable = false)
    public BigDecimal netMarginContribution = BigDecimal.ZERO;

    @Column(name = "quantity_sold", nullable = false)
    public BigDecimal quantitySold = BigDecimal.ZERO;

    @Column(name = "quantity_returned", nullable = false)
    public BigDecimal quantityReturned = BigDecimal.ZERO;

    @Column(name = "amount_basis")
    public String amountBasis;

    @Column(nullable = false)
    public String currency = "CNY";

    @Column(name = "tax_basis", nullable = false)
    public String taxBasis = "tax_inclusive";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flags_json", columnDefinition = "jsonb")
    public String flagsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    public String detailJson;
}
