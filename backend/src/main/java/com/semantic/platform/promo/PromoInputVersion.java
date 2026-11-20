package com.semantic.platform.promo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

/**
 * 输入版本登记：活动规则、商品成本、订单优惠编译、退货重算、毛利发布各自留痕。
 * kind = rule | cost | discount_compilation | refund_recalc | publication
 */
@Entity
@Table(name = "promo_input_version")
public class PromoInputVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(nullable = false)
    public String kind;

    @Column(name = "version_label", nullable = false)
    public String versionLabel;

    @Column(name = "input_hash")
    public String inputHash;

    @Column(name = "output_hash")
    public String outputHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", columnDefinition = "jsonb")
    public String detailsJson;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
