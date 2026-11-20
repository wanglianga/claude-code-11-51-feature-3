package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 商品成本版本：URL 成本回溯调整只生成新版本，旧版本可供历史运行复演。 */
@Entity
@Table(name = "promo_cost_version")
public class PromoCostVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true)
    public Integer version;

    @Column(nullable = false)
    public String status = "published";

    public String note;

    @Column(name = "source_note")
    public String sourceNote;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "published_at")
    public OffsetDateTime publishedAt;
}
