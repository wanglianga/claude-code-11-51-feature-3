package com.semantic.platform.api;

import com.semantic.platform.promo.*;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/promo")
public class PromoController {

    private final PromoService promoService;
    private final PromoCampaignRepository campaignRepo;
    private final PromoRuleVersionRepository ruleRepo;
    private final PromoCostVersionRepository costVersionRepo;
    private final PromoSkuCostRepository skuCostRepo;
    private final PromoSupplierCompensationRepository compRepo;
    private final PromoDiscountExecutionRepository execRepo;
    private final PromoRefundLineRepository refundLineRepo;
    private final PromoPriceFactRepository priceRepo;

    public PromoController(PromoService promoService, PromoCampaignRepository campaignRepo,
                           PromoRuleVersionRepository ruleRepo, PromoCostVersionRepository costVersionRepo,
                           PromoSkuCostRepository skuCostRepo, PromoSupplierCompensationRepository compRepo,
                           PromoDiscountExecutionRepository execRepo, PromoRefundLineRepository refundLineRepo,
                           PromoPriceFactRepository priceRepo) {
        this.promoService = promoService;
        this.campaignRepo = campaignRepo;
        this.ruleRepo = ruleRepo;
        this.costVersionRepo = costVersionRepo;
        this.skuCostRepo = skuCostRepo;
        this.compRepo = compRepo;
        this.execRepo = execRepo;
        this.refundLineRepo = refundLineRepo;
        this.priceRepo = priceRepo;
    }

