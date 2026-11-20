package com.semantic.platform.promo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 活动规则版本 JSON 的领域模型。
 * 规则版本承载：分摊基础（合同/POS 证据）、叠加顺序与互斥、资格（SKU/门槛/件数）、退货重检。
 */
public class PromoRules {

    public Allocation allocation = new Allocation();
    public List<Rule> stacking = new ArrayList<>();
    public RefundPolicy refund = new RefundPolicy();

    public static class Allocation {
        /** amount | quantity | unspecified */
        public String basis = "unspecified";
        /** contract | pos | none */
        public String evidence = "none";
    }

    public static class RefundPolicy {
        public boolean recheckThreshold = true;
        public boolean recheckGift = true;
    }

    public static class Rule {
        public String ruleId;
        public int seq;
        /** threshold_off | percent_off | sku_coupon | buy_gift | points | vendor_subsidy */
        public String kind;
        public BigDecimal threshold;
        public List<Tier> tiers;
        /** null 表示全部商品 */
        public List<String> eligibleSkus;
        public List<String> skus;
        public BigDecimal perUnit;
        public BigDecimal rate;
        public Integer minQty;
        public BigDecimal minAmount;
        public String sku;
        public Integer perQty;
        public String giftSku;
        public Integer giftQty;
        public BigDecimal amount;
        public List<String> exclusiveWith = new ArrayList<>();
        public String note;
    }

    public static class Tier {
        public BigDecimal min;
        public BigDecimal off;
    }

    public Rule find(String ruleId) {
        if (ruleId == null) return null;
        for (Rule r : stacking) if (ruleId.equals(r.ruleId)) return r;
        return null;
    }

    /** 资格：SKU 是否属于该规则可享范围。null/空表示全部商品。 */
    public static boolean skuEligible(Rule r, String sku) {
        List<String> set = r.eligibleSkus != null && !r.eligibleSkus.isEmpty() ? r.eligibleSkus : r.skus;
        if (set == null || set.isEmpty()) return true;
        return set.contains(sku);
    }
}
