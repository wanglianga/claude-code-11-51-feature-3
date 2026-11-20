# 经营指标语义层与自助分析平台

面向连锁零售集团的经营指标语义层与自助分析平台。解决总部、区域、门店三方对"本月销售额""同店增长"口径不一致的问题：通过集中语义定义、版本化运行、血缘下钻，让经营会上同名指标数字一致且可解释。

## 核心能力

- **集中语义定义**：指标用受控 DSL（表达式 AST）定义，表达来源字段、过滤、分组、聚合、四则组合、其他指标引用，**不接受任意 SQL**，也不只存一段口径说明
- **定义校验**：先识别引用环路，再校验金额、数量、比例、时间粒度；不同币种未经显式换算不能相加，门店日指标不能与门店月指标逐行相乘
- **运行引擎**：固定引用定义版本；标记数据截止点与覆盖范围，**不把未到数据当成零销售**；比率上卷时重新汇总分子分母
- **同店队列**：按两个比较期共同满足的营业状态与覆盖规则确定，跨期新开、闭店、缺资料门店各自说明，不把缺失期销售额填零后加入增长率
- **迟到退款**：影响计算生成新运行候选，已发布报表继续引用原运行，经营负责人决定是否发布更正
- **血缘下钻**：每个结果格可展开参与记录、过滤排除及最终算式

## 技术栈

- **后端**：Spring Boot 3.5 + Java 21 + Spring Data JPA + Flyway
- **前端**：Vue 3 + Vite + Element Plus
- **数据库**：PostgreSQL 16
- **部署**：Docker Compose 一键启动

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
docker-compose up --build
```

启动后访问：
- 前端：http://localhost
- 后端 API：http://localhost:8080/api

### 方式二：本地开发

**前置要求**：Java 21+、Node 22+、PostgreSQL 16（库/用户/密码均为 `ccdev`）

```bash
# 1. 启动后端（自动建表 + 播种样例数据）
cd backend
mvn spring-boot:run

# 2. 启动前端（另开终端）
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173（前端开发服务器，代理 API 到 8080）

> 也可以只启动后端：后端会托管前端构建产物（`backend/src/main/resources/static`），访问 http://localhost:8080
>
> 更新前端后同步到后端托管目录：
> ```bash
> cd frontend && npm run build && rm -rf ../backend/src/main/resources/static/* && cp -r dist/* ../backend/src/main/resources/static/
> ```

## 测试账号

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 指标管理员 |
| analyst | analyst123 | 分析员 |
| manager | manager123 | 经营负责人 |

## 验证方式

### 一键验证脚本

```bash
# 确保后端已启动（localhost:8080）
bash scripts/verify.sh          # 既有指标语义层（10 项）
bash scripts/verify-promo.sh    # 促销毛利桥（17 项）
```

促销毛利桥覆盖：跨档满减、多券叠加、买赠退回后门槛失效、整单优惠部分退货、供应商补偿晚到、成本修订，
以及顾客实付/分摊/退款冲回/补偿/毛利桥逐项守恒与两种合法分摊对商品毛利的并列对照（不输出因果增量）。

### 关键场景手动验证

#### 1. 跨营业日订单
订单 O002 于 2026-01-10 01:15 下单（S001 营业到次日 02:00），计入 1/9 营业日。
```bash
# 运行 net_sales 2026-01-09（日粒度），S001 净销售额应为 800（O001 500 + O002 300）
curl -X POST http://localhost:8080/api/runs -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"metricKey":"net_sales","metricVersionId":3,"from":"2026-01-09","to":"2026-01-09","grain":"day"}'
```

#### 2. 缺批次数据（不把未到数据当成零销售）
S003 门店 2/1-2/7 订单批次仍在补数（backfilling）。
```bash
# 运行 net_sales 2月（月粒度），S003 单元格状态应为 partial（部分覆盖）
curl -X POST http://localhost:8080/api/runs -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"metricKey":"net_sales","metricVersionId":3,"from":"2026-02-01","to":"2026-02-28","grain":"month"}'
```