    // ---------- 运行 ----------
    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(name = "user", required = false) String user) {
        PromoService.RunRequest req = new PromoService.RunRequest();
        if (body.get("metricKey") != null) req.metricKey = (String) body.get("metricKey");
        if (body.get("method") != null) req.method = (String) body.get("method");
        if (body.get("taxBasis") != null) req.taxBasis = (String) body.get("taxBasis");
        req.from = LocalDate.parse((String) body.get("from"));
        req.to = LocalDate.parse((String) body.get("to"));
        if (body.get("grain") != null) req.grain = (String) body.get("grain");
        if (body.get("costVersionId") != null) req.costVersionId = Long.valueOf(body.get("costVersionId").toString());
        if (body.get("metricVersionId") != null) req.metricVersionId = Long.valueOf(body.get("metricVersionId").toString());
        PromoBridgeRun run = promoService.execute(req, user);
        return detail(run);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> listRuns() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromoBridgeRun r : promoService.list()) out.add(summary(r));
        return out;
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> getRun(@PathVariable Long id) {
        return detail(promoService.get(id));
    }

    @GetMapping("/runs/{id}/lines")
    public List<PromoBridgeLine> lines(@PathVariable Long id) { return promoService.lines(id); }

    @GetMapping("/runs/{id}/allocations")
    public List<PromoAllocation> allocations(@PathVariable Long id) { return promoService.allocations(id); }

    @GetMapping("/runs/{id}/refunds")
    public List<PromoRefundAdjustment> refunds(@PathVariable Long id) { return promoService.refundAdjustments(id); }

    @GetMapping("/runs/{id}/checks")
    public List<PromoCheck> checks(@PathVariable Long id) { return promoService.checks(id); }

    @GetMapping("/runs/{id}/versions")
    public List<PromoInputVersion> versions(@PathVariable Long id) { return promoService.inputVersions(id); }

    @GetMapping("/runs/{id}/lineage")
    public List<PromoLineage> lineage(@PathVariable Long id, @RequestParam String lineKey) {
        return promoService.lineage(id, lineKey);
    }

    @PostMapping("/runs/{id}/publish")
    public Map<String, Object> publish(@PathVariable Long id,
                                       @RequestAttribute(name = "user", required = false) String user) {
        return detail(promoService.publish(id, user));
    }

    @PostMapping("/runs/{id}/replay")
    public Map<String, Object> replay(@PathVariable Long id,
                                      @RequestAttribute(name = "user", required = false) String user) {
        return detail(promoService.replay(id, user));
    }

    @GetMapping("/runs/{id}/compare")
    public Map<String, Object> compare(@PathVariable Long id) {
        return promoService.compareAllocation(id);
    }

    // ---------- 迟到补偿 / 成本修订 / 候选 ----------
    @PostMapping("/compensations")
    public Map<String, Object> lateCompensation(@RequestBody Map<String, Object> body) {
        return promoService.lateCompensation(
                (String) body.get("compId"),
                (String) body.get("campaignCode"),
                (String) body.get("sku"),
                (String) body.get("storeId"),
                LocalDate.parse((String) body.get("businessDate")),
                body.get("amount") == null ? null : new BigDecimal(body.get("amount").toString()),
                (String) body.get("contractRef"),
                (String) body.get("marginTreatment"),
                (String) body.get("evidenceRef"));
    }

    @PostMapping("/cost-revisions")
    public Map<String, Object> reviseCost(@RequestBody Map<String, Object> body,
                                          @RequestAttribute(name = "user", required = false) String user) {
        BigDecimal factor = body.get("factor") == null ? null : new BigDecimal(body.get("factor").toString());
        return promoService.reviseCost(factor, (String) body.get("note"), user);
    }

    @GetMapping("/candidates")
    public List<PromoRunCandidate> candidates(@RequestParam(required = false) String status) {
        return promoService.candidates(status);
    }

    @PostMapping("/candidates/{id}/adopt")
    public Map<String, Object> adopt(@PathVariable Long id,
                                     @RequestAttribute(name = "user", required = false) String user) {
        return detail(promoService.adoptCandidate(id, user));
    }

    @PostMapping("/candidates/{id}/reject")
    public PromoRunCandidate reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return promoService.rejectCandidate(id, body == null ? null : body.get("note"));
    }

    // ---------- 主数据查询 ----------
    @GetMapping("/campaigns")
    public List<Map<String, Object>> campaigns() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromoCampaign c : campaignRepo.findAllByOrderByCampaignCodeAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("campaignCode", c.campaignCode);
            m.put("name", c.name);
            m.put("taxBasis", c.taxBasis);
            m.put("contractRef", c.contractRef);
            m.put("supplierCompTreatment", c.supplierCompTreatment);
            List<Map<String, Object>> versions = new ArrayList<>();
            for (PromoRuleVersion v : ruleRepo.findByCampaignCodeOrderByVersionDesc(c.campaignCode)) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("id", v.id);
                vm.put("version", v.version);
                vm.put("status", v.status);
                vm.put("definitionHash", v.definitionHash);
                vm.put("publishedAt", v.publishedAt);
                vm.put("rulesJson", v.rulesJson);
                versions.add(vm);
            }
            m.put("ruleVersions", versions);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/cost-versions")
    public List<Map<String, Object>> costVersions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PromoCostVersion v : costVersionRepo.findAllByOrderByVersionDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id);
            m.put("version", v.version);
            m.put("status", v.status);
            m.put("note", v.note);
            m.put("sourceNote", v.sourceNote);
            m.put("publishedAt", v.publishedAt);
            m.put("rows", skuCostRepo.findByCostVersionId(v.id).size());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priceFacts", priceRepo.findAllByOrderByIdAsc());
        m.put("executions", execRepo.findAllByOrderByOrderIdAscAppliedSeqAsc());
        m.put("refundLines", refundLineRepo.findAllByOrderByIdAsc());
        m.put("compensations", compRepo.findAllByOrderByBusinessDateAsc());
        return m;
    }

    // ---------- 组装 ----------
    private Map<String, Object> summary(PromoBridgeRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("runId", r.runId);
        m.put("metricKey", r.metricKey);
        m.put("method", r.method);
        m.put("taxBasis", r.taxBasis);
        m.put("periodFrom", r.periodFrom);
        m.put("periodTo", r.periodTo);
        m.put("grain", r.grain);
        m.put("status", r.status);
        m.put("publicationVersion", r.publicationVersion);
        m.put("createdAt", r.createdAt);
        m.put("publishedAt", r.publishedAt);
        m.put("publishedBy", r.publishedBy);
        m.put("note", r.note);
        m.put("totals", parse(r.totalsJson));
        m.put("warnings", parseArray(r.warningsJson));
        m.put("inputVersions", parse(r.inputVersionsJson));
        return m;
    }

    private Map<String, Object> detail(PromoBridgeRun r) {
        Map<String, Object> m = summary(r);
        m.put("checks", promoService.checks(r.id));
        m.put("lines", promoService.lines(r.id));
        m.put("allocations", promoService.allocations(r.id));
        m.put("refundAdjustments", promoService.refundAdjustments(r.id));
        m.put("inputVersionLog", promoService.inputVersions(r.id));
        m.put("warnings", parseArray(r.warningsJson));
        return m;
    }

    private Object parse(String json) {
        if (json == null) return null;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class); }
        catch (Exception e) { return null; }
    }

    private Object parseArray(String json) {
        if (json == null) return List.of();
        try {
            Object o = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
            return o;
        } catch (Exception e) { return List.of(); }
    }
}
