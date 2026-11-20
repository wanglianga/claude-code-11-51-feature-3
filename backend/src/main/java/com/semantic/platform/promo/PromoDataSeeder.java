package com.semantic.platform.promo;

import com.semantic.platform.metric.MetricService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 促销毛利桥样例数据（在基础样例之后播种，幂等）。
 *
 * 场景（北京/上海门店 S101、S102，2026-05 活动月）：
 *   C1 跨档满减（¥300减30 / ¥600减80），订单级，按金额分摊（合同+POS 双证据）
 *   C2 单品券（SKU-X 每件减 15，买 2 件起），按件数均摊
 *   C3 买赠（买 SKU-X 满 3 件赠 SKU-G 1 件）
 *   C4 积分抵扣（订单级固定 20），**合同与 POS 均不足以判断分摊基础** → 未分摊
 *   C5 厂商补贴（执行明细为 vendor_subsidy，独立列示；另附补偿凭证晚到）
 *
 * 关键订单：
 *   P001 S101 5/3：X2@120 + Y1@200 + Z1@80 = 520，跨档满减 30（按金额分摊），积分抵扣 20（未分摊）
 *   P002 S101 5/10：X4@120 + G1@0(赠)，满减命中 600 档减 80，单品券 2×15=30，买赠赠品
 *        → 5/12 部分退货 1 件 X（拆单两笔：X1+X1），退货后 X 剩 2 件、标价 480 < 600 档 → 买赠失效
 *   P003 S102 5/15：X3@120 + Y1@200 = 560，满减命中 300 档减 30，单品券 3×15=45，买赠赠品
 *        → 5/18 整单优惠部分退货 1 件 Y（200），剩余 360 ≥ 300 档 → 门槛仍有效
 *   P004 S102 5/20：X2@120 = 240，未达 300 档，无满减；仅积分抵扣 20（未分摊）
 *        → 5/21 退货 1 件 X → 整单优惠部分退货
 *   P005 S101 5/25：X5@120 + Y2@200 = 1000，满减命中 600 档减 80，单品券 5×15=75，赠品
 *        → 5/27 退货 4 件 X → 剩余 X1@120+Y2@400=520，X 上券资格 1 件 → 单品券失效、买赠失效
 */