#### 3. 迟到退款
```bash
# 回放迟到退款（O001 的退款 150 元，1/10 营业日）
curl -X POST http://localhost:8080/api/replay/late-refund -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"refundId":"R003","orderId":"O001","storeId":"S001","businessDate":"2026-01-10","amount":"150.00"}'
# 影响计算生成新运行候选；已发布报表继续引用原运行，经营负责人在报表中心发布更正
```

#### 4. 币种校验
```bash
# CNY 未过滤 + USD 直接相加，应报错"不同币种不能直接相加"
curl -X POST http://localhost:8080/api/metrics/validate -H "X-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"metricKey":"t","definition":"{\"op\":\"arith\",\"operator\":\"add\",\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]},\"right\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"filter\":[{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"USD\"}],\"by\":[\"store_id\"]}}"}'
```

#### 5. 引用环路（并发发布）
创建两个相互引用的指标草稿，先发布一个成功，再发布另一个时因最终组合成环被拒绝（409）。


## 促销毛利桥（Promotion Gross-Margin Bridge）

在既有指标语义层之上新增的促销毛利桥数据产品。解决"简单把'用券订单额'当增量销售，把相关销售误当因果提升"的问题：
按 **活动 × 商品 × 门店 × 期间** 列出标价销售、顾客折让、退货冲回、厂商补偿与净毛利贡献，各层合计均可回到订单、成本与补偿来源，并**明确不输出未经对照设计验证的活动因果增量**。

### 桥的语义（声明一致的口径）

```
标价销售 − 顾客折让 − 退货冲回 + 退款重检的重分配调整 = 净销售额
净销售额 − 对应销售成本                                 = 商品毛利
商品毛利 + 独立列示的厂商补偿                           = 净毛利贡献
```

- **净销售额** 按声明一致的含税/不含税口径，由实际销售与退货冲回计算（税基换算显式、恒等校验）。
- **商品毛利** = 净销售额 − 对应销售成本（SKU×门店×日成本，版本化）。
- **厂商补偿** 独立列示，按合同处理方式（`rebate`/`cost_reduction`）计入毛利分析，**不混入销售收入、不重复抵减折让**。

### 订单级折让分摊（两条合法口径，均先遵守活动资格与叠加顺序）

| 口径 | 做法 | 特征 |
|------|------|------|
| `amount` 按金额比例 | 按符合优惠资格的商品金额比例分摊 | 金额守恒，高价商品承担较多折让；适合按金额触发的满减 |
| `quantity` 按件数均摊 | 按符合条件的件数均摊 | 贴合买 N 件减固定额，但不同价格商品单位毛利变化较大 |

- 两条口径都采用确定的**分币尾差规则**（最大余数法：先向下取整到分，剩余分按"小数余数降序、行号升序"逐分补齐），并**保留原始订单级金额**（`已分摊 + 未分摊/冲突 = 订单级优惠执行原始金额`）。
- 若活动合同或 POS 证据不足以判断分摊基础，**整笔列为"未分摊"**（`lineKind=unallocated`），不自行选择更有利的算法，也不把选择写成测得的增量效果。
- 违反叠加顺序/互斥规则的执行明细记为 `conflict` 并同样进入未分摊（POS 已实际执行，顾客确实获得）。

### 部分退货重算

退货按**原订单行、原优惠版本与剩余商品**重新检查买赠/满额资格，**不按退款金额同比例冲回整单折让**：

- 用原优惠版本对剩余篮子重跑活动规则，得到重算后的剩余折让；
- 退款现金 = 原实付 − 剩余应付（若无 POS 实退证据按此重算；有 POS 证据则以实退现金为准，差额计入重分配调整并保持三层恒等）；
- 门槛失效（跨档降级）/买赠件数不足会触发重算，退款现金与折让冲回（memo）分别列示。

