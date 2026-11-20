package com.semantic.platform.promo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.engine.CellKey;
import com.semantic.platform.engine.CellValue;
import com.semantic.platform.engine.Series;
import com.semantic.platform.run.Run;
import com.semantic.platform.run.RunRepository;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderFactRepository;
import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.OrderLineRepository;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 促销毛利桥服务：快照输入 → 计算 → 持久化 → 发布；成本修订/迟到补偿生成新运行候选；
 * 历史运行按留存快照复演，互不影响。
 */
@Service
public class PromoService {

    private final PromoBridgeRunRepository runRepo;
    private final PromoBridgeLineRepository lineRepo;
    private final PromoAllocationRepository allocRepo;
    private final PromoRefundAdjustmentRepository refundAdjRepo;
    private final PromoCheckRepository checkRepo;
    private final PromoInputVersionRepository inputVerRepo;
    private final PromoRunCandidateRepository candidateRepo;
    private final PromoLineageRepository lineageRepo;
    private final PromoCampaignRepository campaignRepo;
    private final PromoRuleVersionRepository ruleRepo;
    private final PromoDiscountExecutionRepository execRepo;
    private final PromoRefundLineRepository refundLineRepo;
    private final PromoPriceFactRepository priceRepo;
    private final PromoCostVersionRepository costVersionRepo;
    private final PromoSkuCostRepository skuCostRepo;
    private final PromoSupplierCompensationRepository compRepo;
    private final OrderFactRepository orderRepo;
    private final OrderLineRepository orderLineRepo;
    private final RunRepository semanticRunRepo;
    private final SourceBatchRepository batchRepo;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public PromoService(PromoBridgeRunRepository runRepo, PromoBridgeLineRepository lineRepo,
                        PromoAllocationRepository allocRepo, PromoRefundAdjustmentRepository refundAdjRepo,
                        PromoCheckRepository checkRepo, PromoInputVersionRepository inputVerRepo,
                        PromoRunCandidateRepository candidateRepo, PromoLineageRepository lineageRepo,
                        PromoCampaignRepository campaignRepo, PromoRuleVersionRepository ruleRepo,
                        PromoDiscountExecutionRepository execRepo, PromoRefundLineRepository refundLineRepo,
                        PromoPriceFactRepository priceRepo, PromoCostVersionRepository costVersionRepo,
                        PromoSkuCostRepository skuCostRepo, PromoSupplierCompensationRepository compRepo,
                        OrderFactRepository orderRepo, OrderLineRepository orderLineRepo,
                        RunRepository semanticRunRepo, SourceBatchRepository batchRepo) {
        this.runRepo = runRepo;
        this.lineRepo = lineRepo;
        this.allocRepo = allocRepo;
        this.refundAdjRepo = refundAdjRepo;
        this.checkRepo = checkRepo;
        this.inputVerRepo = inputVerRepo;
        this.candidateRepo = candidateRepo;
        this.lineageRepo = lineageRepo;
        this.campaignRepo = campaignRepo;
        this.ruleRepo = ruleRepo;
        this.execRepo = execRepo;
        this.refundLineRepo = refundLineRepo;
        this.priceRepo = priceRepo;
        this.costVersionRepo = costVersionRepo;
        this.skuCostRepo = skuCostRepo;
        this.compRepo = compRepo;
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
        this.semanticRunRepo = semanticRunRepo;
        this.batchRepo = batchRepo;
    }

    // ================= 快照构建 =================

