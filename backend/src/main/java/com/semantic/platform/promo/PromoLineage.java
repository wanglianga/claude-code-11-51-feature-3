package com.semantic.platform.promo;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** 明细行血缘：参与记录、被拒记录及原因。 */
@Entity
@Table(name = "promo_lineage")
public class PromoLineage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "line_key", nullable = false)
    public String lineKey;

    @Column(name = "record_type", nullable = false)
    public String recordType;

    @Column(name = "record_id", nullable = false)
    public String recordId;

    public BigDecimal contribution;

    @Column(nullable = false)
    public Boolean included = true;

    public String reason;
}