### 版本与复演

活动规则、商品成本、订单优惠编译、退货重算、毛利发布**各自保存来源版本**（`promo_input_version` 留痕哈希）。
成本追溯调整或迟到补偿**只生成新运行候选**，历史发布仍按其留存快照按旧输入复演（`POST /api/promo/runs/{id}/replay`）。

### 语义层集成

桥以受控 DSL 节点 `{"op":"promo_bridge","method":"amount","taxBasis":"tax_inclusive"}` 注册为指标 `promo_bridge_margin`，通过既有 `/api/runs` 运行，输出 `活动×商品×门店×line_kind` 粒度的净毛利贡献单元格（受控字段，不接受任意 SQL）。

### REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/promo/runs` | 生成桥运行（`from`/`to`/`method`/`taxBasis`/`grain`/`costVersionId`） |
| GET | `/api/promo/runs` / `/{id}` | 运行列表 / 详情（含 `lines`/`allocations`/`refundAdjustments`/`checks`/`warnings`/`inputVersionLog`） |
| GET | `/api/promo/runs/{id}/compare` | 两种合法分摊口径对商品毛利的并列对照（不输出因果增量） |
| POST | `/api/promo/runs/{id}/publish` | 发布为 vN（守恒校验有失败则拒绝） |
| POST | `/api/promo/runs/{id}/replay` | 按留存快照复演 |
| POST | `/api/promo/compensations` | 回放迟到供应商补偿 → 新运行候选 |
| POST | `/api/promo/cost-revisions` | 成本追溯调整（系数）→ 新版本 + 新运行候选 |
| GET | `/api/promo/candidates?status=pending` | 新运行候选 |
| POST | `/api/promo/candidates/{id}/adopt` / `reject` | 采纳/拒绝候选 |
| GET | `/api/promo/campaigns` / `cost-versions` / `sources` | 活动规则版本 / 成本版本 / 原始事实 |
| GET | `/api/promo/runs/{id}/lineage?lineKey=...` | 明细行血缘下钻 |

### 样例促销场景（2026-05，门店 S101/S102）

| 活动 | 内容 | 关键订单 |
|------|------|----------|
| C1 跨档满减 | ¥300 减 30 / ¥600 减 80（订单级，按金额分摊，合同+POS 证据） | P001/P002/P003/P005 |
| C2 单品券 | SKU-X 每件减 15，买 2 件起（按件数均摊） | P002/P003/P005 |
| C3 买赠 | 买 X 满 3 件赠 G 1 件 | P002/P003/P005 |
| C4 积分抵扣 | 订单级 20，**无合同/POS 分摊依据 → 未分摊** | P001/P004 |
| C5 厂商补贴 | 独立执行明细 + 补偿凭证（含迟到补偿 SC-VERIFY） | P005 + SC001 |

退货样例：P002 退货后买赠/门槛失效、P003 整单优惠部分退货门槛仍有效、P004 未达门槛、P005 大额退货跨档降级。

## 指标 DSL 说明

指标定义是 JSON 表达式树（AST），节点类型：

| 节点 | 说明 | 示例 |
|------|------|------|
| `field` | 取源字段 | `{"op":"field","source":"orders","field":"amount"}` |
| `const` | 常量 | `{"op":"const","value":0.05}` |
| `agg` | 聚合 | `{"op":"agg","measure":"sum","expr":{...},"filter":[...],"by":["store_id"]}` |
| `arith` | 四则运算 | `{"op":"arith","operator":"sub","left":{...},"right":{...}}` |
| `convert` | 币种换算 | `{"op":"convert","expr":{...},"fromCurrency":"USD","toCurrency":"CNY","rate":7.1}` |
| `ref` | 引用其他指标 | `{"op":"ref","metric":"gross_sales"}` |
| `cohort` | 同店增长 | `{"op":"cohort","base":{...},"compare":"prev_period","cohortKey":"store_id"}` |

