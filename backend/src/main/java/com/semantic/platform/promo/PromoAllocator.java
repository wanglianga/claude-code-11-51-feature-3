package com.semantic.platform.promo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单级折让分摊器（分币精度、确定尾差）。
 *
 * 两种合法口径：
 *  - amount   按符合优惠资格的商品金额比例分摊：金额守恒，高价商品承担较多折让。
 *  - quantity 按符合条件的件数均摊：贴合买 N 件减固定额，但不同价格商品单位毛利变化较大。
 *
 * 尾差规则：先把每行精确份额向下取整到分，再把剩余的分按“小数余数降序、行号升序”逐分补齐
 * （最大余数法）。保证 Σ分摊 = 原始订单级金额（到分），且同样输入必得同样结果。
 */
public final class PromoAllocator {

    private PromoAllocator() {}

    /** 一行参与分摊的资格信息。 */
    public record Eligible(String lineId, BigDecimal amount, int quantity) {}

    /** 一行分到的折让（到分）及其补分尾差（0 或 1 分）。 */
    public record Share(String lineId, BigDecimal amount, int remainderCents) {}

    public record Result(List<Share> shares, BigDecimal allocated, BigDecimal unallocated,
                         String reason) {}

    public static Result allocate(BigDecimal total, List<Eligible> eligible, String basis) {
        if (total == null || total.signum() <= 0) {
            return new Result(List.of(), BigDecimal.ZERO, total == null ? BigDecimal.ZERO : total, "非正金额无需分摊");
        }
        if (eligible == null || eligible.isEmpty()) {
            return new Result(List.of(), BigDecimal.ZERO, total, "无符合资格的商品行");
        }
        if (!"amount".equals(basis) && !"quantity".equals(basis)) {
            return new Result(List.of(), BigDecimal.ZERO, total, "分摊基础未指定或证据不足，列为未分摊");
        }
        BigDecimal weightTotal = BigDecimal.ZERO;
        for (Eligible e : eligible) weightTotal = weightTotal.add(weightOf(e, basis));
        if (weightTotal.signum() <= 0) {
            return new Result(List.of(), BigDecimal.ZERO, total, "资格权重合计为零，无法分摊");
        }

        long totalCents = total.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        Map<String, Long> centsByLine = new LinkedHashMap<>();
        Map<String, BigDecimal> remainderByLine = new LinkedHashMap<>();
        long assigned = 0;
        for (Eligible e : eligible) {
            BigDecimal exactCents = BigDecimal.valueOf(totalCents)
                    .multiply(weightOf(e, basis))
                    .divide(weightTotal, 10, RoundingMode.DOWN);
            long floor = exactCents.setScale(0, RoundingMode.FLOOR).longValueExact();
            centsByLine.put(e.lineId(), floor);
            remainderByLine.put(e.lineId(), exactCents.subtract(BigDecimal.valueOf(floor)));
            assigned += floor;
        }
        long leftover = totalCents - assigned;   // 0 <= leftover < 行数
        List<String> order = new ArrayList<>(centsByLine.keySet());
        order.sort(Comparator
                .comparing((String id) -> remainderByLine.get(id)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        Map<String, Integer> extraByLine = new LinkedHashMap<>();
        for (int i = 0; i < leftover; i++) {
            String id = order.get(i);
            centsByLine.put(id, centsByLine.get(id) + 1);
            extraByLine.merge(id, 1, Integer::sum);
        }

        List<Share> shares = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, Long> en : centsByLine.entrySet()) {
            BigDecimal amt = BigDecimal.valueOf(en.getValue()).movePointLeft(2);
            shares.add(new Share(en.getKey(), amt, extraByLine.getOrDefault(en.getKey(), 0)));
            sum = sum.add(amt);
        }
        BigDecimal unallocated = total.setScale(2, RoundingMode.HALF_UP).subtract(sum);
        return new Result(shares, sum, unallocated,
                unallocated.signum() == 0 ? "分摊完成（最大余数法清零尾差）" : "分摊尾差未清零");
    }

    /** 按权重把某明细分摊给一组资格行（用于退款后重算的逐行归属）。 */
    public static Map<String, BigDecimal> proportional(BigDecimal total, List<Eligible> eligible, String basis) {
        Result r = allocate(total, eligible, basis);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Share s : r.shares()) out.put(s.lineId(), s.amount());
        return out;
    }

    private static BigDecimal weightOf(Eligible e, String basis) {
        if ("quantity".equals(basis)) return BigDecimal.valueOf(Math.max(0, e.quantity()));
        return e.amount() == null ? BigDecimal.ZERO : e.amount();
    }
}
