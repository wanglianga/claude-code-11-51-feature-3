package com.semantic.platform.promo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 毛利桥引擎：跨档满减/多券叠加/买赠失效/部分退货/厂商补偿/税基/守恒。
 */
class PromoBridgeEngineTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertAmount(expected, actual, null);
    }

    private static void assertAmount(String expected, BigDecimal actual, String note) {
        assertEquals(0, bd(expected).compareTo(actual),
                (note == null ? "" : note + "；") + "expected " + expected + " but was " + actual);
    }

    private static void assertAmount(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static void assertChecksPass(PromoBridgeResult r) {
        StringBuilder sb = new StringBuilder();
        for (PromoBridgeResult.CheckRow c : r.checks) {
            if (!"pass".equals(c.status)) sb.append(c.checkCode).append("=").append(c.status)
                    .append("(exp ").append(c.expected).append(" act ").append(c.actual).append(") ");
        }
        assertTrue(sb.length() == 0, "守恒校验未全通过: " + sb);
    }

    private PromoSnapshot.Line line(String id, String order, String store, LocalDate date, String sku,
                                    int qty, String amount, String type) {
        PromoSnapshot.Line l = new PromoSnapshot.Line();
        l.lineId = id;
        l.orderId = order;
        l.storeId = store;
        l.businessDate = date;
        l.sku = sku;
        l.quantity = qty;
        l.unitPrice = bd(amount).divide(BigDecimal.valueOf(qty), 2, java.math.RoundingMode.HALF_UP);
        l.amount = bd(amount);
        l.lineType = type;
        return l;
    }

    private PromoSnapshot.Order order(String id, String store, LocalDate date, String amount) {
        PromoSnapshot.Order o = new PromoSnapshot.Order();
        o.orderId = id;
        o.storeId = store;
        o.businessDate = date;
        o.amount = bd(amount);
        o.taxBasis = "tax_inclusive";
        o.taxRate = new BigDecimal("0.1300");
        return o;
    }

    private PromoSnapshot.Exec exec(String id, String order, String campaign, String ruleId, String type, String amount,
                                    String basis, String evidence, int seq) {
        PromoSnapshot.Exec e = new PromoSnapshot.Exec();
        e.executionId = id;
        e.orderId = order;
        e.campaignCode = campaign;
        e.ruleId = ruleId;
        e.executionType = type;
        e.scope = "order";
        e.amount = bd(amount);
        e.allocationBasis = basis;
        e.basisEvidence = evidence;
        e.appliedSeq = seq;
        return e;
    }

    private PromoSnapshot.Price price(String sku, String store, String listPrice) {
        PromoSnapshot.Price p = new PromoSnapshot.Price();
        p.sku = sku;
        p.storeId = store;
        p.validFrom = LocalDate.of(2026, 5, 1);
        p.validTo = LocalDate.of(2026, 5, 31);
        p.listPrice = bd(listPrice);
        return p;
    }

    private PromoSnapshot.Cost cost(String sku, String store, LocalDate date, String unitCost) {
        PromoSnapshot.Cost c = new PromoSnapshot.Cost();
        c.sku = sku;
        c.storeId = store;
        c.businessDate = date;
        c.unitCost = bd(unitCost);
        return c;
    }

    private void baseSnapshot(PromoSnapshot snap) {
        PromoSnapshot.Campaign c1 = new PromoSnapshot.Campaign();
        c1.campaignCode = "C1";
        c1.taxBasis = "tax_inclusive";
        PromoSnapshot.Campaign c5 = new PromoSnapshot.Campaign();
        c5.campaignCode = "C5";
        c5.taxBasis = "tax_inclusive";
        snap.campaigns.addAll(List.of(c1, c5));
        PromoSnapshot.RuleVer r = new PromoSnapshot.RuleVer();
        r.id = 1L;
        r.campaignCode = "C1";
        r.version = 1;
        r.status = "published";
        r.rulesJson = "{\"allocation\":{\"basis\":\"amount\",\"evidence\":\"contract\"},"
                + "\"stacking\":[{\"ruleId\":\"C1-TIER\",\"seq\":10,\"kind\":\"threshold_off\",\"tiers\":[{\"min\":300,\"off\":30},{\"min\":600,\"off\":80}]}]}";
        snap.rules.add(r);
        snap.prices.add(price("SKU-X", "S101", "120.00"));
        snap.prices.add(price("SKU-Y", "S101", "200.00"));
    }

    @Test
    void crossTierAllocationAndMarginsConserve() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        LocalDate d = LocalDate.of(2026, 5, 3);
        snap.orders.add(order("P1", "S101", d, "440.00"));
        snap.lines.add(line("L1", "P1", "S101", d, "SKU-X", 2, "240.00", "sale"));
        snap.lines.add(line("L2", "P1", "S101", d, "SKU-Y", 1, "200.00", "sale"));
        snap.executions.add(exec("E1", "P1", "C1", "C1-TIER", "amount_off", "30.00", "amount", "contract", 10));
        snap.costs.add(cost("SKU-X", "S101", d, "70.00"));
        snap.costs.add(cost("SKU-Y", "S101", d, "120.00"));

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_inclusive", "day").run();
        assertChecksPass(r);
        assertAmount("440", r.totals.get("listSales"));
        assertAmount("30", r.totals.get("customerConcession"));
        assertAmount("410", r.totals.get("netSales"));
        // 成本 2*70 + 120 = 260
        assertAmount("260", r.totals.get("costOfSales"));
        assertAmount("150", r.totals.get("grossMargin"));
    }

    @Test
    void supplierCompensationIsIndependentOfSalesRevenue() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        LocalDate d = LocalDate.of(2026, 5, 3);
        snap.orders.add(order("P1", "S101", d, "200.00"));
        snap.lines.add(line("L1", "P1", "S101", d, "SKU-Y", 1, "200.00", "sale"));
        snap.costs.add(cost("SKU-Y", "S101", d, "120.00"));
        PromoSnapshot.Comp comp = new PromoSnapshot.Comp();
        comp.compId = "SC1";
        comp.campaignCode = "C5";
        comp.businessDate = d;
        comp.amount = bd("50.00");
        comp.marginTreatment = "rebate";
        snap.compensations.add(comp);

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_inclusive", "day").run();
        assertChecksPass(r);
        assertAmount("200", r.totals.get("netSales"), "补偿不进销售收入");
        assertAmount("200", r.totals.get("listSales"));
        assertAmount("80", r.totals.get("grossMargin"));
        assertAmount("50", r.totals.get("supplierCompensation"));
        assertAmount("130", r.totals.get("netMarginContribution"), "净毛利贡献 = 毛利 + 补偿");
    }

    @Test
    void unallocatedWhenEvidenceMissing() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        LocalDate d = LocalDate.of(2026, 5, 3);
        snap.orders.add(order("P1", "S101", d, "240.00"));
        snap.lines.add(line("L1", "P1", "S101", d, "SKU-X", 2, "240.00", "sale"));
        snap.costs.add(cost("SKU-X", "S101", d, "70.00"));
        snap.executions.add(exec("E1", "P1", "C1", null, "points", "20.00", null, "none", 40));

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_inclusive", "day").run();
        assertChecksPass(r);
        assertAmount("20", r.totals.get("customerConcession"));
        assertAmount("1", r.totals.get("unallocatedCount"));
        var u = r.lines.stream().filter(l -> "unallocated".equals(l.lineKind)).findFirst().orElseThrow();
        assertEquals("unallocated", u.amountBasis);
        // 未分摊不归到具体 SKU：销售毛利行不含这 20
        var item = r.lines.stream().filter(l -> "item".equals(l.lineKind)).findFirst().orElseThrow();
        assertAmount("0", item.customerConcession);
    }

    @Test
    void taxExclusiveConversionKeepsIdentities() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        LocalDate d = LocalDate.of(2026, 5, 3);
        snap.orders.add(order("P1", "S101", d, "440.00"));
        snap.lines.add(line("L1", "P1", "S101", d, "SKU-X", 2, "240.00", "sale"));
        snap.lines.add(line("L2", "P1", "S101", d, "SKU-Y", 1, "200.00", "sale"));
        snap.executions.add(exec("E1", "P1", "C1", "C1-TIER", "amount_off", "30.00", "amount", "contract", 10));
        snap.costs.add(cost("SKU-X", "S101", d, "70.00"));
        snap.costs.add(cost("SKU-Y", "S101", d, "120.00"));

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_exclusive", "day").run();
        assertChecksPass(r);
        // 440/1.13 ≈ 389.3805（四位舍入）
        assertTrue(r.totals.get("listSales").subtract(bd("389.38")).abs().compareTo(bd("0.01")) < 0,
                "不含税标价销售 " + r.totals.get("listSales"));
    }

    @Test
    void partialRefundRechecksThresholdAndKeepsCashSeparate() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        LocalDate d = LocalDate.of(2026, 5, 10);
        snap.orders.add(order("P2", "S101", d, "880.00"));
        snap.lines.add(line("L1", "P2", "S101", d, "SKU-X", 4, "480.00", "sale"));
        snap.lines.add(line("L2", "P2", "S101", d, "SKU-Y", 2, "400.00", "sale"));
        snap.executions.add(exec("E1", "P2", "C1", "C1-TIER", "amount_off", "80.00", "amount", "contract", 10));
        snap.costs.add(cost("SKU-X", "S101", d, "70.00"));
        snap.costs.add(cost("SKU-Y", "S101", d, "120.00"));
        PromoSnapshot.RefundLine rl = new PromoSnapshot.RefundLine();
        rl.refundLineId = "R1";
        rl.orderId = "P2";
        rl.orderLineId = "L1";
        rl.quantity = 2;
        rl.amount = bd("240.00");
        rl.businessDate = LocalDate.of(2026, 5, 12);
        rl.originalRuleVersionId = 1L;
        snap.refunds.add(rl);

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_inclusive", "day").run();
        assertChecksPass(r);
        // 剩余 X2@240 + Y2@400 = 640 ≥ 600，门槛仍有效，无重分配
        assertAmount("80", r.totals.get("customerConcession"));
        // 退款现金 = 原实付(880-80=800) - 剩余应付(640 - 80) = 240
        assertAmount("240", r.totals.get("refundReversal"));
        // 净销售 = 880 - 80 - 240 = 560
        assertAmount("560", r.totals.get("netSales"));
        assertAmount("0", r.totals.get("reallocationAdjustment"));
    }

    @Test
    void refundBelowThresholdRecomputesTier() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        LocalDate d = LocalDate.of(2026, 5, 10);
        snap.orders.add(order("P3", "S101", d, "480.00"));
        snap.lines.add(line("L1", "P3", "S101", d, "SKU-X", 4, "480.00", "sale"));
        snap.executions.add(exec("E1", "P3", "C1", "C1-TIER", "amount_off", "80.00", "amount", "contract", 10));
        snap.costs.add(cost("SKU-X", "S101", d, "70.00"));
        PromoSnapshot.RefundLine rl = new PromoSnapshot.RefundLine();
        rl.refundLineId = "R1";
        rl.orderId = "P3";
        rl.orderLineId = "L1";
        rl.quantity = 2;
        rl.amount = bd("240.00");
        rl.businessDate = LocalDate.of(2026, 5, 12);
        rl.originalRuleVersionId = 1L;
        snap.refunds.add(rl);

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_inclusive", "day").run();
        assertChecksPass(r);
        // 剩余 2 件 X = 240 < 300：无有效档位，重算折让=0
        // 退款现金 = 原实付(480-80=400) - 剩余应付(240-0) = 160
        assertAmount("160", r.totals.get("refundReversal"));
        // 折让冲回：原分配 80，剩余应 0，门店回补 80 的折让（reallocation = +? 见桥语义）
        // 退款现金已按重算折让确定，故重分配调整为 0；净销售 = 剩余应付 240
        assertAmount("0", r.totals.get("reallocationAdjustment"));
        assertAmount("240", r.totals.get("netSales"), "净销售 = 剩余应付");
    }

    @Test
    void buyGiftAddsCostButNoListSales() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        LocalDate d = LocalDate.of(2026, 5, 10);
        snap.orders.add(order("P4", "S101", d, "240.00"));
        snap.lines.add(line("L1", "P4", "S101", d, "SKU-X", 2, "240.00", "sale"));
        snap.lines.add(line("G1", "P4", "S101", d, "SKU-G", 1, "0.00", "gift"));
        snap.costs.add(cost("SKU-X", "S101", d, "70.00"));
        snap.costs.add(cost("SKU-G", "S101", d, "18.00"));

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_inclusive", "day").run();
        assertChecksPass(r);
        assertAmount("240", r.totals.get("listSales"));
        // 成本 = 2*70 + 18 = 158
        assertAmount("158", r.totals.get("costOfSales"));
        assertAmount("82", r.totals.get("grossMargin"));
    }

    @Test
    void stackingConflictIsFlaggedAndNotDoubleApplied() {
        PromoSnapshot snap = new PromoSnapshot();
        baseSnapshot(snap);
        PromoSnapshot.RuleVer conflictRule = new PromoSnapshot.RuleVer();
        conflictRule.id = 2L;
        conflictRule.campaignCode = "C2";
        conflictRule.version = 1;
        conflictRule.status = "published";
        conflictRule.rulesJson = "{\"allocation\":{\"basis\":\"amount\",\"evidence\":\"contract\"},"
                + "\"stacking\":[{\"ruleId\":\"R1\",\"seq\":10,\"kind\":\"threshold_off\",\"tiers\":[{\"min\":300,\"off\":30}]},"
                + "{\"ruleId\":\"R2\",\"seq\":20,\"kind\":\"sku_coupon\",\"amount\":20,\"exclusiveWith\":[\"R1\"]}]}";
        snap.rules.add(conflictRule);
        LocalDate d = LocalDate.of(2026, 5, 3);
        snap.orders.add(order("P5", "S101", d, "440.00"));
        snap.lines.add(line("L1", "P5", "S101", d, "SKU-X", 2, "240.00", "sale"));
        snap.lines.add(line("L2", "P5", "S101", d, "SKU-Y", 1, "200.00", "sale"));
        snap.costs.add(cost("SKU-X", "S101", d, "70.00"));
        snap.costs.add(cost("SKU-Y", "S101", d, "120.00"));
        PromoSnapshot.Exec e1 = exec("E1", "P5", "C2", "R1", "amount_off", "30.00", "amount", "contract", 10);
        e1.ruleVersionId = 2L;
        PromoSnapshot.Exec e2 = exec("E2", "P5", "C2", "R2", "sku_coupon", "20.00", "amount", "contract", 20);
        e2.ruleVersionId = 2L;
        snap.executions.addAll(List.of(e1, e2));

        PromoBridgeResult r = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT, "tax_inclusive", "day").run();
        var conflict = r.allocations.stream().filter(a -> "conflict".equals(a.status)).findFirst().orElse(null);
        assertNotNull(conflict, "互斥规则应记为冲突");
        assertAmount("20", conflict.unallocatedAmount);
        // 冲突的 20 仍被顾客实际获得：作为未分摊折让列示，桥守恒
        assertChecksPass(r);
        assertAmount("50", r.totals.get("customerConcession"));
    }
}
