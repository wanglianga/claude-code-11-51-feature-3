package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 价格生效事实：SKU × 门店 × 生效期间的标准售价（标价）。 */
@Entity
@Table(name = "promo_price_fact")
public class PromoPriceFact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "price_fact_id", nullable = false, unique = true)
    public String priceFactId;

    @Column(nullable = false)
    public String sku;

    @Column(name = "store_id", nullable = false)
    public String storeId;

    @Column(name = "valid_from", nullable = false)
    public LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    public LocalDate validTo;

    @Column(name = "list_price", nullable = false)
    public BigDecimal listPrice;

    @Column(nullable = false)
    public String currency = "CNY";

    @Column(name = "tax_basis", nullable = false)
    public String taxBasis = "tax_inclusive";

    @Column(name = "tax_rate", nullable = false)
    public BigDecimal taxRate = new BigDecimal("0.1300");

    @Column(nullable = false)
    public Integer version = 1;

    @Column(name = "created_batch")
    public Long createdBatch;
}