**示例：净销售额**
```json
{
  "op": "arith", "operator": "sub",
  "left": {"op": "ref", "metric": "gross_sales"},
  "right": {"op": "ref", "metric": "refund_total"}
}
```

**校验规则**：
- 引用环路检测（DFS 三色标记）
- 金额/数量/比例类型检查
- 不同币种未经 `convert` 换算不能相加
- 门店日指标不能与门店月指标逐行相乘
- 金额与金额不能相乘（量纲错误）

## 样例数据

预置 5 家门店（S001-S005）、2026 年 1-3 月订单/退款/门店状态/节假日数据，覆盖：
- 跨营业日订单（O002，1/10 01:15 计入 1/9）
- 多商品订单（O003，3 个订单行）
- 多次部分退款（O005 → R001、R002）
- 临时闭店（S005，2/15-2/21）
- 跨期新店（S004，2/10 开业）
- 缺批次数据（S003，2/1-2/7 补数中）
- 外币订单（O004，USD，用于币种校验演示）

## 项目结构

```
├── backend/                 # Spring Boot 后端
│   ├── src/main/java/com/semantic/platform/
│   │   ├── dsl/            # 指标 DSL（表达式 AST、校验器、源注册表）
│   │   ├── engine/         # 运行引擎（求值器、覆盖、同店队列）
│   │   ├── metric/         # 指标服务（CRUD、发布、影响）
│   │   ├── run/            # 运行服务（执行、血缘、回放）
│   │   ├── report/         # 报表服务（发布、更正）
│   │   ├── approval/       # 审批服务
│   │   ├── promo/          # 促销毛利桥（引擎/分摊/快照/服务/播种）
│   │   ├── source/         # 源数据与播种
│   │   └── api/            # REST 控制器（含 PromoController）
│   └── src/main/resources/
│       ├── db/migration/   # Flyway 建表脚本
│       └── static/         # 前端构建产物（后端托管）
├── frontend/               # Vue 3 前端
│   └── src/views/          # 指标目录/编辑器/运行/报表/审批/数据源/影响/促销毛利桥
├── scripts/verify.sh       # 既有指标语义层端到端验证
├── scripts/verify-promo.sh # 促销毛利桥端到端验收（17 项）
└── docker-compose.yml      # 一键部署
```

## 验证结论

本次交付实际执行过的验证：
- ✅ 后端编译通过（Spring Boot 3.5.6 + Java 21）
- ✅ 前端构建通过（Vue 3 + Vite）
- ✅ 端到端验证脚本 8/8 通过（登录、币种校验、粒度校验、缺批次覆盖、跨营业日订单、迟到退款、同店队列）
- ✅ 引用环路并发发布被拦截（cyc_a v2 发布成功，cyc_b v2 因组合成环返回 409）
- ✅ 迟到退款完整闭环（初始 1700 → 影响计算生成候选 → 报表仍引用原运行 → 发布更正后 1550）
- ✅ 促销毛利桥端到端验收 17/17 通过（`scripts/verify-promo.sh`）：
  - 9 项守恒校验（标价回订单、顾客实付守恒、折让守恒、净销售/毛利/贡献恒等、成本回来源、补偿回来源、退货现金上限）全部通过；
  - 促销桥样例（2026-05）：标价 2800、顾客折让 410、退货冲回 835、净销售 1555、销售成本 1084、商品毛利 471、厂商补偿 50、净毛利贡献 521；
  - 存在 2 笔证据不足的订单级折让列为未分摊；按金额与按件数两种合法口径并存，并列对照商品毛利差异；
  - 买赠退回后门槛失效、整单优惠部分退货重检均生效；历史发布复演一致（毛利 471）；
  - 迟到补偿 SC-VERIFY、成本修订 +10% 各自生成新运行候选，历史发布仍按旧成本复演（毛利 471）。
- ✅ 促销毛利桥单元测试 13 项 + 既有 13 项，共 26 项 `mvn test` 全部通过。