    /** 从当前数据库事实构建快照（固定到指定成本版本）。 */
    public PromoSnapshot buildSnapshot(LocalDate from, LocalDate to, Long costVersionId) {
        PromoSnapshot snap = new PromoSnapshot();
        for (PromoCampaign c : campaignRepo.findAllByOrderByCampaignCodeAsc()) {
            PromoSnapshot.Campaign sc = new PromoSnapshot.Campaign();
            sc.campaignCode = c.campaignCode;
            sc.name = c.name;
            sc.taxBasis = c.taxBasis;
            sc.supplierCompTreatment = c.supplierCompTreatment;
            sc.contractRef = c.contractRef;
            snap.campaigns.add(sc);
        }
        for (PromoRuleVersion r : ruleRepo.findAll()) {
            if (!"published".equals(r.status)) continue;
            PromoSnapshot.RuleVer sr = new PromoSnapshot.RuleVer();
            sr.id = r.id;
            sr.campaignCode = r.campaignCode;
            sr.version = r.version;
            sr.status = r.status;
            sr.rulesJson = r.rulesJson;
            sr.effectiveFrom = r.effectiveFrom;
            sr.effectiveTo = r.effectiveTo;
            snap.rules.add(sr);
        }
        for (OrderFact o : orderRepo.findByBusinessDateBetween(from, to)) {
            PromoSnapshot.Order so = new PromoSnapshot.Order();
            so.orderId = o.getOrderId();
            so.storeId = o.getStoreId();
            so.businessDate = o.getBusinessDate();
            so.status = o.getStatus();
            so.amount = o.getAmount();
            so.currency = o.getCurrency();
            so.taxBasis = o.getTaxBasis();
            so.taxRate = o.getTaxRate();
            snap.orders.add(so);
        }
        for (OrderLine l : orderLineRepo.findByBusinessDateBetween(from, to)) {
            PromoSnapshot.Line sl = new PromoSnapshot.Line();
            sl.lineId = l.getLineId();
            sl.orderId = l.getOrderId();
            sl.storeId = l.getStoreId();
            sl.businessDate = l.getBusinessDate();
            sl.sku = l.getSku();
            sl.quantity = l.getQuantity() == null ? 0 : l.getQuantity();
            sl.unitPrice = l.getUnitPrice();
            sl.amount = l.getAmount();
            sl.lineType = l.getLineType();
            sl.taxBasis = l.getTaxBasis();
            sl.taxRate = l.getTaxRate();
            snap.lines.add(sl);
        }
        for (PromoDiscountExecution e : execRepo.findAllByOrderByOrderIdAscAppliedSeqAsc()) {
            snap.executions.add(toSnapshot(e));
        }
        for (PromoRefundLine r : refundLineRepo.findAllByOrderByIdAsc()) {
            if (r.businessDate.isBefore(from) || r.businessDate.isAfter(to)) continue;
            PromoSnapshot.RefundLine sr = new PromoSnapshot.RefundLine();
            sr.refundLineId = r.refundLineId;
            sr.refundId = r.refundId;
            sr.orderId = r.orderId;
            sr.orderLineId = r.orderLineId;
            sr.sku = r.sku;
            sr.quantity = r.quantity;
            sr.amount = r.amount;
            sr.cashAmount = r.cashAmount;
            sr.businessDate = r.businessDate;
            sr.originalRuleVersionId = r.originalRuleVersionId;
            sr.note = r.note;
            snap.refunds.add(sr);
        }
        for (PromoPriceFact p : priceRepo.findAllByOrderByIdAsc()) {
            PromoSnapshot.Price sp = new PromoSnapshot.Price();
            sp.sku = p.sku;
            sp.storeId = p.storeId;
            sp.validFrom = p.validFrom;
            sp.validTo = p.validTo;
            sp.listPrice = p.listPrice;
            sp.currency = p.currency;
            sp.taxBasis = p.taxBasis;
            sp.taxRate = p.taxRate;
            snap.prices.add(sp);
        }
        Long cvId = costVersionId != null ? costVersionId
                : costVersionRepo.findFirstByOrderByVersionDesc().map(v -> v.id).orElse(null);
        if (cvId != null) {
            for (PromoSkuCost c : skuCostRepo.findByCostVersionId(cvId)) {
                if (c.businessDate.isBefore(from) || c.businessDate.isAfter(to)) continue;
                PromoSnapshot.Cost sc = new PromoSnapshot.Cost();
                sc.sku = c.sku;
                sc.storeId = c.storeId;
                sc.businessDate = c.businessDate;
                sc.unitCost = c.unitCost;
                sc.currency = c.currency;
                sc.taxBasis = c.taxBasis;
                sc.taxRate = c.taxRate;
                snap.costs.add(sc);
            }
        }
        for (PromoSupplierCompensation c : compRepo.findByBusinessDateBetween(from, to)) {
            PromoSnapshot.Comp sc = new PromoSnapshot.Comp();
            sc.compId = c.compId;
            sc.campaignCode = c.campaignCode;
            sc.sku = c.sku;
            sc.storeId = c.storeId;
            sc.businessDate = c.businessDate;
            sc.periodStart = c.periodStart;
            sc.periodEnd = c.periodEnd;
            sc.amount = c.amount;
            sc.currency = c.currency;
            sc.taxBasis = c.taxBasis;
            sc.taxRate = c.taxRate;
            sc.marginTreatment = c.marginTreatment;
            sc.contractRef = c.contractRef;
            sc.status = c.status;
            sc.settlesExecutionId = c.settlesExecutionId;
            sc.receivedAt = c.receivedAt;
            snap.compensations.add(sc);
        }
        return snap;
    }

    private PromoSnapshot.Exec toSnapshot(PromoDiscountExecution e) {
        PromoSnapshot.Exec se = new PromoSnapshot.Exec();
        se.executionId = e.executionId;
        se.orderId = e.orderId;
        se.campaignCode = e.campaignCode;
        se.ruleVersionId = e.ruleVersionId;
        se.ruleId = e.ruleId;
        se.executionType = e.executionType;
        se.scope = e.scope;
        se.orderLineId = e.orderLineId;
        se.giftLineId = e.giftLineId;
        se.amount = e.amount;
        se.currency = e.currency;
        se.taxBasis = e.taxBasis;
        se.allocationBasis = e.allocationBasis;
        se.basisEvidence = e.basisEvidence;
        se.appliedSeq = e.appliedSeq;
        se.note = e.note;
        return se;
    }

