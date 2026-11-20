package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** 守恒校验结果：每层合计须回到订单/成本/补偿来源。 */
@Entity
@Table(name = "promo_check")
public class PromoCheck {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "check_code", nullable = false)
    public String checkCode;

    @Column(name = "scope_type", nullable = false)
    public String scopeType = "global";

    @Column(name = "scope_key")
    public String scopeKey;

    @Column(nullable = false)
    public String status;

    public BigDecimal expected;
    public BigDecimal actual;
    public BigDecimal diff;
    public String message;
}
