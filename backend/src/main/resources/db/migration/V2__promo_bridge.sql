-- ============================================================
-- 促销毛利桥（Promotion Gross-Margin Bridge）
-- 输入事实：价格生效、订单行与优惠执行、活动资格规则、原交易退货、
--          SKU 门店日成本、供应商补偿凭证
-- 所有事实均带来源版本；桥运行固定输入版本并留存快照以支持历史复演。
-- ============================================================

-- 订单/订单行补充税基声明（含税 / 不含税由事实声明）
ALTER TABLE order_fact ADD COLUMN tax_basis VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive';
ALTER TABLE order_fact ADD COLUMN tax_rate NUMERIC(6,4) NOT NULL DEFAULT 0.1300;
ALTER TABLE order_line ADD COLUMN line_type VARCHAR(16) NOT NULL DEFAULT 'sale'; -- sale | gift
ALTER TABLE order_line ADD COLUMN tax_basis VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive';
ALTER TABLE order_line ADD COLUMN tax_rate NUMERIC(6,4) NOT NULL DEFAULT 0.1300;

-- ---------- 活动主数据 ----------
CREATE TABLE promo_campaign (
    id                    BIGSERIAL PRIMARY KEY,
    campaign_code         VARCHAR(64) NOT NULL UNIQUE,
    name                  VARCHAR(128) NOT NULL,
    contract_ref          VARCHAR(64),
    tax_basis             VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive',
    supplier_comp_treatment VARCHAR(16) DEFAULT 'rebate',   -- rebate | cost_reduction
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 活动资格 / 叠加 / 分摊规则（版本化） ----------
CREATE TABLE promo_rule_version (
    id              BIGSERIAL PRIMARY KEY,
    campaign_code   VARCHAR(64) NOT NULL,
    version         INTEGER NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',  -- draft | published | retired
    rules_json      JSONB NOT NULL,
    effective_from  DATE,
    effective_to    DATE,
    definition_hash VARCHAR(64),
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    UNIQUE (campaign_code, version)
);

-- ---------- 价格生效事实 ----------
CREATE TABLE promo_price_fact (
    id              BIGSERIAL PRIMARY KEY,
    price_fact_id   VARCHAR(64) NOT NULL UNIQUE,
    sku             VARCHAR(32) NOT NULL,
    store_id        VARCHAR(32) NOT NULL,
    valid_from      DATE NOT NULL,
    valid_to        DATE NOT NULL,
    list_price      NUMERIC(16,2) NOT NULL,
    currency        VARCHAR(8) NOT NULL DEFAULT 'CNY',
    tax_basis       VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive',
    tax_rate        NUMERIC(6,4) NOT NULL DEFAULT 0.1300,
    version         INTEGER NOT NULL DEFAULT 1,
    created_batch   BIGINT REFERENCES source_batch(id)
);

-- ---------- 订单优惠执行明细（保留原始订单级金额） ----------
CREATE TABLE promo_discount_execution (
    id                BIGSERIAL PRIMARY KEY,
    execution_id      VARCHAR(64) NOT NULL UNIQUE,
    order_id          VARCHAR(64) NOT NULL,
    campaign_code     VARCHAR(64) NOT NULL,
    rule_version_id   BIGINT REFERENCES promo_rule_version(id),
    execution_type    VARCHAR(32) NOT NULL,   -- amount_off | percent_off | sku_coupon | buy_gift | points | vendor_subsidy
    scope             VARCHAR(16) NOT NULL,   -- order | line | gift
    order_line_id     VARCHAR(64),
    gift_line_id      VARCHAR(64),
    amount            NUMERIC(16,2) NOT NULL DEFAULT 0,
    currency          VARCHAR(8) NOT NULL DEFAULT 'CNY',
    tax_basis         VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive',
    allocation_basis  VARCHAR(16),            -- amount | quantity | unspecified（执行明细优先，其次规则）
    basis_evidence    VARCHAR(16),            -- contract | pos | none
    rule_id           VARCHAR(64),            -- 命中的规则（用于资格/门槛重检）
    applied_seq       INTEGER NOT NULL,
    note              TEXT,
    created_batch     BIGINT REFERENCES source_batch(id)
);
CREATE INDEX idx_promo_exec_order ON promo_discount_execution(order_id);

-- ---------- 原交易关联的退货行 ----------
CREATE TABLE promo_refund_line (
    id                       BIGSERIAL PRIMARY KEY,
    refund_line_id           VARCHAR(64) NOT NULL UNIQUE,
    refund_id                VARCHAR(64) NOT NULL,
    order_id                 VARCHAR(64) NOT NULL,
    order_line_id            VARCHAR(64) NOT NULL,
    sku                      VARCHAR(32),
    quantity                 INTEGER NOT NULL,
    amount                   NUMERIC(16,2) NOT NULL,   -- 退货行标价（用于分摊现金）
    cash_amount              NUMERIC(16,2),            -- POS 实退现金证据（可空，空则仅按重算）
    business_date            DATE NOT NULL,
    original_rule_version_id BIGINT,                   -- 退款按原优惠版本重算
    note                     VARCHAR(256),
    created_batch            BIGINT REFERENCES source_batch(id)
);
CREATE INDEX idx_promo_refund_line_order ON promo_refund_line(order_id);

-- ---------- SKU 门店日成本（版本化） ----------
CREATE TABLE promo_cost_version (
    id            BIGSERIAL PRIMARY KEY,
    version       INTEGER NOT NULL UNIQUE,
    status        VARCHAR(16) NOT NULL DEFAULT 'published', -- published | superseded
    note          VARCHAR(256),
    source_note   VARCHAR(256),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE TABLE promo_sku_cost (
    id              BIGSERIAL PRIMARY KEY,
    cost_version_id BIGINT NOT NULL REFERENCES promo_cost_version(id),
    sku             VARCHAR(32) NOT NULL,
    store_id        VARCHAR(32) NOT NULL,
    business_date   DATE NOT NULL,
    unit_cost       NUMERIC(16,2) NOT NULL,
    currency        VARCHAR(8) NOT NULL DEFAULT 'CNY',
    tax_basis       VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive',
    tax_rate        NUMERIC(6,4) NOT NULL DEFAULT 0.1300,
    created_batch   BIGINT REFERENCES source_batch(id),
    UNIQUE (cost_version_id, sku, store_id, business_date)
);
CREATE INDEX idx_promo_cost_lookup ON promo_sku_cost(cost_version_id, sku, store_id, business_date);

-- ---------- 供应商补偿凭证（独立事实） ----------
CREATE TABLE promo_supplier_compensation (
    id                    BIGSERIAL PRIMARY KEY,
    comp_id               VARCHAR(64) NOT NULL UNIQUE,
    campaign_code         VARCHAR(64) NOT NULL,
    sku                   VARCHAR(32),
    store_id              VARCHAR(32),
    business_date         DATE NOT NULL,
    period_start          DATE,
    period_end            DATE,
    amount                NUMERIC(16,2) NOT NULL,
    currency              VARCHAR(8) NOT NULL DEFAULT 'CNY',
    tax_basis             VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive',
    tax_rate              NUMERIC(6,4) NOT NULL DEFAULT 0.1300,
    contract_ref          VARCHAR(64),
    margin_treatment      VARCHAR(16) NOT NULL DEFAULT 'rebate', -- rebate | cost_reduction
    evidence_ref          VARCHAR(128),
    settles_execution_id  VARCHAR(64),
    status                VARCHAR(16) NOT NULL DEFAULT 'confirmed',
    received_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_batch         BIGINT REFERENCES source_batch(id)
);

-- ---------- 毛利桥运行（一次发布固定输入版本与快照） ----------
CREATE TABLE promo_bridge_run (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT NOT NULL UNIQUE REFERENCES run(id),
    metric_key          VARCHAR(64) NOT NULL DEFAULT 'promo_bridge_margin',
    metric_version_id   BIGINT,
    method              VARCHAR(16) NOT NULL,          -- amount | quantity
    tax_basis           VARCHAR(16) NOT NULL,          -- tax_inclusive | tax_exclusive
    period_from         DATE,
    period_to           DATE,
    grain               VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'draft', -- draft | published | superseded | failed
    publication_version INTEGER,
    input_versions_json JSONB,
    snapshot_json       JSONB,
    check_summary_json  JSONB,
    warnings_json       JSONB,
    totals_json         JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    published_by        VARCHAR(64),
    note                VARCHAR(512)
);

CREATE TABLE promo_bridge_line (
    id                       BIGSERIAL PRIMARY KEY,
    run_id                   BIGINT NOT NULL REFERENCES promo_bridge_run(id),
    line_key                 VARCHAR(300) NOT NULL,
    period_key               VARCHAR(32) NOT NULL,
    campaign_code            VARCHAR(64),
    sku                      VARCHAR(32),
    store_id                 VARCHAR(32),
    line_kind                VARCHAR(16) NOT NULL,    -- item | unallocated | compensation
    list_sales               NUMERIC(20,4) NOT NULL DEFAULT 0,
    customer_concession      NUMERIC(20,4) NOT NULL DEFAULT 0,
    refund_reversal          NUMERIC(20,4) NOT NULL DEFAULT 0,
    reallocation_adjustment  NUMERIC(20,4) NOT NULL DEFAULT 0,
    net_sales                NUMERIC(20,4) NOT NULL DEFAULT 0,
    cost_of_sales            NUMERIC(20,4) NOT NULL DEFAULT 0,
    gross_margin             NUMERIC(20,4) NOT NULL DEFAULT 0,
    supplier_compensation    NUMERIC(20,4) NOT NULL DEFAULT 0,
    net_margin_contribution  NUMERIC(20,4) NOT NULL DEFAULT 0,
    quantity_sold            NUMERIC(20,4) NOT NULL DEFAULT 0,
    quantity_returned        NUMERIC(20,4) NOT NULL DEFAULT 0,
    amount_basis             VARCHAR(16),
    currency                 VARCHAR(8) NOT NULL DEFAULT 'CNY',
    tax_basis                VARCHAR(16) NOT NULL DEFAULT 'tax_inclusive',
    flags_json               JSONB,
    detail_json              JSONB,
    UNIQUE (run_id, line_key)
);
CREATE INDEX idx_promo_bridge_line_run ON promo_bridge_line(run_id);

CREATE TABLE promo_allocation (
    id                 BIGSERIAL PRIMARY KEY,
    run_id             BIGINT NOT NULL REFERENCES promo_bridge_run(id),
    order_id           VARCHAR(64) NOT NULL,
    execution_id       VARCHAR(64) NOT NULL,
    campaign_code      VARCHAR(64),
    order_line_id      VARCHAR(64),
    rule_version_id    BIGINT,
    applied_seq        INTEGER,
    basis              VARCHAR(16),
    original_amount    NUMERIC(16,2) NOT NULL DEFAULT 0,
    allocated_amount   NUMERIC(16,2) NOT NULL DEFAULT 0,
    unallocated_amount NUMERIC(16,2) NOT NULL DEFAULT 0,
    eligible_amount    NUMERIC(16,2),
    eligible_quantity  INTEGER,
    remainder_cents    INTEGER NOT NULL DEFAULT 0,
    phase              VARCHAR(16) NOT NULL DEFAULT 'original', -- original | refund_recalc
    status             VARCHAR(32) NOT NULL,                  -- allocated | unallocated | threshold_lost | conflict | invalid
    reason             VARCHAR(256)
);
CREATE INDEX idx_promo_alloc_run ON promo_allocation(run_id);

CREATE TABLE promo_refund_adjustment (
    id                    BIGSERIAL PRIMARY KEY,
    run_id                BIGINT NOT NULL REFERENCES promo_bridge_run(id),
    order_id              VARCHAR(64) NOT NULL,
    refund_line_id        VARCHAR(64),
    order_line_id         VARCHAR(64),
    original_rule_version BIGINT,
    method                VARCHAR(16),
    return_business_date  DATE,
    returned_list         NUMERIC(16,2) NOT NULL DEFAULT 0,
    original_paid         NUMERIC(16,2) NOT NULL DEFAULT 0,
    remaining_paid        NUMERIC(16,2) NOT NULL DEFAULT 0,
    refund_cash           NUMERIC(16,2) NOT NULL DEFAULT 0,
    discount_reversal     NUMERIC(16,2) NOT NULL DEFAULT 0,
    reallocation_amount   NUMERIC(16,2) NOT NULL DEFAULT 0,
    threshold_lost        BOOLEAN NOT NULL DEFAULT FALSE,
    gift_invalidated      BOOLEAN NOT NULL DEFAULT FALSE,
    note                  VARCHAR(512),
    detail_json           JSONB
);
CREATE INDEX idx_promo_refund_adj_run ON promo_refund_adjustment(run_id);

CREATE TABLE promo_check (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT NOT NULL REFERENCES promo_bridge_run(id),
    check_code  VARCHAR(64) NOT NULL,
    scope_type  VARCHAR(16) NOT NULL DEFAULT 'global', -- global | order | execution | line | campaign
    scope_key   VARCHAR(128),
    status      VARCHAR(16) NOT NULL,                  -- pass | fail | warn
    expected    NUMERIC(20,4),
    actual      NUMERIC(20,4),
    diff        NUMERIC(20,4),
    message     VARCHAR(512)
);
CREATE INDEX idx_promo_check_run ON promo_check(run_id);

CREATE TABLE promo_input_version (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT NOT NULL REFERENCES promo_bridge_run(id),
    kind          VARCHAR(32) NOT NULL,  -- rule | cost | discount_compilation | refund_recalc | publication
    version_label VARCHAR(64) NOT NULL,
    input_hash    VARCHAR(64),
    output_hash   VARCHAR(64),
    details_json  JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE promo_run_candidate (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT NOT NULL REFERENCES promo_bridge_run(id),
    source_run_id BIGINT,
    trigger_type  VARCHAR(32) NOT NULL,  -- late_compensation | cost_revision | late_refund
    trigger_ref   VARCHAR(64),
    status        VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending | adopted | rejected
    note          VARCHAR(512),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE promo_lineage (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT NOT NULL REFERENCES promo_bridge_run(id),
    line_key      VARCHAR(300) NOT NULL,
    record_type   VARCHAR(32) NOT NULL,  -- order_line | execution | refund_line | refund | cost | compensation | price_fact
    record_id     VARCHAR(64) NOT NULL,
    contribution  NUMERIC(20,4),
    included      BOOLEAN NOT NULL DEFAULT TRUE,
    reason        VARCHAR(256)
);
CREATE INDEX idx_promo_lineage_line ON promo_lineage(run_id, line_key);

-- 血缘记录类型扩展（促销桥会写入 promo_bridge_line / compensation 等类型）
ALTER TABLE run_cell_lineage ALTER COLUMN record_type TYPE VARCHAR(40);
