package com.semantic.platform.promo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 促销毛利桥引擎（纯计算，无 IO）。
 *
 * 桥：标价销售 − 顾客折让 − 退货冲回 + 退款重检的重分配调整 = 净销售额；
 *     净销售额 − 对应销售成本 = 商品毛利；
 *     商品毛利 + 独立列示的厂商补偿 = 净毛利贡献。
 *
 * 关键语义：
 *  1) 订单级折让只按活动资格（活动/SKU/门槛/叠加）分摊到商品行，
 *     采用确定的分币尾差规则（最大余数法）；合同与 POS 证据不足时整笔列为“未分摊”，
 *     不自行选择更有利的算法，也不把“选择”写成测得的因果增量。
 *  2) 部分退货按原订单行、原优惠版本与剩余商品重检门槛/买赠资格：
 *     现金按退货行原分摊后净额退回；若剩余商品不再满足原门槛，则重算剩余档位并做
 *     折让重分配（clawback），与现金退回分别列示；不做“按退款金额同比例冲回整单折让”。
 *  3) 供应商补偿独立列示并按合同处理方式计入毛利分析，不混入销售收入、不重复抵减折让。
 *  4) 金额内部以 6 位小数计算，落表 4 位；分币层面（2 位）严格守恒。
 */
public class PromoBridgeEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public enum Method { AMOUNT, QUANTITY }

    private final PromoSnapshot snap;
    private final Method method;
    private final String outputTaxBasis;
    private final String grain;
    /** 非空时强制所有“可按基础分摊”的执行改用该方法（用于两种合法口径的并列敏感性对照）。 */
    private final Method forceMethod;

    public PromoBridgeEngine(PromoSnapshot snap, Method method, String outputTaxBasis, String grain) {
        this(snap, method, outputTaxBasis, grain, null);
    }

    public PromoBridgeEngine(PromoSnapshot snap, Method method, String outputTaxBasis, String grain, Method forceMethod) {
        this.snap = snap;
        this.method = method;
        this.outputTaxBasis = outputTaxBasis == null ? "tax_inclusive" : outputTaxBasis;
        this.grain = "month".equals(grain) ? "month" : "day";
        this.forceMethod = forceMethod;
    }

    public PromoBridgeResult run() {
        PromoBridgeResult res = new PromoBridgeResult();
        OrderCalc oc = new OrderCalc(snap, method, outputTaxBasis, grain, forceMethod);
        oc.compute();
        res.lines.addAll(oc.lines);
        res.allocations.addAll(oc.allocations);
        res.refundAdjustments.addAll(oc.refundAdjustments);
        res.checks.addAll(oc.checks);
        res.warnings.addAll(oc.warnings);
        res.totals = oc.totals;
        res.meta.put("method", method.name().toLowerCase());
        res.meta.put("forcedBasis", forceMethod == null ? null : forceMethod.name().toLowerCase());
        res.meta.put("taxBasis", outputTaxBasis);
        res.meta.put("grain", grain);
        res.meta.put("causalIncrement",
                "未输出：本桥仅重建促销让利与毛利守恒，活动因果增量须经对照设计验证");
        return res;
    }

    // =============================== 计算体 ===============================

    static final class OrderCalc {
        final PromoSnapshot snap;
        final Method method;
        final String outBasis;
        final String grain;
        final Method forceMethod;

        final List<PromoBridgeResult.LineRow> rawLines = new ArrayList<>();
        final List<PromoBridgeResult.AllocRow> allocations = new ArrayList<>();
        final List<PromoBridgeResult.RefundRow> refundAdjustments = new ArrayList<>();
        final List<PromoBridgeResult.CheckRow> checks = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, BigDecimal> totals = new LinkedHashMap<>();
        List<PromoBridgeResult.LineRow> lines = new ArrayList<>();

        final Map<String, PromoSnapshot.Campaign> campaigns = new HashMap<>();
        final Map<Long, PromoRules> rulesById = new HashMap<>();
        final Map<String, PromoRules> rulesByCampaign = new HashMap<>();
        final Map<String, List<PromoSnapshot.Line>> linesByOrder = new LinkedHashMap<>();
        final Map<String, List<PromoSnapshot.Exec>> execsByOrder = new LinkedHashMap<>();
        final Map<String, List<PromoSnapshot.RefundLine>> refundsByOrder = new LinkedHashMap<>();
        final Map<String, PromoSnapshot.Price> priceIndex = new HashMap<>();
        final Map<String, PromoSnapshot.Cost> costIndex = new HashMap<>();

        BigDecimal orderAmountSource = BigDecimal.ZERO;
        BigDecimal costSourceFull = BigDecimal.ZERO;
        BigDecimal returnedCostTotal = BigDecimal.ZERO;

        OrderCalc(PromoSnapshot snap, Method method, String outBasis, String grain, Method forceMethod) {
            this.snap = snap;
            this.method = method;
            this.outBasis = outBasis;
            this.grain = grain;
            this.forceMethod = forceMethod;
        }

        void compute() {
            index();
            Set<String> orderIds = new LinkedHashSet<>();
            for (PromoSnapshot.Line l : snap.lines) orderIds.add(l.orderId);

            for (String orderId : orderIds) {
                List<PromoSnapshot.Line> orderLines = linesByOrder.getOrDefault(orderId, List.of());
                if (orderLines.isEmpty() || !hasSaleLine(orderLines)) continue;
                PromoSnapshot.Order order = snap.orders.stream()
                        .filter(o -> o.orderId.equals(orderId)).findFirst().orElse(null);
                if (order != null) orderAmountSource = orderAmountSource.add(convertToOut(order.amount, order.taxBasis, order.taxRate));
                compileOrder(orderId, orderLines,
                        execsByOrder.getOrDefault(orderId, List.of()),
                        refundsByOrder.getOrDefault(orderId, List.of()));
            }

            addCompensations();
            lines = mergeLines(rawLines);
            finalizeTotals();
            runChecks();
        }

        // ---------- 索引 ----------
        void index() {
            for (PromoSnapshot.Campaign c : snap.campaigns) campaigns.put(c.campaignCode, c);
            for (PromoSnapshot.RuleVer r : snap.rules) {
                try {
                    rulesById.put(r.id, MAPPER.readValue(r.rulesJson, PromoRules.class));
                } catch (Exception e) {
                    warnings.add("规则版本解析失败 " + r.id + ": " + e.getMessage());
                }
            }
            // 每个活动取“已发布的最新版本”作为默认规则上下文
            for (PromoSnapshot.Campaign c : snap.campaigns) {
                snap.rules.stream()
                        .filter(r -> r.campaignCode.equals(c.campaignCode) && "published".equals(r.status))
                        .max(Comparator.comparingInt(r -> r.version))
                        .ifPresent(r -> rulesByCampaign.put(c.campaignCode, rulesById.get(r.id)));
            }
            for (PromoSnapshot.Line l : snap.lines) linesByOrder.computeIfAbsent(l.orderId, k -> new ArrayList<>()).add(l);
            for (PromoSnapshot.Exec e : snap.executions) execsByOrder.computeIfAbsent(e.orderId, k -> new ArrayList<>()).add(e);
            for (PromoSnapshot.RefundLine r : snap.refunds) refundsByOrder.computeIfAbsent(r.orderId, k -> new ArrayList<>()).add(r);
            for (PromoSnapshot.Price p : snap.prices) {
                String k = p.sku + "|" + p.storeId;
                // 同一 SKU×门店 多个生效期：保留覆盖更晚的
                PromoSnapshot.Price cur = priceIndex.get(k);
                if (cur == null || p.validFrom.isAfter(cur.validFrom)) priceIndex.put(k, p);
            }
            for (PromoSnapshot.Cost c : snap.costs) costIndex.put(c.sku + "|" + c.storeId + "|" + c.businessDate, c);
        }

        boolean hasSaleLine(List<PromoSnapshot.Line> ls) {
            for (PromoSnapshot.Line l : ls) if (!"gift".equals(l.lineType)) return true;
            return false;
        }

        // ---------- 订单编译 ----------
        void compileOrder(String orderId, List<PromoSnapshot.Line> orderLines,
                          List<PromoSnapshot.Exec> orderExecs, List<PromoSnapshot.RefundLine> orderRefunds) {
            PromoSnapshot.Line any = orderLines.get(0);
            String storeId = any.storeId;
            LocalDate businessDate = any.businessDate;
            String period = periodKey(businessDate);
            String currency = "CNY";

            Map<String, BigDecimal> listByLine = new LinkedHashMap<>();
            Map<String, Integer> qtyByLine = new LinkedHashMap<>();
            Map<String, String> skuByLine = new LinkedHashMap<>();
            for (PromoSnapshot.Line l : orderLines) {
                skuByLine.put(l.lineId, l.sku);
                qtyByLine.put(l.lineId, l.quantity);
                if ("gift".equals(l.lineType)) { listByLine.put(l.lineId, BigDecimal.ZERO); continue; }
                PromoSnapshot.Price pf = priceAt(l.sku, l.storeId, l.businessDate);
                // 价格生效事实为“单位标价”，标价销售 = 单位标价 × 件数；无价格事实时回退订单行金额（已是行合计）
                BigDecimal list = pf != null
                        ? convertToOut(pf.listPrice, pf.taxBasis, pf.taxRate).multiply(BigDecimal.valueOf(l.quantity))
                        : convertToOut(l.amount, l.taxBasis, l.taxRate);
                listByLine.put(l.lineId, list);
            }

            Map<String, BigDecimal> concessionByLine = new LinkedHashMap<>();
            for (String id : listByLine.keySet()) concessionByLine.put(id, BigDecimal.ZERO);
            Map<String, List<String>> appliedRulesByLine = new LinkedHashMap<>();
            Map<String, String> campaignByRule = new HashMap<>();
            for (PromoSnapshot.RuleVer r : snap.rules) {
                try {
                    PromoRules pr = MAPPER.readValue(r.rulesJson, PromoRules.class);
                    for (PromoRules.Rule rule : pr.stacking) campaignByRule.put(rule.ruleId, r.campaignCode);
                } catch (Exception ignored) {}
            }
            Set<String> appliedRuleIds = new LinkedHashSet<>();

            List<PromoSnapshot.Exec> execs = new ArrayList<>(orderExecs);
            execs.sort(Comparator.comparingInt((PromoSnapshot.Exec e) -> e.appliedSeq)
                    .thenComparing(e -> e.executionId));

            for (PromoSnapshot.Exec e : execs) {
                if ("gift".equals(e.executionType) || "gift".equals(e.scope)) {
                    appliedRuleIds.add(e.ruleId == null ? e.executionId : e.ruleId);
                    continue; // 赠品行无现金金额，成本单独计入
                }
                if ("vendor_subsidy".equals(e.executionType)) {
                    // 厂商补贴属毛利桥的独立事实，不进入顾客折让、不抵减销售收入。
                    PromoBridgeResult.AllocRow ar = baseAllocRow(e);
                    ar.status = "classified_compensation";
                    ar.allocatedAmount = BigDecimal.ZERO;
                    ar.unallocatedAmount = BigDecimal.ZERO;
                    ar.reason = "厂商补贴：独立列示并按合同计入毛利分析，不混入销售收入、不重复抵减折让";
                    allocations.add(ar);
                    continue;
                }
                PromoRules.Rule rule = ruleOf(e);
                PromoRules ruleVer = rulesById.get(e.ruleVersionId);
                if (ruleVer == null) ruleVer = rulesByCampaign.get(e.campaignCode);

                if (rule != null) {
                    List<String> clashes = new ArrayList<>();
                    for (String ex : rule.exclusiveWith) if (appliedRuleIds.contains(ex)) clashes.add(ex);
                    if (!clashes.isEmpty()) {
                        PromoBridgeResult.AllocRow ar = baseAllocRow(e);
                        ar.status = "conflict";
                        BigDecimal amt = convertToOut(nz(e.amount), e.taxBasis, new BigDecimal("0.1300"));
                        ar.unallocatedAmount = setScale4(amt);
                        ar.reason = "违反叠加顺序：与已执行规则 " + String.join(", ", clashes)
                                + " 互斥；POS 已实际执行，列为冲突且不归入任何商品行";
                        allocations.add(ar);
                        addUnallocatedLine(period, e, storeId, currency, amt, ar.reason);
                        addWarn("订单 " + orderId + " 执行 " + e.executionId + " 与 " + clashes + " 互斥，列为冲突");
                        continue;
                    }
                }
                appliedRuleIds.add(rule == null ? e.executionId : rule.ruleId);

                List<PromoAllocator.Eligible> eligible = new ArrayList<>();
                for (PromoSnapshot.Line l : orderLines) {
                    if ("gift".equals(l.lineType)) continue;
                    boolean ok = rule == null || PromoRules.skuEligible(rule, l.sku);
                    if (ok) eligible.add(new PromoAllocator.Eligible(l.lineId, listByLine.get(l.lineId), l.quantity));
                }
                PromoBridgeResult.AllocRow ar = baseAllocRow(e);
                if (eligible.isEmpty()) {
                    ar.status = "invalid";
                    ar.unallocatedAmount = setScale4(nz(e.amount));
                    ar.reason = "无符合活动的商品行";
                    allocations.add(ar);
                    continue;
                }
                String basis = effectiveBasis(e, ruleVer);
                // 执行明细的金额按其自身税基换算到输出税基后再分摊，避免含税/不含税混用
                BigDecimal amount = convertToOut(nz(e.amount), e.taxBasis, new BigDecimal("0.1300"));
                ar.basis = basis;
                BigDecimal eligAmt = BigDecimal.ZERO;
                int eligQty = 0;
                for (PromoAllocator.Eligible el : eligible) { eligAmt = eligAmt.add(el.amount()); eligQty += el.quantity(); }
                ar.eligibleAmount = eligAmt;
                ar.eligibleQuantity = eligQty;

                if (!"amount".equals(basis) && !"quantity".equals(basis)) {
                    ar.status = "unallocated";
                    ar.unallocatedAmount = setScale4(amount);
                    ar.reason = "活动合同或 POS 证据不足以判断分摊基础（basis=" + basis + "），列为未分摊";
                    allocations.add(ar);
                    addUnallocatedLine(period, e, storeId, currency, amount, ar.reason);
                    continue;
                }
                PromoAllocator.Result alloc = PromoAllocator.allocate(amount, eligible, basis);
                ar.allocatedAmount = setScale4(alloc.allocated());
                ar.unallocatedAmount = setScale4(alloc.unallocated());
                ar.status = alloc.unallocated().signum() == 0 ? "allocated" : "partial";
                ar.reason = alloc.reason();
                int rem = 0;
                for (PromoAllocator.Share s : alloc.shares()) {
                    concessionByLine.merge(s.lineId(), s.amount(), BigDecimal::add);
                    rem += s.remainderCents();
                    appliedRulesByLine.computeIfAbsent(s.lineId(), k -> new ArrayList<>())
                            .add(rule == null ? e.executionId : rule.ruleId);
                }
                ar.remainderCents = rem;
                allocations.add(ar);
            }

            // 退款重检
            RefundOutcome ro = processRefunds(orderId, orderLines, listByLine, qtyByLine, concessionByLine,
                    execs, businessDate);

            // 输出销售行
            for (PromoSnapshot.Line l : orderLines) {
                if ("gift".equals(l.lineType)) {
                    BigDecimal giftCost = giftCost(l);
                    costSourceFull = costSourceFull.add(giftCost);
                    if (giftCost.signum() > 0) {
                        PromoBridgeResult.LineRow row = lineRow(period, null, l.sku, storeId, "item", currency);
                        row.listSales = BigDecimal.ZERO;
                        row.costOfSales = giftCost;
                        row.grossMargin = giftCost.negate();
                        row.netMarginContribution = giftCost.negate();
                        row.quantitySold = BigDecimal.valueOf(l.quantity);
                        row.amountBasis = "gift_cost";
                        row.flags.put("gift", true);
                        row.detail.put("giftLineId", l.lineId);
                        row.detail.put("note", "买赠赠品：无标价销售，成本计入毛利桥");
                        rawLines.add(row);
                    }
                    continue;
                }
                BigDecimal list = listByLine.get(l.lineId);
                BigDecimal concession = setScale4(concessionByLine.getOrDefault(l.lineId, BigDecimal.ZERO));
                BigDecimal refund = ro.refundCashByLine.getOrDefault(l.lineId, BigDecimal.ZERO);
                BigDecimal realloc = ro.reallocByLine.getOrDefault(l.lineId, BigDecimal.ZERO);
                BigDecimal net = list.subtract(concession).subtract(refund).add(realloc);
                int returnedQty = ro.returnedQtyByLine.getOrDefault(l.lineId, 0);
                PromoSnapshot.Cost c = costIndex.get(l.sku + "|" + l.storeId + "|" + l.businessDate);
                BigDecimal unitCost = c == null ? BigDecimal.ZERO : convertToOut(c.unitCost, c.taxBasis, c.taxRate);
                if (c == null) addWarn("SKU " + l.sku + " 门店 " + l.storeId + " " + l.businessDate + " 缺成本，按 0 计");
                int soldQty = Math.max(0, l.quantity - returnedQty);
                BigDecimal cost = unitCost.multiply(BigDecimal.valueOf(soldQty));
                costSourceFull = costSourceFull.add(unitCost.multiply(BigDecimal.valueOf(l.quantity)));
                returnedCostTotal = returnedCostTotal.add(unitCost.multiply(BigDecimal.valueOf(returnedQty)));
                BigDecimal gm = net.subtract(cost);

                List<String> appliedRules = appliedRulesByLine.getOrDefault(l.lineId, List.of());
                String campaign = appliedRules.isEmpty() ? null : campaignByRule.get(appliedRules.get(0));

                PromoBridgeResult.LineRow row = lineRow(period, campaign, l.sku, storeId, "item", currency);
                row.listSales = setScale4(list);
                row.customerConcession = concession;
                row.refundReversal = setScale4(refund);
                row.reallocationAdjustment = setScale4(realloc);
                row.netSales = setScale4(net);
                row.costOfSales = setScale4(cost);
                row.grossMargin = setScale4(gm);
                row.netMarginContribution = setScale4(gm);
                row.quantitySold = BigDecimal.valueOf(l.quantity);
                row.quantityReturned = BigDecimal.valueOf(returnedQty);
                row.amountBasis = "price_fact";
                row.detail.put("orderId", orderId);
                row.detail.put("lineId", l.lineId);
                row.detail.put("rules", appliedRules);
                row.detail.put("unitCost", unitCost);
                row.detail.put("clawbackNote", ro.thresholdLostOrders.contains(orderId) || ro.giftInvalidatedOrders.contains(orderId));
                row.detail.put("thresholdLost", ro.thresholdLostOrders.contains(orderId));
                row.detail.put("giftInvalidated", ro.giftInvalidatedOrders.contains(orderId));
                rawLines.add(row);
            }
        }

        PromoSnapshot.Price priceAt(String sku, String storeId, LocalDate date) {
            PromoSnapshot.Price p = priceIndex.get(sku + "|" + storeId);
            if (p == null) return null;
            if (date.isBefore(p.validFrom) || date.isAfter(p.validTo)) return null;
            return p;
        }

        BigDecimal giftCost(PromoSnapshot.Line l) {
            PromoSnapshot.Cost c = costIndex.get(l.sku + "|" + l.storeId + "|" + l.businessDate);
            if (c == null) { addWarn("赠品 SKU " + l.sku + " 缺成本"); return BigDecimal.ZERO; }
            return convertToOut(c.unitCost, c.taxBasis, c.taxRate).multiply(BigDecimal.valueOf(l.quantity));
        }

        // ---------- 退款重检（按订单整体计算剩余篮子） ----------
        static final class RefundOutcome {
            final Map<String, BigDecimal> refundCashByLine = new LinkedHashMap<>();
            final Map<String, BigDecimal> reallocByLine = new LinkedHashMap<>();
            final Map<String, BigDecimal> returnedListByLine = new LinkedHashMap<>();
            final Map<String, Integer> returnedQtyByLine = new LinkedHashMap<>();
            final Set<String> thresholdLostOrders = new LinkedHashSet<>();
            final Set<String> giftInvalidatedOrders = new LinkedHashSet<>();
            BigDecimal recomputedRemainingConcession = BigDecimal.ZERO;
            BigDecimal originalRemainingConcession = BigDecimal.ZERO;
            BigDecimal recomputedRefund = BigDecimal.ZERO;
            BigDecimal usedRefund = BigDecimal.ZERO;
            BigDecimal reallocDiff = BigDecimal.ZERO;
            boolean hasUnallocated = false;
        }

        /**
         * 退款模型（严禁“按退款金额同比例冲回整单折让”）：
         *  1) 用**原优惠版本**对“剩余商品篮子”重跑活动规则，得到重算后的剩余折让 recheckedConc；
         *  2) 剩余应付 = 剩余标价 − recheckedConc；退款现金 = 原实付 − 剩余应付；
         *  3) 若 POS 提供实退现金证据，则以 POS 现金为准，二者差额计入“重分配调整”保持三层恒等；
         *  4) 现金退回按各退货行“原分摊后净额”占比分摊到行，便于逐行桥展示；
         *     折让冲回（memo）按退货行原分摊折让列示，与现金退回分开。
         */
        RefundOutcome processRefunds(String orderId, List<PromoSnapshot.Line> orderLines,
                                     Map<String, BigDecimal> listByLine,
                                     Map<String, Integer> qtyByLine,
                                     Map<String, BigDecimal> concessionByLine,
                                     List<PromoSnapshot.Exec> execs,
                                     LocalDate businessDate) {
            RefundOutcome out = new RefundOutcome();
            for (String id : listByLine.keySet()) {
                out.refundCashByLine.put(id, BigDecimal.ZERO);
                out.reallocByLine.put(id, BigDecimal.ZERO);
                out.returnedListByLine.put(id, BigDecimal.ZERO);
                out.returnedQtyByLine.put(id, 0);
            }
            List<PromoSnapshot.RefundLine> refunds = refundsByOrder.getOrDefault(orderId, List.of());
            if (refunds.isEmpty()) return out;

            // 累计退货件数（不超过原数量）
            Map<String, Integer> returnedByLine = new LinkedHashMap<>();
            for (PromoSnapshot.RefundLine rl : refunds) {
                if (!listByLine.containsKey(rl.orderLineId)) continue;
                int cap = qtyByLine.getOrDefault(rl.orderLineId, 0);
                int cur = returnedByLine.getOrDefault(rl.orderLineId, 0);
                int add = Math.max(0, Math.min(rl.quantity, cap - cur));
                returnedByLine.put(rl.orderLineId, cur + add);
            }
            out.returnedQtyByLine.putAll(returnedByLine);

            // 原始实付与剩余篮子
            BigDecimal originalPaid = BigDecimal.ZERO;
            BigDecimal remainingList = BigDecimal.ZERO;
            BigDecimal originalRemainingConc = BigDecimal.ZERO;
            Map<String, BigDecimal> removedNetByLine = new LinkedHashMap<>();
            Map<String, BigDecimal> removedListByLine = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> en : listByLine.entrySet()) {
                String lineId = en.getKey();
                int qty = qtyByLine.getOrDefault(lineId, 0);
                if (qty <= 0) continue;
                BigDecimal list = en.getValue();
                BigDecimal conc = concessionByLine.getOrDefault(lineId, BigDecimal.ZERO);
                BigDecimal perList = list.divide(BigDecimal.valueOf(qty), 6, RoundingMode.HALF_UP);
                BigDecimal perConc = conc.divide(BigDecimal.valueOf(qty), 6, RoundingMode.HALF_UP);
                int ret = Math.max(0, Math.min(returnedByLine.getOrDefault(lineId, 0), qty));
                int remain = qty - ret;
                originalPaid = originalPaid.add(list).subtract(conc);
                remainingList = remainingList.add(perList.multiply(BigDecimal.valueOf(remain)));
                originalRemainingConc = originalRemainingConc.add(perConc.multiply(BigDecimal.valueOf(remain)));
                BigDecimal removedList = perList.multiply(BigDecimal.valueOf(ret));
                BigDecimal removedConc = perConc.multiply(BigDecimal.valueOf(ret));
                removedListByLine.put(lineId, removedList.setScale(4, RoundingMode.HALF_UP));
                removedNetByLine.put(lineId, removedList.subtract(removedConc).setScale(4, RoundingMode.HALF_UP));
            }

            // 未分摊折让无法归属到具体商品行，因此不参与退款同比例冲回（避免自行选择算法）；
            // 但需显式披露，避免把“未分摊”当成“零折让”而高估退款现金。
            boolean hasUnallocated = false;
            for (PromoBridgeResult.AllocRow a : allocations) {
                if (orderId.equals(a.orderId)
                        && ("unallocated".equals(a.status) || "conflict".equals(a.status))
                        && nz(a.unallocatedAmount).signum() > 0) {
                    hasUnallocated = true;
                    break;
                }
            }
            out.hasUnallocated = hasUnallocated;
            if (hasUnallocated) {
                addWarn("订单 " + orderId + " 含未分摊/冲突的订单级折让：退款现金仅按已分摊部分重算，"
                        + "未分摊部分不做同比例冲回（保持未分摊，不自行选择算法）");
            }

            // 对剩余篮子用原规则重算折让（门槛/档位/百分比/买赠资格）
            BigDecimal recheckedConc = BigDecimal.ZERO;
            boolean thresholdLost = false, giftInvalidated = false;
            for (PromoSnapshot.Exec e : execs) {
                PromoRules ruleVer = e.ruleVersionId != null ? rulesById.get(e.ruleVersionId) : rulesByCampaign.get(e.campaignCode);
                if (ruleVer == null) continue;
                PromoRules.Rule rule = ruleOf(e);
                if (rule == null) continue;
                if ("vendor_subsidy".equals(e.executionType)) continue;
                if ("gift".equals(e.executionType) || "gift".equals(e.scope)) {
                    if (rule.perQty != null) {
                        int remainQty = 0;
                        for (PromoSnapshot.Line l : orderLines) {
                            if ("gift".equals(l.lineType)) continue;
                            if (rule.skus != null && !rule.skus.isEmpty() && !rule.skus.contains(l.sku)) continue;
                            remainQty += Math.max(0, qtyByLine.getOrDefault(l.lineId, 0)
                                    - returnedByLine.getOrDefault(l.lineId, 0));
                        }
                        if (remainQty < rule.perQty && returnedQtyTotal(returnedByLine) > 0) {
                            giftInvalidated = true;
                            out.giftInvalidatedOrders.add(orderId);
                        }
                    }
                    continue;
                }
                if ("unspecified".equals(rawBasisOf(e, ruleVer))) continue; // 未分摊执行不参与重算
                BigDecimal remainEligibleAmt = BigDecimal.ZERO;
                int remainQty = 0;
                for (PromoSnapshot.Line l : orderLines) {
                    if ("gift".equals(l.lineType)) continue;
                    if (!PromoRules.skuEligible(rule, l.sku)) continue;
                    int qty = qtyByLine.getOrDefault(l.lineId, 0);
                    int ret = returnedByLine.getOrDefault(l.lineId, 0);
                    int remain = Math.max(0, qty - ret);
                    if (remain <= 0) continue;
                    BigDecimal perList = listByLine.get(l.lineId).divide(BigDecimal.valueOf(qty), 6, RoundingMode.HALF_UP);
                    remainEligibleAmt = remainEligibleAmt.add(perList.multiply(BigDecimal.valueOf(remain)));
                    remainQty += remain;
                }
                BigDecimal recomputed = recomputeRule(e, rule, remainEligibleAmt, remainQty);
                recheckedConc = recheckedConc.add(recomputed);
                BigDecimal threshold = thresholdOf(rule);
                if (threshold != null && remainEligibleAmt.compareTo(threshold) < 0
                        && returnedQtyTotal(returnedByLine) > 0 && !"percent_off".equals(rule.kind)) {
                    thresholdLost = true;
                }
            }
            if (thresholdLost) out.thresholdLostOrders.add(orderId);
            boolean tierDowngraded = recheckedConc.compareTo(originalRemainingConc) < 0
                    && returnedQtyTotal(returnedByLine) > 0;

            BigDecimal remainingPaid = remainingList.subtract(recheckedConc);
            BigDecimal recomputedRefund = originalPaid.subtract(remainingPaid).setScale(4, RoundingMode.HALF_UP);
            if (recomputedRefund.signum() < 0) recomputedRefund = BigDecimal.ZERO;

            // POS 现金证据（各退货行之和；可为空）
            BigDecimal posCash = null;
            for (PromoSnapshot.RefundLine rl : refunds) {
                if (rl.cashAmount == null) continue;
                BigDecimal c = convertToOut(rl.cashAmount, "tax_inclusive", new BigDecimal("0.1300"));
                posCash = (posCash == null ? BigDecimal.ZERO : posCash).add(c);
            }
            BigDecimal usedRefund = recomputedRefund;
            if (posCash != null && posCash.compareTo(recomputedRefund) != 0
                    && posCash.compareTo(BigDecimal.ZERO) > 0) {
                // POS 实退现金是更强证据：以实际现金入桥；与重算值的差额进入“重分配调整”保持三层恒等
                usedRefund = posCash.setScale(4, RoundingMode.HALF_UP);
            }
            out.reallocDiff = usedRefund.subtract(recomputedRefund).setScale(4, RoundingMode.HALF_UP);

            // 现金退回按“原分摊后净额”占比分摊到退货行
            BigDecimal removedNetTotal = BigDecimal.ZERO;
            for (BigDecimal v : removedNetByLine.values()) removedNetTotal = removedNetTotal.add(v);
            List<String> refundLineIds = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> en : removedNetByLine.entrySet()) {
                if (en.getValue().signum() > 0) refundLineIds.add(en.getKey());
            }
            BigDecimal assigned = BigDecimal.ZERO;
            for (int i = 0; i < refundLineIds.size(); i++) {
                String lineId = refundLineIds.get(i);
                BigDecimal share;
                if (i == refundLineIds.size() - 1) {
                    share = usedRefund.subtract(assigned);
                } else if (removedNetTotal.signum() > 0) {
                    share = usedRefund.multiply(removedNetByLine.get(lineId))
                            .divide(removedNetTotal, 4, RoundingMode.HALF_UP);
                } else {
                    share = usedRefund.divide(BigDecimal.valueOf(refundLineIds.size()), 4, RoundingMode.HALF_UP);
                }
                assigned = assigned.add(share);
                out.refundCashByLine.put(lineId, share);
            }
            // 恒等补偿：POS 实退与重算差额按退货行现金占比分摊，使 净销售 = 剩余应付 始终成立
            if (out.reallocDiff.signum() != 0 && !refundLineIds.isEmpty()) {
                BigDecimal acc = BigDecimal.ZERO;
                for (int i = 0; i < refundLineIds.size(); i++) {
                    String lineId = refundLineIds.get(i);
                    BigDecimal share = (i == refundLineIds.size() - 1)
                            ? out.reallocDiff.subtract(acc)
                            : (usedRefund.signum() > 0
                                ? out.reallocDiff.multiply(out.refundCashByLine.get(lineId))
                                    .divide(usedRefund, 4, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO);
                    acc = acc.add(share);
                    out.reallocByLine.merge(lineId, share, BigDecimal::add);
                }
            }
            out.recomputedRemainingConcession = recheckedConc.setScale(4, RoundingMode.HALF_UP);
            out.originalRemainingConcession = originalRemainingConc.setScale(4, RoundingMode.HALF_UP);
            out.recomputedRefund = recomputedRefund;
            out.usedRefund = usedRefund;

            // 退款审计行
            for (PromoSnapshot.RefundLine rl : refunds) {
                PromoBridgeResult.RefundRow row = new PromoBridgeResult.RefundRow();
                row.orderId = orderId;
                row.refundLineId = rl.refundLineId;
                row.orderLineId = rl.orderLineId;
                row.originalRuleVersion = rl.originalRuleVersionId;
                row.method = method == Method.AMOUNT ? "amount" : "quantity";
                row.returnBusinessDate = rl.businessDate;
                row.returnedList = setScale4(removedListByLine.getOrDefault(rl.orderLineId, BigDecimal.ZERO));
                row.refundCash = setScale4(out.refundCashByLine.getOrDefault(rl.orderLineId, BigDecimal.ZERO));
                row.discountReversal = setScale4(concessionByLine.getOrDefault(rl.orderLineId, BigDecimal.ZERO)
                        .divide(BigDecimal.valueOf(Math.max(1, qtyByLine.getOrDefault(rl.orderLineId, 1))), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(out.returnedQtyByLine.getOrDefault(rl.orderLineId, 0))));
                row.reallocationAmount = setScale4(out.reallocByLine.getOrDefault(rl.orderLineId, BigDecimal.ZERO));
                row.thresholdLost = thresholdLost;
                row.giftInvalidated = giftInvalidated;
                row.detail.put("tierDowngraded", tierDowngraded);
                row.originalPaid = setScale4(originalPaid);
                row.remainingPaid = setScale4(remainingPaid);
                row.note = (thresholdLost || giftInvalidated)
                        ? "退货后重检：剩余商品不再满足原门槛/买赠资格，按剩余篮子重算折让后确定退款（非按退款金额同比例冲回整单折让）"
                        : "剩余商品仍满足资格：按剩余篮子重算折让，退款=原实付−剩余应付";
                row.detail.put("originalRemainingConcession", out.originalRemainingConcession);
                row.detail.put("recomputedRemainingConcession", out.recomputedRemainingConcession);
                row.detail.put("remainingList", setScale4(remainingList));
                row.detail.put("recomputedRefund", out.recomputedRefund);
                row.detail.put("usedRefund", out.usedRefund);
                row.detail.put("posCashEvidence", posCash == null ? null : setScale4(posCash));
                row.detail.put("hasUnallocatedDiscount", out.hasUnallocated);
                if (out.hasUnallocated) {
                    row.note = row.note + "；含未分摊折让，未做同比例冲回（退款现金仅按已分摊部分重算）";
                }
                refundAdjustments.add(row);
            }
            return out;
        }

        int returnedQtyTotal(Map<String, Integer> returnedByLine) {
            int t = 0;
            for (int v : returnedByLine.values()) t += v;
            return t;
        }

        /** 原始声明基础（不看强制口径），用于决定退款重算是否跳过未分摊执行。 */
        String rawBasisOf(PromoSnapshot.Exec e, PromoRules ruleVer) {
            if (e.allocationBasis != null && (e.allocationBasis.equals("amount") || e.allocationBasis.equals("quantity"))) {
                return e.allocationBasis;
            }
            if ("none".equals(e.basisEvidence)) return "unspecified";
            if (ruleVer != null && ruleVer.allocation != null
                    && (ruleVer.allocation.basis.equals("amount") || ruleVer.allocation.basis.equals("quantity"))
                    && !"none".equals(ruleVer.allocation.evidence)) {
                return ruleVer.allocation.basis;
            }
            return "unspecified";
        }

        BigDecimal recomputeRule(PromoSnapshot.Exec e, PromoRules.Rule rule,
                                 BigDecimal remainEligibleAmt, int remainEligibleQty) {
            if (remainEligibleAmt.signum() <= 0 && remainEligibleQty <= 0) return BigDecimal.ZERO;
            return switch (rule.kind == null ? "" : rule.kind) {
                case "threshold_off", "vendor_subsidy", "points" -> {
                    if (rule.tiers != null && !rule.tiers.isEmpty()) {
                        BigDecimal best = BigDecimal.ZERO;
                        BigDecimal bestMin = null;
                        for (PromoRules.Tier t : rule.tiers) {
                            if (t.min == null || t.off == null) continue;
                            if (remainEligibleAmt.compareTo(t.min) >= 0 && (bestMin == null || t.min.compareTo(bestMin) > 0)) {
                                bestMin = t.min; best = t.off;
                            }
                        }
                        yield best.min(remainEligibleAmt);
                    }
                    BigDecimal threshold = rule.minAmount != null ? rule.minAmount : rule.threshold;
                    if (threshold != null && remainEligibleAmt.compareTo(threshold) >= 0) {
                        BigDecimal off = rule.amount != null ? rule.amount : nz(e.amount);
                        yield off.min(remainEligibleAmt);
                    }
                    yield BigDecimal.ZERO;
                }
                case "percent_off" -> {
                    BigDecimal rate = rule.rate != null ? rule.rate : BigDecimal.ZERO;
                    yield remainEligibleAmt.multiply(rate).setScale(6, RoundingMode.HALF_UP);
                }
                case "sku_coupon" -> {
                    Integer minQty = rule.minQty;
                    if (minQty != null && remainEligibleQty < minQty) yield BigDecimal.ZERO;
                    BigDecimal off;
                    if (rule.perUnit != null) {
                        off = rule.perUnit.multiply(BigDecimal.valueOf(remainEligibleQty));
                    } else if (rule.amount != null) {
                        off = rule.amount;
                    } else {
                        off = nz(e.amount);
                    }
                    yield off.min(remainEligibleAmt);
                }
                default -> BigDecimal.ZERO;
            };
        }

        String skuOf(List<PromoSnapshot.Line> lines, String lineId) {
            for (PromoSnapshot.Line l : lines) if (l.lineId.equals(lineId)) return l.sku;
            return null;
        }

        /** 规则的门槛（金额）：minAmount 优先，其次 threshold，最后 tiers 的最低档。 */
        BigDecimal thresholdOf(PromoRules.Rule rule) {
            if (rule == null) return null;
            if (rule.minAmount != null) return rule.minAmount;
            if (rule.threshold != null) return rule.threshold;
            if (rule.tiers != null && !rule.tiers.isEmpty()) {
                BigDecimal min = null;
                for (PromoRules.Tier t : rule.tiers) {
                    if (t.min == null) continue;
                    if (min == null || t.min.compareTo(min) < 0) min = t.min;
                }
                return min;
            }
            return null;
        }

        // ---------- 补偿 ----------
        void addCompensations() {
            for (PromoSnapshot.Comp c : snap.compensations) {
                if (!"confirmed".equals(c.status)) {
                    addWarn("补偿 " + c.compId + " 状态为 " + c.status + "，不进入已发布运行");
                    continue;
                }
                String period = periodKey(c.businessDate);
                String sku = c.sku == null ? "*" : c.sku;
                String store = c.storeId == null ? "*" : c.storeId;
                PromoBridgeResult.LineRow row = lineRow(period, c.campaignCode, sku, store, "compensation", "CNY");
                BigDecimal amt = convertToOut(c.amount, c.taxBasis, c.taxRate);
                row.supplierCompensation = setScale4(amt);
                row.netMarginContribution = row.supplierCompensation;
                row.grossMargin = BigDecimal.ZERO;
                row.netSales = BigDecimal.ZERO; // 不进入销售收入
                row.detail.put("compId", c.compId);
                row.detail.put("marginTreatment", c.marginTreatment);
                row.detail.put("contractRef", c.contractRef);
                row.detail.put("receivedAt", c.receivedAt == null ? null : c.receivedAt.toString());
                row.detail.put("settlesExecutionId", c.settlesExecutionId);
                row.flags.put("treatment", c.marginTreatment);
                rawLines.add(row);
            }
        }

        void addUnallocatedLine(String period, PromoSnapshot.Exec e, String storeId, String currency,
                                BigDecimal amount, String reason) {
            PromoBridgeResult.LineRow row = lineRow(period, e.campaignCode, "*", storeId, "unallocated", currency);
            // 未分摊：不归属任何商品行；在桥中作为独立“未分摊折让”列示并减少净销售，
            // 但不伪装成具体 SKU 折让，也不计入任何 SKU 的毛利。
            row.listSales = BigDecimal.ZERO;
            row.customerConcession = setScale4(amount);
            row.netSales = setScale4(amount.negate());
            row.costOfSales = BigDecimal.ZERO;
            row.grossMargin = setScale4(amount.negate());
            row.netMarginContribution = setScale4(amount.negate());
            row.amountBasis = "unallocated";
            row.detail.put("orderId", e.orderId);
            row.detail.put("executionId", e.executionId);
            row.detail.put("originalAmount", setScale4(amount));
            row.detail.put("reason", reason);
            row.flags.put("unallocatedOriginalAmount", setScale4(amount));
            rawLines.add(row);
        }

        // ---------- 合并同键明细 ----------
        List<PromoBridgeResult.LineRow> mergeLines(List<PromoBridgeResult.LineRow> raw) {
            Map<String, PromoBridgeResult.LineRow> byKey = new LinkedHashMap<>();
            for (PromoBridgeResult.LineRow r : raw) {
                PromoBridgeResult.LineRow agg = byKey.get(r.lineKey);
                if (agg == null) {
                    agg = new PromoBridgeResult.LineRow();
                    agg.lineKey = r.lineKey;
                    agg.periodKey = r.periodKey;
                    agg.campaignCode = r.campaignCode;
                    agg.sku = r.sku;
                    agg.storeId = r.storeId;
                    agg.lineKind = r.lineKind;
                    agg.amountBasis = r.amountBasis;
                    agg.currency = r.currency;
                    agg.taxBasis = r.taxBasis;
                    agg.flags.putAll(r.flags);
                    agg.detail.put("members", new ArrayList<Map<String, Object>>());
                    byKey.put(r.lineKey, agg);
                }
                agg.listSales = agg.listSales.add(r.listSales);
                agg.customerConcession = agg.customerConcession.add(r.customerConcession);
                agg.refundReversal = agg.refundReversal.add(r.refundReversal);
                agg.reallocationAdjustment = agg.reallocationAdjustment.add(r.reallocationAdjustment);
                agg.netSales = agg.netSales.add(r.netSales);
                agg.costOfSales = agg.costOfSales.add(r.costOfSales);
                agg.grossMargin = agg.grossMargin.add(r.grossMargin);
                agg.supplierCompensation = agg.supplierCompensation.add(r.supplierCompensation);
                agg.netMarginContribution = agg.netMarginContribution.add(r.netMarginContribution);
                agg.quantitySold = agg.quantitySold.add(r.quantitySold);
                agg.quantityReturned = agg.quantityReturned.add(r.quantityReturned);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> members = (List<Map<String, Object>>) agg.detail.get("members");
                members.add(new LinkedHashMap<>(r.detail));
            }
            List<PromoBridgeResult.LineRow> out = new ArrayList<>();
            for (PromoBridgeResult.LineRow r : byKey.values()) {
                r.listSales = setScale4(r.listSales);
                r.customerConcession = setScale4(r.customerConcession);
                r.refundReversal = setScale4(r.refundReversal);
                r.reallocationAdjustment = setScale4(r.reallocationAdjustment);
                r.netSales = setScale4(r.netSales);
                r.costOfSales = setScale4(r.costOfSales);
                r.grossMargin = setScale4(r.grossMargin);
                r.supplierCompensation = setScale4(r.supplierCompensation);
                r.netMarginContribution = setScale4(r.netMarginContribution);
                out.add(r);
            }
            out.sort(Comparator.comparing((PromoBridgeResult.LineRow r) -> r.periodKey)
                    .thenComparing(r -> r.campaignCode == null ? "" : r.campaignCode)
                    .thenComparing(r -> r.sku == null ? "" : r.sku)
                    .thenComparing(r -> r.storeId == null ? "" : r.storeId)
                    .thenComparing(r -> r.lineKind));
            return out;
        }

        // ---------- 汇总 ----------
        void finalizeTotals() {
            BigDecimal list = BigDecimal.ZERO, concession = BigDecimal.ZERO, refund = BigDecimal.ZERO,
                    realloc = BigDecimal.ZERO, net = BigDecimal.ZERO, cost = BigDecimal.ZERO,
                    gm = BigDecimal.ZERO, comp = BigDecimal.ZERO, contribution = BigDecimal.ZERO;
            for (PromoBridgeResult.LineRow r : lines) {
                list = list.add(r.listSales);
                concession = concession.add(r.customerConcession);
                refund = refund.add(r.refundReversal);
                realloc = realloc.add(r.reallocationAdjustment);
                net = net.add(r.netSales);
                cost = cost.add(r.costOfSales);
                gm = gm.add(r.grossMargin);
                comp = comp.add(r.supplierCompensation);
                contribution = contribution.add(r.netMarginContribution);
            }
            totals.put("listSales", setScale4(list));
            totals.put("customerConcession", setScale4(concession));
            totals.put("refundReversal", setScale4(refund));
            totals.put("reallocationAdjustment", setScale4(realloc));
            totals.put("netSales", setScale4(net));
            totals.put("costOfSales", setScale4(cost));
            totals.put("grossMargin", setScale4(gm));
            totals.put("supplierCompensation", setScale4(comp));
            totals.put("netMarginContribution", setScale4(contribution));
            totals.put("unallocatedCount", BigDecimal.valueOf(
                    lines.stream().filter(r -> "unallocated".equals(r.lineKind)).count()));
            totals.put("orderAmountSource", setScale4(orderAmountSource));
            totals.put("costSourceFull", setScale4(costSourceFull));
            BigDecimal execOriginal = BigDecimal.ZERO;
            for (PromoSnapshot.Exec e : snap.executions) {
                if ("gift".equals(e.executionType) || "vendor_subsidy".equals(e.executionType)) continue;
                execOriginal = execOriginal.add(convertToOut(nz(e.amount), e.taxBasis, new BigDecimal("0.1300")));
            }
            totals.put("executionOriginal", setScale4(execOriginal));
        }

        // ---------- 校验 ----------
        void runChecks() {
            // 1) 标价合计 = 订单金额来源
            checks.add(check("LIST_RETURNS_TO_ORDERS", "标价销售合计应回到订单来源金额（分）",
                    totals.get("orderAmountSource"), totals.get("listSales"), new BigDecimal("0.01")));

            // 2) 已分摊 + 未分摊 + 冲突/无效 = 订单级优惠执行原始金额
            BigDecimal allocatedSum = BigDecimal.ZERO, unallocSum = BigDecimal.ZERO;
            for (PromoBridgeResult.AllocRow a : allocations) {
                allocatedSum = allocatedSum.add(a.allocatedAmount);
                unallocSum = unallocSum.add(a.unallocatedAmount);
            }
            checks.add(check("CONCESSION_CONSERVATION", "已分摊+未分摊/冲突应等于订单级优惠执行原始金额（分）",
                    totals.get("executionOriginal"), allocatedSum.add(unallocSum), new BigDecimal("0.05")));

            // 3) 顾客实付守恒：标价 − 折让 = 订单金额 − 优惠执行原始额
            BigDecimal paidSource = totals.get("orderAmountSource").subtract(totals.get("executionOriginal"));
            BigDecimal paidBridge = totals.get("listSales").subtract(totals.get("customerConcession"));
            checks.add(check("PAID_CONSERVATION", "顾客实付（标价−折让）应等于订单金额−优惠执行额（分）",
                    paidSource, paidBridge, new BigDecimal("0.05")));

            // 4) 净销售额恒等
            BigDecimal expectedNet = totals.get("listSales")
                    .subtract(totals.get("customerConcession"))
                    .subtract(totals.get("refundReversal"))
                    .add(totals.get("reallocationAdjustment"));
            checks.add(check("NET_SALES_IDENTITY", "净销售额 = 标价−折让−退货冲回+重分配",
                    expectedNet, totals.get("netSales"), new BigDecimal("0.01")));

            // 5) 商品毛利恒等
            checks.add(check("GROSS_MARGIN_IDENTITY", "商品毛利 = 净销售额 − 销售成本",
                    totals.get("netSales").subtract(totals.get("costOfSales")), totals.get("grossMargin"),
                    new BigDecimal("0.01")));

            // 6) 净毛利贡献 = 商品毛利 + 独立列示的厂商补偿
            checks.add(check("CONTRIBUTION_IDENTITY", "净毛利贡献 = 商品毛利 + 独立列示的厂商补偿",
                    totals.get("grossMargin").add(totals.get("supplierCompensation")),
                    totals.get("netMarginContribution"), new BigDecimal("0.01")));

            // 7) 成本回到来源：销售成本 + 退货成本回补 = 全量成本来源
            checks.add(check("COST_RETURNS_TO_SOURCE", "销售成本 + 退货成本回补 = SKU×门店×日成本来源",
                    totals.get("costSourceFull"), totals.get("costOfSales").add(returnedCostTotal),
                    new BigDecimal("0.05")));

            // 8) 补偿回到来源
            BigDecimal compSource = BigDecimal.ZERO;
            for (PromoSnapshot.Comp c : snap.compensations) {
                if (!"confirmed".equals(c.status)) continue;
                compSource = compSource.add(convertToOut(c.amount, c.taxBasis, c.taxRate));
            }
            checks.add(check("COMPENSATION_RETURNS_TO_SOURCE", "厂商补偿合计应回到补偿凭证来源",
                    compSource, totals.get("supplierCompensation"), new BigDecimal("0.01")));

            // 9) 退货现金上限（不等式校验）：退货冲回现金 ≤ 退货行标价合计
            BigDecimal returnedListTotal = BigDecimal.ZERO;
            for (PromoSnapshot.RefundLine r : snap.refunds) {
                returnedListTotal = returnedListTotal.add(convertToOut(r.amount, "tax_inclusive", new BigDecimal("0.1300")));
            }
            PromoBridgeResult.CheckRow rcheck = new PromoBridgeResult.CheckRow();
            rcheck.checkCode = "REFUND_WITHIN_RETURNED_LIST";
            rcheck.message = "退货冲回现金不应超过退货行标价合计（不等式）";
            rcheck.expected = setScale4(returnedListTotal);
            rcheck.actual = setScale4(totals.get("refundReversal"));
            rcheck.diff = setScale4(rcheck.actual.subtract(rcheck.expected));
            if (rcheck.actual.compareTo(rcheck.expected) > 0) {
                rcheck.status = "fail";
                rcheck.message = "退货冲回现金 " + fmt(rcheck.actual) + " 超过退货行标价 " + fmt(rcheck.expected);
            } else {
                rcheck.status = "pass";
            }
            checks.add(rcheck);

            long fails = checks.stream().filter(c -> "fail".equals(c.status)).count();
            long warns = checks.stream().filter(c -> "warn".equals(c.status)).count();
            totals.put("checksTotal", BigDecimal.valueOf(checks.size()));
            totals.put("checksFailed", BigDecimal.valueOf(fails));
            totals.put("checksWarned", BigDecimal.valueOf(warns));
            totals.put("checksPassed", BigDecimal.valueOf(checks.size() - fails - warns));
            if (fails > 0) addWarn("存在 " + fails + " 项守恒校验未通过，运行仍可按快照复演，但不建议发布");
        }

        PromoBridgeResult.CheckRow check(String code, String msg, BigDecimal expected, BigDecimal actual, BigDecimal tolerance) {
            PromoBridgeResult.CheckRow c = new PromoBridgeResult.CheckRow();
            c.checkCode = code;
            c.message = msg;
            c.expected = setScale4(expected);
            c.actual = setScale4(actual);
            BigDecimal diff = nz(expected).subtract(nz(actual));
            c.diff = setScale4(diff);
            if (diff.abs().compareTo(tolerance) <= 0) c.status = "pass";
            else { c.status = "fail"; c.message = msg + "（差 " + fmt(diff) + "，容差 " + fmt(tolerance) + "）"; }
            return c;
        }

        // ---------- 工具 ----------
        PromoRules.Rule ruleOf(PromoSnapshot.Exec e) {
            PromoRules rv = e.ruleVersionId != null ? rulesById.get(e.ruleVersionId) : rulesByCampaign.get(e.campaignCode);
            if (rv == null) return null;
            if (e.ruleId != null) {
                PromoRules.Rule r = rv.find(e.ruleId);
                if (r != null) return r;
            }
            for (PromoRules.Rule r : rv.stacking) if (matchesType(r, e.executionType)) return r;
            return null;
        }

        boolean matchesType(PromoRules.Rule r, String execType) {
            if (execType == null) return false;
            return switch (execType) {
                case "amount_off" -> "threshold_off".equals(r.kind);
                case "percent_off" -> "percent_off".equals(r.kind);
                case "sku_coupon" -> "sku_coupon".equals(r.kind);
                case "buy_gift" -> "buy_gift".equals(r.kind);
                case "points" -> "points".equals(r.kind);
                case "vendor_subsidy" -> "vendor_subsidy".equals(r.kind);
                default -> r.kind != null && r.kind.equals(execType);
            };
        }

        String effectiveBasis(PromoSnapshot.Exec e, PromoRules rulesVer) {
            String declared;
            if (e.allocationBasis != null && (e.allocationBasis.equals("amount") || e.allocationBasis.equals("quantity"))) {
                declared = e.allocationBasis;
            } else if ("none".equals(e.basisEvidence)) {
                return "unspecified";
            } else if (rulesVer != null && rulesVer.allocation != null
                    && (rulesVer.allocation.basis.equals("amount") || rulesVer.allocation.basis.equals("quantity"))
                    && !"none".equals(rulesVer.allocation.evidence)) {
                declared = rulesVer.allocation.basis;
            } else {
                return "unspecified";
            }
            // 并列敏感性对照：强制口径时，对“可按基础分摊”的执行改用强制口径
            if (forceMethod != null) return forceMethod == Method.AMOUNT ? "amount" : "quantity";
            return declared;
        }

        PromoBridgeResult.AllocRow baseAllocRow(PromoSnapshot.Exec e) {
            PromoBridgeResult.AllocRow ar = new PromoBridgeResult.AllocRow();
            ar.orderId = e.orderId;
            ar.executionId = e.executionId;
            ar.campaignCode = e.campaignCode;
            ar.orderLineId = e.orderLineId;
            ar.ruleVersionId = e.ruleVersionId;
            ar.appliedSeq = e.appliedSeq;
            ar.originalAmount = setScale4(nz(e.amount));
            return ar;
        }

        PromoBridgeResult.LineRow lineRow(String period, String campaign, String sku, String store,
                                          String kind, String currency) {
            PromoBridgeResult.LineRow r = new PromoBridgeResult.LineRow();
            r.periodKey = period;
            r.campaignCode = campaign;
            r.sku = sku;
            r.storeId = store;
            r.lineKind = kind;
            r.currency = currency == null ? "CNY" : currency;
            r.taxBasis = outBasis;
            r.lineKey = period + "|" + (campaign == null ? "-" : campaign) + "|" + sku + "|" + store + "|" + kind;
            return r;
        }

        String periodKey(LocalDate d) {
            return "month".equals(grain) ? YearMonth.from(d).toString() : d.toString();
        }

        BigDecimal convertToOut(BigDecimal amount, String srcBasis, BigDecimal rate) {
            if (amount == null) return BigDecimal.ZERO;
            String sb = srcBasis == null ? "tax_inclusive" : srcBasis;
            BigDecimal r = rate == null ? BigDecimal.ZERO : rate;
            if (sb.equals(outBasis)) return amount;
            if ("tax_inclusive".equals(sb) && "tax_exclusive".equals(outBasis))
                return amount.divide(BigDecimal.ONE.add(r), 6, RoundingMode.HALF_UP);
            if ("tax_exclusive".equals(sb) && "tax_inclusive".equals(outBasis))
                return amount.multiply(BigDecimal.ONE.add(r)).setScale(6, RoundingMode.HALF_UP);
            return amount;
        }

        void addWarn(String w) { if (!warnings.contains(w)) warnings.add(w); }

        static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

        static BigDecimal setScale4(BigDecimal v) { return nz(v).setScale(4, RoundingMode.HALF_UP); }

        static String fmt(BigDecimal v) { return nz(v).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    }
}
