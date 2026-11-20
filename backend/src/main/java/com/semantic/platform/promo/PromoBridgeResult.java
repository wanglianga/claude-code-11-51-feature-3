package com.semantic.platform.promo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 引擎输出：桥明细、分摊审计、退款重算审计、守恒校验与合计。 */
public class PromoBridgeResult {

    public List<LineRow> lines = new ArrayList<>();
    public List<AllocRow> allocations = new ArrayList<>();
    public List<RefundRow> refundAdjustments = new ArrayList<>();
    public List<CheckRow> checks = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    public Map<String, BigDecimal> totals = new LinkedHashMap<>();
    public Map<String, Object> meta = new LinkedHashMap<>();

    public static class LineRow {
        public String lineKey;
        public String periodKey;
        public String campaignCode;
        public String sku;
        public String storeId;
        public String lineKind;          // item | unallocated | compensation
        public BigDecimal listSales = BigDecimal.ZERO;
        public BigDecimal customerConcession = BigDecimal.ZERO;
        public BigDecimal refundReversal = BigDecimal.ZERO;
        public BigDecimal reallocationAdjustment = BigDecimal.ZERO;
        public BigDecimal netSales = BigDecimal.ZERO;
        public BigDecimal costOfSales = BigDecimal.ZERO;
        public BigDecimal grossMargin = BigDecimal.ZERO;
        public BigDecimal supplierCompensation = BigDecimal.ZERO;
        public BigDecimal netMarginContribution = BigDecimal.ZERO;
        public BigDecimal quantitySold = BigDecimal.ZERO;
        public BigDecimal quantityReturned = BigDecimal.ZERO;
        public String amountBasis;
        public String currency = "CNY";
        public String taxBasis;
        public Map<String, Object> flags = new LinkedHashMap<>();
        public Map<String, Object> detail = new LinkedHashMap<>();
    }

    public static class AllocRow {
        public String orderId;
        public String executionId;
        public String campaignCode;
        public String orderLineId;
        public Long ruleVersionId;
        public Integer appliedSeq;
        public String basis;
        public BigDecimal originalAmount = BigDecimal.ZERO;
        public BigDecimal allocatedAmount = BigDecimal.ZERO;
        public BigDecimal unallocatedAmount = BigDecimal.ZERO;
        public BigDecimal eligibleAmount;
        public Integer eligibleQuantity;
        public int remainderCents;
        public String phase = "original";
        public String status;
        public String reason;
    }

    public static class RefundRow {
        public String orderId;
        public String refundLineId;
        public String orderLineId;
        public Long originalRuleVersion;
        public String method;
        public java.time.LocalDate returnBusinessDate;
        public BigDecimal returnedList = BigDecimal.ZERO;
        public BigDecimal originalPaid = BigDecimal.ZERO;
        public BigDecimal remainingPaid = BigDecimal.ZERO;
        public BigDecimal refundCash = BigDecimal.ZERO;
        public BigDecimal discountReversal = BigDecimal.ZERO;
        public BigDecimal reallocationAmount = BigDecimal.ZERO;
        public boolean thresholdLost;
        public boolean giftInvalidated;
        public String note;
        public Map<String, Object> detail = new LinkedHashMap<>();
    }

    public static class CheckRow {
        public String checkCode;
        public String scopeType = "global";
        public String scopeKey;
        public String status;      // pass | warn | fail
        public BigDecimal expected;
        public BigDecimal actual;
        public BigDecimal diff;
        public String message;
    }
}
