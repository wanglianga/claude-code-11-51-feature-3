package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** SKU 门店日成本（属于某个成本版本）。 */
@Entity
@Table(name = "promo_sku_cost")
public class PromoSkuCost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "cost_version_id", nullable = false)
    public Long costVersionId;

    @Column(nullable = false)
    public String sku;

    @Column(name = "store_id", nullable = false)
    public String storeId;

    @Column(name = "business_date", nullable = false)
    public LocalDate businessDate;

    @Column(name = "unit_cost", nullable = false)
    public BigDecimal unitCost;

    @Column(nullable = false)
    public String currency = "CNY";

    @Column(name = "tax_basis", nullable = false)
    public String taxBasis = "tax_inclusive";

    @Column(name = "tax_rate", nullable = false)
    public BigDecimal taxRate = new BigDecimal("0.1300");

    @Column(name = "created_batch")
    public Long createdBatch;
}
