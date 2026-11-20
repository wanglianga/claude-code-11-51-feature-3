package com.semantic.platform.promo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 毛利桥输入快照：一次运行把用到的全部事实与版本固化为可复演 JSON。
 * 历史发布沿用其快照；成本调整/迟到补偿只生成新运行。
 */
public class PromoSnapshot {

    public List<Order> orders = new ArrayList<>();
    public List<Line> lines = new ArrayList<>();
    public List<Exec> executions = new ArrayList<>();
    public List<RefundLine> refunds = new ArrayList<>();
    public List<Price> prices = new ArrayList<>();
    public List<Cost> costs = new ArrayList<>();
    public List<Comp> compensations = new ArrayList<>();
    public List<RuleVer> rules = new ArrayList<>();
    public List<Campaign> campaigns = new ArrayList<>();

    public static class Campaign {
        public String campaignCode;
        public String name;
        public String taxBasis;
        public String supplierCompTreatment;
        public String contractRef;
    }

    public static class Order {
        public String orderId;
        public String storeId;
        public LocalDate businessDate;
        public String status;
        public BigDecimal amount;
        public String currency = "CNY";
        public String taxBasis = "tax_inclusive";
        public BigDecimal taxRate = new BigDecimal("0.1300");
    }

    public static class Line {
        public String lineId;
        public String orderId;
        public String storeId;
        public LocalDate businessDate;
        public String sku;
        public int quantity;
        public BigDecimal unitPrice;
        public BigDecimal amount;
        public String lineType = "sale";
        public String taxBasis = "tax_inclusive";
        public BigDecimal taxRate = new BigDecimal("0.1300");
    }

    public static class Exec {
        public String executionId;
        public String orderId;
        public String campaignCode;
        public Long ruleVersionId;
        public String ruleId;
        public String executionType;
        public String scope;
        public String orderLineId;
        public String giftLineId;
        public BigDecimal amount;
        public String currency;
        public String taxBasis = "tax_inclusive";
        public String allocationBasis;
        public String basisEvidence;
        public int appliedSeq;
        public String note;
    }

    public static class RefundLine {
        public String refundLineId;
        public String refundId;
        public String orderId;
        public String orderLineId;
        public String sku;
        public int quantity;
        public BigDecimal amount;
        public BigDecimal cashAmount;
        public LocalDate businessDate;
        public Long originalRuleVersionId;
        public String note;
    }

    public static class Price {
        public String sku;
        public String storeId;
        public LocalDate validFrom;
        public LocalDate validTo;
        public BigDecimal listPrice;
        public String currency = "CNY";
        public String taxBasis = "tax_inclusive";
        public BigDecimal taxRate = new BigDecimal("0.1300");
    }

    public static class Cost {
        public String sku;
        public String storeId;
        public LocalDate businessDate;
        public BigDecimal unitCost;
        public String currency = "CNY";
        public String taxBasis = "tax_inclusive";
        public BigDecimal taxRate = new BigDecimal("0.1300");
    }

    public static class Comp {
        public String compId;
        public String campaignCode;
        public String sku;
        public String storeId;
        public LocalDate businessDate;
        public LocalDate periodStart;
        public LocalDate periodEnd;
        public BigDecimal amount;
        public String currency = "CNY";
        public String taxBasis = "tax_inclusive";
        public BigDecimal taxRate = new BigDecimal("0.1300");
        public String marginTreatment = "rebate";
        public String contractRef;
        public String status = "confirmed";
        public String settlesExecutionId;
        public OffsetDateTime receivedAt;
    }

    public static class RuleVer {
        public Long id;
        public String campaignCode;
        public int version;
        public String status;
        public String rulesJson;
        public LocalDate effectiveFrom;
        public LocalDate effectiveTo;
    }
}
