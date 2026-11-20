package com.semantic.platform.promo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 分摊器：两种合法口径、分币守恒、确定性尾差。 */
class PromoAllocatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void amountBasisConservesAndFavoursHighPrice() {
        var eligible = List.of(
                new PromoAllocator.Eligible("L1", bd("240.00"), 2),   // 120/件
                new PromoAllocator.Eligible("L2", bd("200.00"), 1),
                new PromoAllocator.Eligible("L3", bd("80.00"), 1));
        var r = PromoAllocator.allocate(bd("30.00"), eligible, "amount");
        assertEquals(0, r.unallocated().signum(), "金额守恒");
        assertAmount("30", r.allocated());
        Map<String, BigDecimal> m = PromoAllocator.proportional(bd("30.00"), eligible, "amount");
        // 240/520*30 = 13.846..., 200/520*30 = 11.538..., 80/520*30 = 4.615...
        assertAmount("13.85", m.get("L1"));
        assertAmount("11.54", m.get("L2"));
        assertAmount("4.61", m.get("L3"));
        assertAmount("30.00", m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void quantityBasisSplitsByUnits() {
        var eligible = List.of(
                new PromoAllocator.Eligible("L1", bd("240.00"), 2),
                new PromoAllocator.Eligible("L2", bd("200.00"), 1),
                new PromoAllocator.Eligible("L3", bd("80.00"), 1));
        Map<String, BigDecimal> m = PromoAllocator.proportional(bd("40.00"), eligible, "quantity");
        // 4 件均摊：10 每件 → L1=20, L2=10, L3=10
        assertAmount("20.00", m.get("L1"));
        assertAmount("10.00", m.get("L2"));
        assertAmount("10.00", m.get("L3"));
    }

    @Test
    void largestRemainderClearsCents() {
        // 3 行等额，10 元分到 3 行：3.33/3.33/3.34（按行号升序补最后一分）
        var eligible = List.of(
                new PromoAllocator.Eligible("A", bd("1"), 1),
                new PromoAllocator.Eligible("B", bd("1"), 1),
                new PromoAllocator.Eligible("C", bd("1"), 1));
        var r = PromoAllocator.allocate(bd("10.00"), eligible, "amount");
        assertEquals(0, r.unallocated().signum());
        BigDecimal sum = r.shares().stream().map(PromoAllocator.Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertAmount("10.00", sum);
        long extras = r.shares().stream().filter(s -> s.remainderCents() > 0).count();
        assertEquals(1, extras, "仅一行获得 1 分补足尾差");
        // 确定性：同样输入重复计算结果一致
        var r2 = PromoAllocator.allocate(bd("10.00"), eligible, "amount");
        assertEquals(r.shares().toString(), r2.shares().toString());
    }

    @Test
    void unspecifiedBasisIsUnallocated() {
        var eligible = List.of(new PromoAllocator.Eligible("L1", bd("100.00"), 1));
        var r = PromoAllocator.allocate(bd("20.00"), eligible, "unspecified");
        assertEquals(0, r.allocated().signum());
        assertAmount("20.00", r.unallocated());
        assertEquals(0, r.shares().size());
    }

    @Test
    void weightedPercentOffConservesToCent() {
        // 7 元分给 3,3,1 权重
        var eligible = List.of(
                new PromoAllocator.Eligible("L1", bd("3"), 1),
                new PromoAllocator.Eligible("L2", bd("3"), 1),
                new PromoAllocator.Eligible("L3", bd("1"), 1));
        var r = PromoAllocator.allocate(bd("7.00"), eligible, "amount");
        assertAmount("7.00", r.allocated());
        assertEquals(0, r.unallocated().signum());
        // 3/7*7=3; 3/7*7=3; 1/7*7=1
        Map<String, BigDecimal> m = PromoAllocator.proportional(bd("7.00"), eligible, "amount");
        assertAmount("3.00", m.get("L1"));
        assertAmount("3.00", m.get("L2"));
        assertAmount("1.00", m.get("L3"));
    }
}
