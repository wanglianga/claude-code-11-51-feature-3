package com.semantic.platform.source;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "order_fact")
public class OrderFact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "ordered_at", nullable = false)
    private OffsetDateTime orderedAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "CNY";

    @Column(nullable = false)
    private String status;

    private String channel;

    @Column(name = "created_batch")
    private Long createdBatch;

    @Column(name = "tax_basis", nullable = false)
    private String taxBasis = "tax_inclusive";

    @Column(name = "tax_rate", nullable = false)
    private BigDecimal taxRate = new BigDecimal("0.1300");

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public OffsetDateTime getOrderedAt() { return orderedAt; }
    public void setOrderedAt(OffsetDateTime orderedAt) { this.orderedAt = orderedAt; }
    public OffsetDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(OffsetDateTime paidAt) { this.paidAt = paidAt; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Long getCreatedBatch() { return createdBatch; }
    public void setCreatedBatch(Long createdBatch) { this.createdBatch = createdBatch; }
    public String getTaxBasis() { return taxBasis; }
    public void setTaxBasis(String taxBasis) { this.taxBasis = taxBasis; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
}