@Component
@Order(20)
public class PromoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PromoDataSeeder.class);

    private final PromoCampaignRepository campaignRepo;
    private final PromoRuleVersionRepository ruleRepo;
    private final PromoPriceFactRepository priceRepo;
    private final PromoDiscountExecutionRepository execRepo;
    private final PromoRefundLineRepository refundLineRepo;
    private final PromoCostVersionRepository costVersionRepo;
    private final PromoSkuCostRepository skuCostRepo;
    private final PromoSupplierCompensationRepository compRepo;
    private final com.semantic.platform.source.OrderFactRepository orderRepo;
    private final com.semantic.platform.source.OrderLineRepository orderLineRepo;
    private final com.semantic.platform.source.SourceBatchRepository batchRepo;
    private final MetricService metricService;

    public PromoDataSeeder(PromoCampaignRepository campaignRepo, PromoRuleVersionRepository ruleRepo,
                           PromoPriceFactRepository priceRepo, PromoDiscountExecutionRepository execRepo,
                           PromoRefundLineRepository refundLineRepo, PromoCostVersionRepository costVersionRepo,
                           PromoSkuCostRepository skuCostRepo, PromoSupplierCompensationRepository compRepo,
                           com.semantic.platform.source.OrderFactRepository orderRepo,
                           com.semantic.platform.source.OrderLineRepository orderLineRepo,
                           com.semantic.platform.source.SourceBatchRepository batchRepo,
                           MetricService metricService) {
        this.campaignRepo = campaignRepo;
        this.ruleRepo = ruleRepo;
        this.priceRepo = priceRepo;
        this.execRepo = execRepo;
        this.refundLineRepo = refundLineRepo;
        this.costVersionRepo = costVersionRepo;
        this.skuCostRepo = skuCostRepo;
        this.compRepo = compRepo;
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
        this.batchRepo = batchRepo;
        this.metricService = metricService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (campaignRepo.count() > 0) {
            log.info("促销样例数据已存在，跳过播种");
            return;
        }
        log.info("开始播种促销毛利桥样例数据...");
        seedCampaignsAndRules();
        seedPrices();
        seedCosts();
        seedOrders();
        seedExecutions();
        seedRefunds();
        seedCompensations();
        seedBatches();
        seedMetric();
        log.info("促销毛利桥样例数据播种完成");
    }

    // ---------- 活动与规则 ----------
    private void seedCampaignsAndRules() {
        campaign("C1", "五一大促·跨档满减", "CT-2026-05-001", "tax_inclusive", "rebate");
        campaign("C2", "X 单品券", "CT-2026-05-002", "tax_inclusive", "none");
        campaign("C3", "买 X 赠 G", "CT-2026-05-003", "tax_inclusive", "none");
        campaign("C4", "会员积分抵扣", "CT-2026-05-004", "tax_inclusive", "none");
        campaign("C5", "厂商联合补贴", "CT-2026-05-005", "tax_inclusive", "rebate");

        // C1 跨档满减：¥300 减 30 / ¥600 减 80；订单级，金额分摊（contract + pos 双证据）
        rule("C1", 1,
                "{\"allocation\":{\"basis\":\"amount\",\"evidence\":\"contract\"},\n" +
                " \"stacking\":[{\"ruleId\":\"C1-TIER\",\"seq\":10,\"kind\":\"threshold_off\",\"tiers\":[" +
                "{\"min\":300,\"off\":30},{\"min\":600,\"off\":80}],\"note\":\"跨档满减，按符合资格金额分摊\"}],\n" +
                " \"refund\":{\"recheckThreshold\":true,\"recheckGift\":true}}");

        // C2 单品券：SKU-X 每件减 15，买 2 件起；按件数均摊（POS 明细）
        rule("C2", 1,
                "{\"allocation\":{\"basis\":\"quantity\",\"evidence\":\"pos\"},\n" +
                " \"stacking\":[{\"ruleId\":\"C2-XCOUPON\",\"seq\":20,\"kind\":\"sku_coupon\",\"skus\":[\"SKU-X\"]," +
                "\"perUnit\":15,\"minQty\":2,\"note\":\"买 2 件起每件减 15\"}],\n" +
                " \"refund\":{\"recheckThreshold\":true,\"recheckGift\":true}}");

        // C3 买 X 满 3 件赠 G 1 件
        rule("C3", 1,
                "{\"allocation\":{\"basis\":\"unspecified\",\"evidence\":\"none\"},\n" +
                " \"stacking\":[{\"ruleId\":\"C3-GIFT\",\"seq\":30,\"kind\":\"buy_gift\",\"skus\":[\"SKU-X\"]," +
                "\"perQty\":3,\"giftSku\":\"SKU-G\",\"giftQty\":1,\"note\":\"买 3 件 X 赠 1 件 G\"}],\n" +
                " \"refund\":{\"recheckThreshold\":true,\"recheckGift\":true}}");

        // C4 积分抵扣：固定 20；无合同/POS 分摊依据 → 未分摊
        rule("C4", 1,
                "{\"allocation\":{\"basis\":\"unspecified\",\"evidence\":\"none\"},\n" +
                " \"stacking\":[{\"ruleId\":\"C4-POINTS\",\"seq\":40,\"kind\":\"points\",\"amount\":20," +
                "\"note\":\"积分抵扣；合同与 POS 均未约定按金额或按件分摊\"}],\n" +
                " \"refund\":{\"recheckThreshold\":false,\"recheckGift\":false}}");

        // C5 厂商补贴：按订单级 50（执行明细），独立列示；不抵减顾客折让
        rule("C5", 1,
                "{\"allocation\":{\"basis\":\"amount\",\"evidence\":\"contract\"},\n" +
                " \"stacking\":[{\"ruleId\":\"C5-VENDOR\",\"seq\":50,\"kind\":\"vendor_subsidy\",\"amount\":50," +
                "\"note\":\"厂商补贴：独立列示，计入毛利分析，不混入销售收入\"}],\n" +
                " \"refund\":{\"recheckThreshold\":false,\"recheckGift\":false}}");
    }

    private void campaign(String code, String name, String contract, String taxBasis, String treatment) {
        PromoCampaign c = new PromoCampaign();
        c.campaignCode = code;
        c.name = name;
        c.contractRef = contract;
        c.taxBasis = taxBasis;
        c.supplierCompTreatment = treatment;
        campaignRepo.save(c);
    }

    private void rule(String campaign, int version, String json) {
        PromoRuleVersion r = new PromoRuleVersion();
        r.campaignCode = campaign;
        r.version = version;
        r.status = "published";
        r.rulesJson = json;
        r.effectiveFrom = LocalDate.of(2026, 5, 1);
        r.effectiveTo = LocalDate.of(2026, 5, 31);
        r.publishedAt = OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        r.createdBy = "system";
        ruleRepo.save(r);
    }

    // ---------- 价格生效事实（标价，含税） ----------
    private void seedPrices() {
        price("PF-X-S101", "SKU-X", "S101", "120.00");
        price("PF-Y-S101", "SKU-Y", "S101", "200.00");
        price("PF-Z-S101", "SKU-Z", "S101", "80.00");
        price("PF-G-S101", "SKU-G", "S101", "30.00");
        price("PF-X-S102", "SKU-X", "S102", "120.00");
        price("PF-Y-S102", "SKU-Y", "S102", "200.00");
        price("PF-G-S102", "SKU-G", "S102", "30.00");
    }

    private void price(String id, String sku, String store, String listPrice) {
        PromoPriceFact p = new PromoPriceFact();
        p.priceFactId = id;
        p.sku = sku;
        p.storeId = store;
        p.validFrom = LocalDate.of(2026, 5, 1);
        p.validTo = LocalDate.of(2026, 5, 31);
        p.listPrice = new BigDecimal(listPrice);
        p.taxBasis = "tax_inclusive";
        p.taxRate = new BigDecimal("0.1300");
        priceRepo.save(p);
    }

    // ---------- 成本（版本 1） ----------
    private void seedCosts() {
        PromoCostVersion v = new PromoCostVersion();
        v.version = 1;
        v.status = "published";
        v.note = "初始成本基线";
        v.sourceNote = "SKU 门店日成本导入";
        v.publishedAt = OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        v = costVersionRepo.save(v);
        for (LocalDate d = LocalDate.of(2026, 5, 1); !d.isAfter(LocalDate.of(2026, 5, 31)); d = d.plusDays(1)) {
            cost(v.id, "SKU-X", "S101", d, "70.00");
            cost(v.id, "SKU-Y", "S101", d, "120.00");
            cost(v.id, "SKU-Z", "S101", d, "40.00");
            cost(v.id, "SKU-G", "S101", d, "18.00");
            cost(v.id, "SKU-X", "S102", d, "70.00");
            cost(v.id, "SKU-Y", "S102", d, "120.00");
            cost(v.id, "SKU-G", "S102", d, "18.00");
        }
    }

    private void cost(Long cvId, String sku, String store, LocalDate date, String unitCost) {
        PromoSkuCost c = new PromoSkuCost();
        c.costVersionId = cvId;
        c.sku = sku;
        c.storeId = store;
        c.businessDate = date;
        c.unitCost = new BigDecimal(unitCost);
        c.taxBasis = "tax_inclusive";
        c.taxRate = new BigDecimal("0.1300");
        skuCostRepo.save(c);
    }

    // ---------- 订单与订单行（促销场景） ----------
    private void seedOrders() {
        // P001 跨档满减 + 积分（未分摊）
        order("P001", "S101", LocalDate.of(2026, 5, 3), "520.00", "completed");
        line("PL001", "P001", "S101", LocalDate.of(2026, 5, 3), "SKU-X", 2, "120.00", "240.00", "sale");
        line("PL002", "P001", "S101", LocalDate.of(2026, 5, 3), "SKU-Y", 1, "200.00", "200.00", "sale");
        line("PL003", "P001", "S101", LocalDate.of(2026, 5, 3), "SKU-Z", 1, "80.00", "80.00", "sale");

        // P002 多券叠加 + 买赠；部分退货后门槛失效
        order("P002", "S101", LocalDate.of(2026, 5, 10), "480.00", "partial_refund");
        line("PL004", "P002", "S101", LocalDate.of(2026, 5, 10), "SKU-X", 4, "120.00", "480.00", "sale");
        line("PL005", "P002", "S101", LocalDate.of(2026, 5, 10), "SKU-G", 1, "0.00", "0.00", "gift");

        // P003 整单优惠部分退货（门槛仍有效）
        order("P003", "S102", LocalDate.of(2026, 5, 15), "560.00", "partial_refund");
        line("PL006", "P003", "S102", LocalDate.of(2026, 5, 15), "SKU-X", 3, "120.00", "360.00", "sale");
        line("PL007", "P003", "S102", LocalDate.of(2026, 5, 15), "SKU-Y", 1, "200.00", "200.00", "sale");
        line("PL008", "P003", "S102", LocalDate.of(2026, 5, 15), "SKU-G", 1, "0.00", "0.00", "gift");

        // P004 未达门槛 + 积分（未分摊）；整单优惠部分退货
        order("P004", "S102", LocalDate.of(2026, 5, 20), "240.00", "partial_refund");
        line("PL009", "P004", "S102", LocalDate.of(2026, 5, 20), "SKU-X", 2, "120.00", "240.00", "sale");

        // P005 多券叠加 + 买赠；大额部分退货后券与买赠同时失效
        order("P005", "S101", LocalDate.of(2026, 5, 25), "1000.00", "partial_refund");
        line("PL010", "P005", "S101", LocalDate.of(2026, 5, 25), "SKU-X", 5, "120.00", "600.00", "sale");
        line("PL011", "P005", "S101", LocalDate.of(2026, 5, 25), "SKU-Y", 2, "200.00", "400.00", "sale");
        line("PL012", "P005", "S101", LocalDate.of(2026, 5, 25), "SKU-G", 1, "0.00", "0.00", "gift");
    }

    private void order(String id, String store, LocalDate date, String amount, String status) {
        var o = new com.semantic.platform.source.OrderFact();
        o.setOrderId(id);
        o.setStoreId(store);
        o.setOrderedAt(date.atTime(12, 0).atOffset(ZoneOffset.UTC));
        o.setPaidAt(date.atTime(12, 1).atOffset(ZoneOffset.UTC));
        o.setBusinessDate(date);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency("CNY");
        o.setStatus(status);
        o.setChannel("pos");
        o.setTaxBasis("tax_inclusive");
        o.setTaxRate(new BigDecimal("0.1300"));
        orderRepo.save(o);
    }

    private void line(String lineId, String orderId, String store, LocalDate date, String sku,
                      int qty, String unitPrice, String amount, String type) {
        var l = new com.semantic.platform.source.OrderLine();
        l.setLineId(lineId);
        l.setOrderId(orderId);
        l.setStoreId(store);
        l.setBusinessDate(date);
        l.setSku(sku);
        l.setQuantity(qty);
        l.setUnitPrice(new BigDecimal(unitPrice));
        l.setAmount(new BigDecimal(amount));
        l.setLineType(type);
        l.setTaxBasis("tax_inclusive");
        l.setTaxRate(new BigDecimal("0.1300"));
        orderLineRepo.save(l);
    }

    // ---------- 优惠执行明细（保留原始订单级金额 + 叠加顺序 + 证据） ----------
    private void seedExecutions() {
        Long c1 = ruleId("C1"), c2 = ruleId("C2"), c3 = ruleId("C3"), c4 = ruleId("C4"), c5 = ruleId("C5");

        // P001：满减 30（订单级，amount 分摊）；积分 -20（未分摊，规则证据 none）
        exec("PE001", "P001", "C1", c1, "C1-TIER", "amount_off", "order", null, null,
                "30.00", "amount", "contract", 10, "跨档满减命中 ¥300 档");
        exec("PE002", "P001", "C4", c4, "C4-POINTS", "points", "order", null, null,
                "20.00", null, "none", 40, "积分抵扣：无分摊依据 → 未分摊");

        // P002：满减 80（600 档）；单品券 2×15=30（quantity 均摊）；买赠（金额 0，赠品行）
        exec("PE003", "P002", "C1", c1, "C1-TIER", "amount_off", "order", null, null,
                "80.00", "amount", "contract", 10, "跨档满减命中 ¥600 档");
        exec("PE004", "P002", "C2", c2, "C2-XCOUPON", "sku_coupon", "order", null, null,
                "30.00", "quantity", "pos", 20, "单品券 X 每件 15 × 2 件计价");
        exec("PE005", "P002", "C3", c3, "C3-GIFT", "buy_gift", "gift", null, "PL005",
                "0.00", null, "pos", 30, "买 4 件 X 赠 1 件 G");

        // P003：满减 30（300 档）；单品券 3×15=45；买赠
        exec("PE006", "P003", "C1", c1, "C1-TIER", "amount_off", "order", null, null,
                "30.00", "amount", "contract", 10, "跨档满减命中 ¥300 档");
        exec("PE007", "P003", "C2", c2, "C2-XCOUPON", "sku_coupon", "order", null, null,
                "45.00", "quantity", "pos", 20, "单品券 X 每件 15 × 3 件计价");
        exec("PE008", "P003", "C3", c3, "C3-GIFT", "buy_gift", "gift", null, "PL008",
                "0.00", null, "pos", 30, "买 3 件 X 赠 1 件 G");

        // P004：积分 -20（未分摊）
        exec("PE009", "P004", "C4", c4, "C4-POINTS", "points", "order", null, null,
                "20.00", null, "none", 40, "积分抵扣：无分摊依据 → 未分摊");

        // P005：满减 80；单品券 5×15=75；买赠；厂商补贴 50（独立列示，按合同金额分摊但**不进入顾客折让**）
        exec("PE010", "P005", "C1", c1, "C1-TIER", "amount_off", "order", null, null,
                "80.00", "amount", "contract", 10, "跨档满减命中 ¥600 档");
        exec("PE011", "P005", "C2", c2, "C2-XCOUPON", "sku_coupon", "order", null, null,
                "75.00", "quantity", "pos", 20, "单品券 X 每件 15 × 5 件计价");
        exec("PE012", "P005", "C3", c3, "C3-GIFT", "buy_gift", "gift", null, "PL012",
                "0.00", null, "pos", 30, "买 5 件 X 赠 1 件 G");
        exec("PE013", "P005", "C5", c5, "C5-VENDOR", "vendor_subsidy", "order", null, null,
                "50.00", "amount", "contract", 50, "厂商补贴：独立列示，不计入顾客折让/销售收入");
    }

    private Long ruleId(String campaign) {
        return ruleRepo.findFirstByCampaignCodeAndStatusOrderByVersionDesc(campaign, "published")
                .map(r -> r.id).orElse(null);
    }

    private void exec(String id, String orderId, String campaign, Long ruleVersionId, String ruleId,
                      String type, String scope, String orderLineId, String giftLineId, String amount,
                      String basis, String evidence, int seq, String note) {
        PromoDiscountExecution e = new PromoDiscountExecution();
        e.executionId = id;
        e.orderId = orderId;
        e.campaignCode = campaign;
        e.ruleVersionId = ruleVersionId;
        e.ruleId = ruleId;
        e.executionType = type;
        e.scope = scope;
        e.orderLineId = orderLineId;
        e.giftLineId = giftLineId;
        e.amount = new BigDecimal(amount);
        e.taxBasis = "tax_inclusive";
        e.allocationBasis = basis;
        e.basisEvidence = evidence;
        e.appliedSeq = seq;
        e.note = note;
        execRepo.save(e);
    }

    // ---------- 原交易关联退货 ----------
    private void seedRefunds() {
        // P002 5/12 退 2 件 X（标价 240）；剩余 2 件 → 不满 600 档（买赠件数不足），
        // 重算后剩余篮子不能再享受 80 档满减；无 POS 实退现金证据，按重算结果退款
        refundLine("PR001", "PRF001", "P002", "PL004", "SKU-X", 2, "240.00", LocalDate.of(2026, 5, 12), null);
        // P003 5/18 退 1 件 Y（整单优惠部分退货，门槛仍有效）
        refundLine("PR002", "PRF002", "P003", "PL007", "SKU-Y", 1, "200.00", LocalDate.of(2026, 5, 18), null);
        // P004 5/21 退 1 件 X
        refundLine("PR003", "PRF003", "P004", "PL009", "SKU-X", 1, "120.00", LocalDate.of(2026, 5, 21), null);
        // P005 5/27 退 4 件 X
        refundLine("PR004", "PRF004", "P005", "PL010", "SKU-X", 4, "480.00", LocalDate.of(2026, 5, 27), null);
    }

    private void refundLine(String id, String refundId, String orderId, String orderLineId, String sku,
                            int qty, String amount, LocalDate date, String cash) {
        PromoRefundLine r = new PromoRefundLine();
        r.refundLineId = id;
        r.refundId = refundId;
        r.orderId = orderId;
        r.orderLineId = orderLineId;
        r.sku = sku;
        r.quantity = qty;
        r.amount = new BigDecimal(amount);
        r.cashAmount = cash == null ? null : new BigDecimal(cash);
        r.businessDate = date;
        // 原优惠版本：C1 等规则版本 1
        r.originalRuleVersionId = ruleId("C1");
        r.note = "原交易关联退货";
        refundLineRepo.save(r);
    }

    // ---------- 供应商补偿（含一条迟到凭证，默认不预置，由回放接口补充） ----------
    private void seedCompensations() {
        comp("SC001", "C5", "SKU-X", "S101", LocalDate.of(2026, 5, 31), "50.00",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "rebate", "PE013", "已确认厂商补贴");
        // SC002 迟到补偿由 /api/promo/compensations 回放（5/5 期间），不预置
    }

    private void comp(String id, String campaign, String sku, String store, LocalDate bd, String amount,
                      LocalDate ps, LocalDate pe, String treatment, String settlesExec, String evidence) {
        PromoSupplierCompensation c = new PromoSupplierCompensation();
        c.compId = id;
        c.campaignCode = campaign;
        c.sku = sku;
        c.storeId = store;
        c.businessDate = bd;
        c.periodStart = ps;
        c.periodEnd = pe;
        c.amount = new BigDecimal(amount);
        c.taxBasis = "tax_inclusive";
        c.taxRate = new BigDecimal("0.1300");
        c.marginTreatment = treatment;
        c.settlesExecutionId = settlesExec;
        c.evidenceRef = evidence;
        c.status = "confirmed";
        c.receivedAt = OffsetDateTime.of(2026, 5, 31, 10, 0, 0, 0, ZoneOffset.UTC);
        compRepo.save(c);
    }

    // ---------- 批次（供覆盖/影响分析） ----------
    private void seedBatches() {
        batch("promo_price", null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 7);
        batch("promo_discount", null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 13);
        batch("promo_refund", null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 4);
        batch("promo_cost", null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 217);
        batch("promo_compensation", null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 1);
    }

    /** 在语义层注册毛利桥数据产品指标（防止重复发布导致的唯一约束冲突）。 */
    private void seedMetric() {
        try {
            if (metricService.versions("promo_bridge_margin").isEmpty()) {
                metricService.saveDraft("promo_bridge_margin", "促销毛利桥",
                        "按活动×商品×门店×期间列示标价销售、顾客折让、退货冲回、净销售、成本、商品毛利、厂商补偿与净毛利贡献；" +
                                "厂商补偿独立列示，不混入销售收入",
                        "month",
                        "{\"op\":\"promo_bridge\",\"method\":\"amount\",\"taxBasis\":\"tax_inclusive\"}",
                        "system");
                metricService.publish("promo_bridge_margin", 1, "system");
                log.info("发布指标 promo_bridge_margin v1");
            }
            if (metricService.versions("promo_net_sales").isEmpty()) {
                // 桥的净销售额视图（不含厂商补偿），与既有 net_sales 口径对照
                metricService.saveDraft("promo_net_sales", "活动净销售额",
                        "促销桥口径的净销售额（标价−顾客折让−退货冲回+重分配）",
                        "month",
                        "{\"op\":\"promo_bridge\",\"method\":\"amount\",\"taxBasis\":\"tax_inclusive\"}",
                        "system");
                metricService.publish("promo_net_sales", 1, "system");
                log.info("发布指标 promo_net_sales v1（促销桥口径）");
            }
        } catch (Exception e) {
            log.warn("注册促销毛利桥指标失败: {}", e.getMessage());
        }
    }

    private void batch(String type, String store, LocalDate start, LocalDate end, int count) {
        var b = new com.semantic.platform.source.SourceBatch();
        b.setSourceType(type);
        b.setStoreId(store);
        b.setStatus("loaded");
        b.setPeriodStart(start);
        b.setPeriodEnd(end);
        b.setRecordCount(count);
        batchRepo.save(b);
    }
}
