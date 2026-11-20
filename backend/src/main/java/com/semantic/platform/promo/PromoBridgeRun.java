package com.semantic.platform.promo;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 毛利桥运行：固定输入版本、留存输入快照，支持历史复演。 */
@Entity
@Table(name = "promo_bridge_run")
public class PromoBridgeRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false, unique = true)
    public Long runId;

    @Column(name = "metric_key", nullable = false)
    public String metricKey = "promo_bridge_margin";

    @Column(name = "metric_version_id")
    public Long metricVersionId;

    @Column(nullable = false)
    public String method;

    @Column(name = "tax_basis", nullable = false)
    public String taxBasis;

    @Column(name = "period_from")
    public LocalDate periodFrom;

    @Column(name = "period_to")
    public LocalDate periodTo;

    @Column(nullable = false)
    public String grain;

    @Column(nullable = false)
    public String status = "draft";

    @Column(name = "publication_version")
    public Integer publicationVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_versions_json", columnDefinition = "jsonb")
    public String inputVersionsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "jsonb")
    public String snapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_summary_json", columnDefinition = "jsonb")
    public String checkSummaryJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings_json", columnDefinition = "jsonb")
    public String warningsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "totals_json", columnDefinition = "jsonb")
    public String totalsJson;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;

    @Column(name = "published_by")
    public String publishedBy;

    public String note;
}