    /**
     * 供语义层 DSL 的 promo_bridge 节点求值：按 活动×商品×门店×line_kind 输出“净毛利贡献”。
     * 走同一引擎，保证与毛利桥数据产品口径一致（不重复实现）。
     */
    public Series evaluateSeries(LocalDate from, LocalDate to, String grain, String method, String taxBasis) {
        PromoSnapshot snap = buildSnapshot(from, to, null);
        PromoBridgeEngine engine = new PromoBridgeEngine(snap,
                "quantity".equals(method) ? PromoBridgeEngine.Method.QUANTITY : PromoBridgeEngine.Method.AMOUNT,
                taxBasis, grain);
        PromoBridgeResult result = engine.run();
        Series series = new Series();
        for (PromoBridgeResult.LineRow r : result.lines) {
            java.util.TreeMap<String, String> dims = new java.util.TreeMap<>();
            dims.put("campaign_code", r.campaignCode == null ? "-" : r.campaignCode);
            dims.put("sku", r.sku == null ? "*" : r.sku);
            dims.put("store_id", r.storeId == null ? "*" : r.storeId);
            dims.put("line_kind", r.lineKind);
            CellKey key = new CellKey(r.periodKey, dims);
            CellValue cv = CellValue.of(r.netMarginContribution);
            cv.setTrace("促销毛利桥：净毛利贡献（商品毛利 " + r.grossMargin + " + 厂商补偿 " + r.supplierCompensation + "）");
            cv.addLineage("promo_bridge_line", r.lineKey, r.netMarginContribution, true, r.amountBasis);
            series.put(key, cv);
        }
        return series;
    }

    // ================= 运行 =================

    public static class RunRequest {
        public String metricKey = "promo_bridge_margin";
        public String method = "amount";     // 展示用主口径
        public String taxBasis = "tax_inclusive";
        public LocalDate from;
        public LocalDate to;
        public String grain = "month";
        public Long costVersionId;
        public Long metricVersionId;
    }

    @Transactional
    public PromoBridgeRun execute(RunRequest req, String user) {
        if (req.from == null || req.to == null) throw new IllegalArgumentException("必须提供 from/to 期间");
        if (req.from.isAfter(req.to)) throw new IllegalArgumentException("from 不能晚于 to");
        String method = req.method == null ? "amount" : req.method;
        if (!method.equals("amount") && !method.equals("quantity"))
            throw new IllegalArgumentException("method 仅支持 amount | quantity");
        String taxBasis = req.taxBasis == null ? "tax_inclusive" : req.taxBasis;
        if (!taxBasis.equals("tax_inclusive") && !taxBasis.equals("tax_exclusive"))
            throw new IllegalArgumentException("taxBasis 仅支持 tax_inclusive | tax_exclusive");

        PromoSnapshot snap = buildSnapshot(req.from, req.to, req.costVersionId);
        PromoBridgeEngine engine = new PromoBridgeEngine(snap,
                PromoBridgeEngine.Method.valueOf(method.toUpperCase()), taxBasis, req.grain);
        PromoBridgeResult result = engine.run();

        // 语义层运行记录（与既有 run 表打通，便于报表/血缘统一引用）
        Run run = new Run();
        run.setMetricKey(req.metricKey);
        run.setMetricVersionId(req.metricVersionId == null ? -1L : req.metricVersionId);
        run.setGrain(req.grain);
        run.setPeriodFrom(req.from);
        run.setPeriodTo(req.to);
        run.setResultKind("promo_bridge");
        run.setStatus(result.totals.getOrDefault("checksFailed", BigDecimal.ZERO).signum() == 0 ? "success" : "partial");
        run.setFinishedAt(OffsetDateTime.now());
        run.setDataCutoffJson(toJson(Map.of(
                "note", "促销毛利桥按快照截止点；成本/补偿晚到只生成新候选运行",
                "periodTo", req.to.toString())));
        run = semanticRunRepo.save(run);

        PromoBridgeRun pbr = new PromoBridgeRun();
        pbr.runId = run.getId();
        pbr.metricKey = req.metricKey;
        pbr.metricVersionId = req.metricVersionId;
        pbr.method = method;
        pbr.taxBasis = taxBasis;
        pbr.periodFrom = req.from;
        pbr.periodTo = req.to;
        pbr.grain = req.grain;
        pbr.status = "draft";
        pbr.snapshotJson = toJson(snap);
        pbr.inputVersionsJson = toJson(collectInputVersions(req, snap));
        pbr.checkSummaryJson = toJson(result.checks);
        pbr.warningsJson = toJson(result.warnings);
        pbr.totalsJson = toJson(result.totals);
        pbr.note = "方法=" + method + "；税基=" + taxBasis + "；创建人=" + (user == null ? "system" : user);
        pbr = runRepo.save(pbr);

        persistResult(pbr, result, snap, req, user);
        return pbr;
    }

