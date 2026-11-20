package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 新运行候选：成本追溯调整或迟到补偿只生成新运行，历史发布不受影响。 */
@Entity
@Table(name = "promo_run_candidate")
public class PromoRunCandidate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "source_run_id")
    public Long sourceRunId;

    @Column(name = "trigger_type", nullable = false)
    public String triggerType;

    @Column(name = "trigger_ref")
    public String triggerRef;

    @Column(nullable = false)
    public String status = "pending";

    public String note;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();
}