    private Map<String, Object> collectInputVersions(RunRequest req, PromoSnapshot snap) {
        Map<String, Object> v = new LinkedHashMap<>();
        Map<String, Integer> ruleLatest = new LinkedHashMap<>();
        for (PromoSnapshot.RuleVer r : snap.rules) {
            ruleLatest.merge(r.campaignCode, r.version, Math::max);
        }
        v.put("ruleVersions", ruleLatest);
        PromoCostVersion cv = req.costVersionId != null
                ? costVersionRepo.findById(req.costVersionId).orElse(null)
                : costVersionRepo.findFirstByOrderByVersionDesc().orElse(null);
        v.put("costVersion", cv == null ? null : cv.version);
        v.put("discountCompilation", "executions=" + snap.executions.size());
        v.put("refundRecalc", "refundLines=" + snap.refunds.size());
        v.put("priceFacts", snap.prices.size());
        v.put("compensations", snap.compensations.size());
        return v;
    }

    private void persistResult(PromoBridgeRun pbr, PromoBridgeResult result, PromoSnapshot snap,
                               RunRequest req, String user) {
        Long runId = pbr.id;
        for (PromoBridgeResult.LineRow r : result.lines) {
            PromoBridgeLine l = new PromoBridgeLine();
            l.runId = runId;
            l.lineKey = r.lineKey;
            l.periodKey = r.periodKey;
            l.campaignCode = r.campaignCode;
            l.sku = r.sku;
            l.storeId = r.storeId;
            l.lineKind = r.lineKind;
            l.listSales = r.listSales;
            l.customerConcession = r.customerConcession;
            l.refundReversal = r.refundReversal;
            l.reallocationAdjustment = r.reallocationAdjustment;
            l.netSales = r.netSales;
            l.costOfSales = r.costOfSales;
            l.grossMargin = r.grossMargin;
            l.supplierCompensation = r.supplierCompensation;
            l.netMarginContribution = r.netMarginContribution;
            l.quantitySold = r.quantitySold;
            l.quantityReturned = r.quantityReturned;
            l.amountBasis = r.amountBasis;
            l.currency = r.currency;
            l.taxBasis = r.taxBasis;
            l.flagsJson = toJson(r.flags);
            l.detailJson = toJson(r.detail);
            lineRepo.save(l);
        }
        for (PromoBridgeResult.AllocRow a : result.allocations) {
            PromoAllocation pa = new PromoAllocation();
            pa.runId = runId;
            pa.orderId = a.orderId;
            pa.executionId = a.executionId;
            pa.campaignCode = a.campaignCode;
            pa.orderLineId = a.orderLineId;
            pa.ruleVersionId = a.ruleVersionId;
            pa.appliedSeq = a.appliedSeq;
            pa.basis = a.basis;
            pa.originalAmount = a.originalAmount;
            pa.allocatedAmount = a.allocatedAmount;
            pa.unallocatedAmount = a.unallocatedAmount;
            pa.eligibleAmount = a.eligibleAmount;
            pa.eligibleQuantity = a.eligibleQuantity;
            pa.remainderCents = a.remainderCents;
            pa.phase = a.phase;
            pa.status = a.status;
            pa.reason = a.reason;
            allocRepo.save(pa);
        }
        for (PromoBridgeResult.RefundRow r : result.refundAdjustments) {
            PromoRefundAdjustment a = new PromoRefundAdjustment();
            a.runId = runId;
            a.orderId = r.orderId;
            a.refundLineId = r.refundLineId;
            a.orderLineId = r.orderLineId;
            a.originalRuleVersion = r.originalRuleVersion;
            a.method = r.method;
            a.returnBusinessDate = r.returnBusinessDate;
            a.returnedList = r.returnedList;
            a.originalPaid = r.originalPaid;
            a.remainingPaid = r.remainingPaid;
            a.refundCash = r.refundCash;
            a.discountReversal = r.discountReversal;
            a.reallocationAmount = r.reallocationAmount;
            a.thresholdLost = r.thresholdLost;
            a.giftInvalidated = r.giftInvalidated;
            a.note = r.note;
            a.detailJson = toJson(r.detail);
            refundAdjRepo.save(a);
        }
        for (PromoBridgeResult.CheckRow c : result.checks) {
            PromoCheck pc = new PromoCheck();
            pc.runId = runId;
            pc.checkCode = c.checkCode;
            pc.scopeType = c.scopeType;
            pc.scopeKey = c.scopeKey;
            pc.status = c.status;
            pc.expected = c.expected;
            pc.actual = c.actual;
            pc.diff = c.diff;
            pc.message = c.message;
            checkRepo.save(pc);
        }
        // 输入版本留痕（规则/成本/优惠编译/退货重算/毛利发布）
        registerInputVersion(runId, "rule",
                "campaigns=" + snap.campaigns.size() + ";versions=" + snap.rules.size(), pbr.inputVersionsJson, null);
        PromoCostVersion cv = req.costVersionId != null
                ? costVersionRepo.findById(req.costVersionId).orElse(null)
                : costVersionRepo.findFirstByOrderByVersionDesc().orElse(null);
        registerInputVersion(runId, "cost", "costVersion=" + (cv == null ? "none" : cv.version), null,
                hash(snap.costs.toString()));
        registerInputVersion(runId, "discount_compilation",
                "executions=" + snap.executions.size(), hash(snap.executions.toString()), hash(result.allocations.toString()));
        registerInputVersion(runId, "refund_recalc",
                "refundLines=" + snap.refunds.size(), hash(snap.refunds.toString()), hash(result.refundAdjustments.toString()));
        registerInputVersion(runId, "publication",
                "status=" + pbr.status, pbr.snapshotJson == null ? null : hash(pbr.snapshotJson), hash(pbr.totalsJson));
        // 血缘：订单行/执行/退款行/成本/补偿
        persistLineage(runId, result, snap);
    }

    @SuppressWarnings("unchecked")
    private void persistLineage(Long runId, PromoBridgeResult result, PromoSnapshot snap) {
        // 合并行的 detail 内含 members（各原始明细的 detail）；逐成员登记血缘并回指合并行键
        Map<String, String> lineIdToKey = new LinkedHashMap<>();
        for (PromoBridgeResult.LineRow r : result.lines) {
            List<Map<String, Object>> members = new ArrayList<>();
            if (r.detail != null && r.detail.get("members") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) members.add((Map<String, Object>) m);
                }
            }
            if (members.isEmpty() && r.detail != null) members.add(r.detail);
            for (Map<String, Object> d : members) {
                Object orderId = d.get("orderId");
                Object lineId = d.get("lineId");
                if (orderId != null) addLineage(runId, r.lineKey, "order", String.valueOf(orderId), null, true, null);
                if (lineId != null) {
                    lineIdToKey.put(String.valueOf(lineId), r.lineKey);
                    addLineage(runId, r.lineKey, "order_line", String.valueOf(lineId), r.listSales, true, null);
                }
                Object execId = d.get("executionId");
                if (execId != null) addLineage(runId, r.lineKey, "execution", String.valueOf(execId),
                        r.customerConcession, true, d.get("reason") == null ? null : String.valueOf(d.get("reason")));
                Object compId = d.get("compId");
                if (compId != null) addLineage(runId, r.lineKey, "compensation", String.valueOf(compId),
                        r.supplierCompensation, true, "供应商补偿独立列示，不计入销售收入");
                Object giftLineId = d.get("giftLineId");
                if (giftLineId != null) addLineage(runId, r.lineKey, "order_line", String.valueOf(giftLineId),
                        r.costOfSales.negate(), true, "买赠赠品成本");
                if ("item".equals(r.lineKind) && r.sku != null && r.storeId != null && d.get("unitCost") != null) {
                    addLineage(runId, r.lineKey, "cost", r.sku + "@" + r.storeId, r.costOfSales, true, "SKU×门店×日成本");
                }
            }
        }
        for (PromoBridgeResult.RefundRow r : result.refundAdjustments) {
            String key = r.orderLineId == null ? null : lineIdToKey.get(r.orderLineId);
            if (key == null) continue;
            addLineage(runId, key, "refund_line", r.refundLineId, r.refundCash.negate(), true, r.note);
        }
    }

    private void addLineage(Long runId, String lineKey, String type, String id, BigDecimal contribution,
                            boolean included, String reason) {
        PromoLineage l = new PromoLineage();
        l.runId = runId;
        l.lineKey = lineKey;
        l.recordType = type;
        l.recordId = id;
        l.contribution = contribution;
        l.included = included;
        l.reason = reason;
        lineageRepo.save(l);
    }

    private void registerInputVersion(Long runId, String kind, String label, String inputHash, String outputHash) {
        PromoInputVersion v = new PromoInputVersion();
        v.runId = runId;
        v.kind = kind;
        v.versionLabel = label;
        v.inputHash = inputHash == null ? null : hash(inputHash);
        v.outputHash = outputHash;
        inputVerRepo.save(v);
    }

    // ================= 发布 / 查询 =================

    @Transactional
    public PromoBridgeRun publish(Long id, String user) {
        PromoBridgeRun r = runRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("运行不存在: " + id));
        if ("published".equals(r.status)) return r;
        long fails = r.checkSummaryJson == null ? 0 : countFails(r.checkSummaryJson);
        if (fails > 0) {
            throw new IllegalStateException("守恒校验存在 " + fails + " 项未通过，禁止发布");
        }
        int nextVersion = (int) runRepo.findAll().stream()
                .filter(x -> x.metricKey.equals(r.metricKey) && x.publicationVersion != null)
                .mapToInt(x -> x.publicationVersion).max().orElse(0) + 1;
        r.status = "published";
        r.publicationVersion = nextVersion;
        r.publishedAt = OffsetDateTime.now();
        r.publishedBy = user == null ? "system" : user;
        // 同指标更早的发布保持 published（历史可复演），但标记 superseded
        for (PromoBridgeRun other : runRepo.findAll()) {
            if (!other.id.equals(r.id) && other.metricKey.equals(r.metricKey)
                    && "published".equals(other.status)) {
                other.status = "superseded";
                runRepo.save(other);
            }
        }
        PromoBridgeRun saved = runRepo.save(r);
        registerInputVersion(saved.id, "publication", "published=v" + nextVersion, hash(saved.snapshotJson), hash(saved.totalsJson));
        return saved;
    }

    private long countFails(String checkJson) {
        try {
            JsonNode node = mapper.readTree(checkJson);
            long fails = 0;
            if (node.isArray()) for (var c : node) {
                if ("fail".equals(c.path("status").asText())) fails++;
            }
            return fails;
        } catch (Exception e) { return 0; }
    }

    public List<PromoBridgeRun> list() { return runRepo.findAllByOrderByCreatedAtDesc(); }

    public PromoBridgeRun get(Long id) {
        return runRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("运行不存在: " + id));
    }

    public List<PromoBridgeLine> lines(Long id) { return lineRepo.findByRunIdOrderByPeriodKeyAscLineKeyAsc(id); }
    public List<PromoAllocation> allocations(Long id) { return allocRepo.findByRunIdOrderByOrderIdAscAppliedSeqAsc(id); }
    public List<PromoRefundAdjustment> refundAdjustments(Long id) { return refundAdjRepo.findByRunIdOrderByOrderIdAsc(id); }
    public List<PromoCheck> checks(Long id) { return checkRepo.findByRunIdOrderByIdAsc(id); }
    public List<PromoInputVersion> inputVersions(Long id) { return inputVerRepo.findByRunIdOrderByIdAsc(id); }
    public List<PromoLineage> lineage(Long id, String lineKey) { return lineageRepo.findByRunIdAndLineKeyOrderByIdAsc(id, lineKey); }

    public List<PromoRunCandidate> candidates(String status) {
        return status == null ? candidateRepo.findAllByOrderByCreatedAtDesc()
                : candidateRepo.findByStatusOrderByCreatedAtDesc(status);
    }

    // ================= 复演 / 敏感性对照 =================

    /** 用历史运行留存的输入快照复演（不读当前成本/补偿）。 */
    @Transactional
    public PromoBridgeRun replay(Long id, String user) {
        PromoBridgeRun src = get(id);
        PromoSnapshot snap;
        try {
            snap = mapper.readValue(src.snapshotJson, PromoSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("快照解析失败: " + e.getMessage());
        }
        RunRequest req = new RunRequest();
        req.metricKey = src.metricKey;
        req.metricVersionId = src.metricVersionId;
        req.method = src.method;
        req.taxBasis = src.taxBasis;
        req.from = src.periodFrom;
        req.to = src.periodTo;
        req.grain = src.grain;
        // 以快照直接重算，避免受当前数据库事实影响
        PromoBridgeEngine engine = new PromoBridgeEngine(snap,
                PromoBridgeEngine.Method.valueOf(src.method.toUpperCase()), src.taxBasis, src.grain);
        PromoBridgeResult result = engine.run();

        Run run = new Run();
        run.setMetricKey(src.metricKey);
        run.setMetricVersionId(src.metricVersionId == null ? -1L : src.metricVersionId);
        run.setGrain(src.grain);
        run.setPeriodFrom(src.periodFrom);
        run.setPeriodTo(src.periodTo);
        run.setResultKind("promo_bridge");
        run.setStatus("success");
        run.setFinishedAt(OffsetDateTime.now());
        run.setDataCutoffJson(toJson(Map.of("replayOf", src.id, "note", "按历史快照复演，不读取当前成本/补偿")));
        run = semanticRunRepo.save(run);

        PromoBridgeRun pbr = new PromoBridgeRun();
        pbr.runId = run.getId();
        pbr.metricKey = src.metricKey;
        pbr.metricVersionId = src.metricVersionId;
        pbr.method = src.method;
        pbr.taxBasis = src.taxBasis;
        pbr.periodFrom = src.periodFrom;
        pbr.periodTo = src.periodTo;
        pbr.grain = src.grain;
        pbr.status = "draft";
        pbr.snapshotJson = src.snapshotJson;
        pbr.inputVersionsJson = src.inputVersionsJson;
        pbr.checkSummaryJson = toJson(result.checks);
        pbr.warningsJson = toJson(result.warnings);
        pbr.totalsJson = toJson(result.totals);
        pbr.note = "历史复演自运行 " + src.id + "；创建人=" + (user == null ? "system" : user);
        pbr = runRepo.save(pbr);
        persistResult(pbr, result, snap, req, user);
        return pbr;
    }

    /** 两种合法分摊口径并列：同一快照、同一税基，分别强制 amount / quantity。 */
    public Map<String, Object> compareAllocation(Long id) {
        PromoBridgeRun src = get(id);
        PromoSnapshot snap;
        try {
            snap = mapper.readValue(src.snapshotJson, PromoSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("快照解析失败: " + e.getMessage());
        }
        PromoBridgeResult byAmount = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.AMOUNT,
                src.taxBasis, src.grain, PromoBridgeEngine.Method.AMOUNT).run();
        PromoBridgeResult byQuantity = new PromoBridgeEngine(snap, PromoBridgeEngine.Method.QUANTITY,
                src.taxBasis, src.grain, PromoBridgeEngine.Method.QUANTITY).run();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", src.id);
        out.put("period", src.periodFrom + " ~ " + src.periodTo);
        out.put("taxBasis", src.taxBasis);
        out.put("note", "两种合法口径并列展示，仅比较商品毛利结构差异；均不输出未经对照设计验证的活动因果增量");
        out.put("byAmount", summarize(byAmount));
        out.put("byQuantity", summarize(byQuantity));
        out.put("skuDiff", skuDiff(byAmount, byQuantity));
        out.put("causalIncrement", "未输出（需对照设计验证）");
        return out;
    }

    private Map<String, Object> summarize(PromoBridgeResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listSales", r.totals.get("listSales"));
        m.put("customerConcession", r.totals.get("customerConcession"));
        m.put("refundReversal", r.totals.get("refundReversal"));
        m.put("netSales", r.totals.get("netSales"));
        m.put("grossMargin", r.totals.get("grossMargin"));
        m.put("supplierCompensation", r.totals.get("supplierCompensation"));
        m.put("netMarginContribution", r.totals.get("netMarginContribution"));
        m.put("checksFailed", r.totals.get("checksFailed"));
        m.put("lines", r.lines.stream().filter(l -> "item".equals(l.lineKind)).map(l -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("sku", l.sku);
            x.put("storeId", l.storeId);
            x.put("campaignCode", l.campaignCode);
            x.put("customerConcession", l.customerConcession);
            x.put("netSales", l.netSales);
            x.put("grossMargin", l.grossMargin);
            return x;
        }).toList());
        return m;
    }

    private List<Map<String, Object>> skuDiff(PromoBridgeResult a, PromoBridgeResult q) {
        Map<String, BigDecimal> gmByAmount = new LinkedHashMap<>();
        Map<String, BigDecimal> gmByQty = new LinkedHashMap<>();
        for (PromoBridgeResult.LineRow l : a.lines) {
            if (!"item".equals(l.lineKind)) continue;
            gmByAmount.merge(l.sku + "@" + l.storeId, l.grossMargin, BigDecimal::add);
        }
        for (PromoBridgeResult.LineRow l : q.lines) {
            if (!"item".equals(l.lineKind)) continue;
            gmByQty.merge(l.sku + "@" + l.storeId, l.grossMargin, BigDecimal::add);
        }
        List<Map<String, Object>> diff = new ArrayList<>();
        for (String key : gmByAmount.keySet()) {
            BigDecimal amt = gmByAmount.get(key);
            BigDecimal qty = gmByQty.getOrDefault(key, BigDecimal.ZERO);
            if (amt.compareTo(qty) != 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("skuStore", key);
                d.put("grossMarginByAmount", amt);
                d.put("grossMarginByQuantity", qty);
                d.put("delta", amt.subtract(qty));
                diff.add(d);
            }
        }
        diff.sort(Comparator.comparing((Map<String, Object> m) -> ((BigDecimal) m.get("delta")).abs()).reversed());
        return diff;
    }

    // ================= 迟到补偿 / 成本修订 → 新运行候选 =================

    @Transactional
    public Map<String, Object> lateCompensation(String compId, String campaignCode, String sku, String storeId,
                                                LocalDate businessDate, BigDecimal amount, String contractRef,
                                                String marginTreatment, String evidenceRef) {
        if (compId == null || compId.isBlank()) throw new IllegalArgumentException("缺少 compId");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("补偿金额必须为正");
        PromoSupplierCompensation c = compRepo.findAll().stream()
                .filter(x -> x.compId.equals(compId)).findFirst().orElse(null);
        if (c == null) {
            c = new PromoSupplierCompensation();
            c.compId = compId;
            c.campaignCode = campaignCode;
            c.sku = sku;
            c.storeId = storeId;
            c.businessDate = businessDate;
            c.amount = amount;
            c.contractRef = contractRef;
            c.marginTreatment = marginTreatment == null ? "rebate" : marginTreatment;
            c.evidenceRef = evidenceRef;
            c.status = "confirmed";
            c.receivedAt = OffsetDateTime.now();
            compRepo.save(c);
        }
        SourceBatch batch = new SourceBatch();
        batch.setSourceType("promo_compensation");
        batch.setStoreId(storeId);
        batch.setStatus("loaded");
        batch.setPeriodStart(businessDate);
        batch.setPeriodEnd(businessDate);
        batch.setRecordCount(1);
        batchRepo.save(batch);
        return createCandidatesForLateInput("late_compensation", compId,
                businessDate, "迟到供应商补偿 " + compId + " 到达，生成新运行候选");
    }

    @Transactional
    public Map<String, Object> reviseCost(BigDecimal factor, String note, String user) {
        if (factor == null || factor.signum() <= 0) throw new IllegalArgumentException("成本系数必须为正");
        PromoCostVersion prev = costVersionRepo.findFirstByOrderByVersionDesc()
                .orElseThrow(() -> new IllegalStateException("无成本版本"));
        PromoCostVersion nv = new PromoCostVersion();
        nv.version = prev.version + 1;
        nv.status = "published";
        nv.note = note == null ? "成本追溯调整" : note;
        nv.sourceNote = "由成本版本 " + prev.version + " 按系数 " + factor + " 调整";
        nv.publishedAt = OffsetDateTime.now();
        nv = costVersionRepo.save(nv);
        for (PromoSkuCost old : skuCostRepo.findByCostVersionId(prev.id)) {
            PromoSkuCost nc = new PromoSkuCost();
            nc.costVersionId = nv.id;
            nc.sku = old.sku;
            nc.storeId = old.storeId;
            nc.businessDate = old.businessDate;
            nc.unitCost = old.unitCost.multiply(factor).setScale(2, java.math.RoundingMode.HALF_UP);
            nc.currency = old.currency;
            nc.taxBasis = old.taxBasis;
            nc.taxRate = old.taxRate;
            skuCostRepo.save(nc);
        }
        prev.status = "superseded";
        costVersionRepo.save(prev);
        return createCandidatesForLateInput("cost_revision", "cost-v" + nv.version,
                null, "成本修订到版本 " + nv.version + "，生成新运行候选；历史发布仍按旧成本复演");
    }

    private Map<String, Object> createCandidatesForLateInput(String triggerType, String triggerRef,
                                                             LocalDate date, String note) {
        List<PromoRunCandidate> created = new ArrayList<>();
        for (PromoBridgeRun r : runRepo.findAll()) {
            if (!"published".equals(r.status) && !"superseded".equals(r.status)) continue;
            if (date != null && (r.periodFrom == null || date.isBefore(r.periodFrom) || date.isAfter(r.periodTo))) continue;
            PromoRunCandidate cand = new PromoRunCandidate();
            cand.runId = r.id;
            cand.sourceRunId = r.id;
            cand.triggerType = triggerType;
            cand.triggerRef = triggerRef;
            cand.status = "pending";
            cand.note = note;
            candidateRepo.save(cand);
            created.add(cand);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triggerType", triggerType);
        out.put("triggerRef", triggerRef);
        out.put("candidates", created);
        out.put("message", "已生成 " + created.size() + " 个新运行候选；历史发布仍按原输入版本复演，待负责人采纳");
        return out;
    }

    @Transactional
    public PromoBridgeRun adoptCandidate(Long candidateId, String user) {
        PromoRunCandidate cand = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在: " + candidateId));
        PromoBridgeRun src = get(cand.runId);
        RunRequest req = new RunRequest();
        req.metricKey = src.metricKey;
        req.metricVersionId = src.metricVersionId;
        req.method = src.method;
        req.taxBasis = src.taxBasis;
        req.from = src.periodFrom;
        req.to = src.periodTo;
        req.grain = src.grain;
        // 采纳 = 用当前事实重跑（新成本版本/迟到补偿生效）
        PromoBridgeRun fresh = execute(req, user);
        cand.status = "adopted";
        candidateRepo.save(cand);
        return fresh;
    }

    @Transactional
    public PromoRunCandidate rejectCandidate(Long candidateId, String note) {
        PromoRunCandidate cand = candidateRepo.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在: " + candidateId));
        cand.status = "rejected";
        cand.note = note;
        return candidateRepo.save(cand);
    }

    // ================= 工具 =================

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (Exception e) { return null; }
    }

    private String hash(String s) {
        if (s == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }
}
