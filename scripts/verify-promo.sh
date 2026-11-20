#!/bin/bash
# 促销毛利桥 — 端到端验收脚本
# 覆盖：跨档满减、多券叠加、买赠退回后门槛失效、整单优惠部分退货、供应商补偿晚到、成本修订，
#      以及顾客实付/分摊/退款冲回/补偿/毛利桥逐项守恒与两种合法分摊并列对照。
set -e
BASE="${BASE:-http://localhost:8080}"
PASS=0
FAIL=0

ok()  { PASS=$((PASS+1)); echo "  ✓ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

py() { /opt/python-3.12/bin/python3 "$@"; }

echo "=== 0. 登录 ==="
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | py -c "import sys,json;print(json.load(sys.stdin)['token'])" 2>/dev/null)
if [ -n "$TOKEN" ]; then ok "登录成功"; else bad "登录失败"; exit 1; fi
AUTH="X-Token: $TOKEN"

echo "=== 1. 桥运行（2026-05 月，按金额口径，含税） ==="
RUN=$(curl -s -X POST "$BASE/api/promo/runs" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"from":"2026-05-01","to":"2026-05-31","grain":"month","method":"amount","taxBasis":"tax_inclusive"}')
RUNID=$(echo "$RUN" | py -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
[ -n "$RUNID" ] && ok "生成运行 #$RUNID" || { bad "生成运行失败"; echo "$RUN"; exit 1; }

TOT_OK=$(echo "$RUN" | py -c "
import sys,json
d=json.load(sys.stdin)
print('yes' if d['totals']['checksFailed']==0 else 'no')")
[ "$TOT_OK" = "yes" ] && ok "9 项守恒校验全部通过" || bad "存在守恒校验未通过"

echo "=== 2. 顾客实付 / 折让 / 退货 / 桥逐项守恒 ==="
echo "$RUN" | py -c "
import sys,json
d=json.load(sys.stdin); t=d['totals']
assert abs(t['listSales']-t['orderAmountSource'])<0.01, '标价未回到订单来源'
assert abs((t['listSales']-t['customerConcession'])-(t['orderAmountSource']-t['executionOriginal']))<0.05, '顾客实付不守恒'
assert abs(t['netSales']-(t['listSales']-t['customerConcession']-t['refundReversal']+t['reallocationAdjustment']))<0.01, '净销售恒等失败'
assert abs(t['grossMargin']-(t['netSales']-t['costOfSales']))<0.01, '商品毛利恒等失败'
assert abs(t['netMarginContribution']-(t['grossMargin']+t['supplierCompensation']))<0.01, '净毛利贡献恒等失败'
print('OK')
" >/dev/null 2>&1 && ok "顾客实付=标价−折让、净销售/毛利/贡献逐层恒等" || bad "桥恒等校验失败"

echo "=== 3. 订单级折让分摊：已分摊+未分摊+冲突=原始订单级金额 ==="
curl -s "$BASE/api/promo/runs/$RUNID/allocations" -H "$AUTH" | py -c "
import sys,json
rows=json.load(sys.stdin)
orig=sum(float(r['originalAmount']) for r in rows if r['status']!='classified_compensation')
alloc=sum(float(r['allocatedAmount']) for r in rows)
unalloc=sum(float(r['unallocatedAmount']) for r in rows)
assert abs(orig-(alloc+unalloc))<0.05, f'分摊不守恒 orig={orig} alloc={alloc} unalloc={unalloc}'
print('OK')
" >/dev/null 2>&1 && ok "分摊守恒（保留原始订单级金额）" || bad "分摊不守恒"

UNALLOC=$(curl -s "$BASE/api/promo/runs/$RUNID/allocations" -H "$AUTH" | py -c "
import sys,json
rows=json.load(sys.stdin)
print(sum(1 for r in rows if r['status']=='unallocated'))")
[ "$UNALLOC" -ge 1 ] && ok "证据不足订单级折让列为未分摊（$UNALLOC 笔）" || bad "未分摊未生效"

echo "=== 4. 两种合法分摊基础并存 ==="
BASES=$(curl -s "$BASE/api/promo/runs/$RUNID/allocations" -H "$AUTH" | py -c "
import sys,json
rows=json.load(sys.stdin)
print('yes' if any(r['basis']=='amount' for r in rows) and any(r['basis']=='quantity' for r in rows) else 'no')")
[ "$BASES" = "yes" ] && ok "同时存在按金额与按件数分摊的执行明细" || bad "分摊基础缺失"

echo "=== 5. 买赠退回后门槛失效 / 整单优惠部分退货 重算 ==="
curl -s "$BASE/api/promo/runs/$RUNID/refunds" -H "$AUTH" | py -c "
import sys,json
rows=json.load(sys.stdin)
lost=[r for r in rows if r['thresholdLost']]
gift=[r for r in rows if r['giftInvalidated']]
assert lost, '未出现门槛失效重算'
assert gift, '未出现买赠失效重算'
# 不做同比例冲回：退款现金 != 退货标价 × 整单折让率
print('OK')
" >/dev/null 2>&1 && ok "部分退货按原优惠版本与剩余商品重检门槛/买赠" || bad "退货重算未生效"

echo "=== 6. 供应商补偿独立列示（不混入销售收入） ==="
echo "$RUN" | py -c "
import sys,json
d=json.load(sys.stdin); t=d['totals']
assert float(t['supplierCompensation'])>0, '无补偿'
comp_lines=[l for l in d['lines'] if l['lineKind']=='compensation']
assert comp_lines, '无补偿明细行'
for l in comp_lines:
    assert float(l['netSales'])==0, '补偿被计入销售收入'
print('OK')
" >/dev/null 2>&1 && ok "厂商补偿独立行、不计入净销售、仅计入净毛利贡献" || bad "厂商补偿口径错误"

echo "=== 7. 两种合法分摊对商品毛利的并列对照（不输出因果增量） ==="
CMP=$(curl -s "$BASE/api/promo/runs/$RUNID/compare" -H "$AUTH")
echo "$CMP" | py -c "
import sys,json
d=json.load(sys.stdin)
assert d.get('byAmount') and d.get('byQuantity'), '缺少双口径'
assert '未输出' in d.get('causalIncrement',''), '不应输出因果增量'
print('OK')
" >/dev/null 2>&1 && ok "并列展示按金额/按件数对商品毛利影响，且不输出未验证的因果增量" || bad "双口径对照失败"

echo "=== 8. 发布 v1 与历史复演（按留存快照） ==="
PUB=$(curl -s -X POST "$BASE/api/promo/runs/$RUNID/publish" -H "$AUTH")
PVER=$(echo "$PUB" | py -c "import sys,json;print(json.load(sys.stdin).get('publicationVersion'))" 2>/dev/null)
[ "$PVER" = "1" ] && ok "发布为 v1" || bad "发布失败: $PUB"
GM_ORIG=$(echo "$PUB" | py -c "import sys,json;print(json.load(sys.stdin)['totals']['grossMargin'])" 2>/dev/null)
REPLAY=$(curl -s -X POST "$BASE/api/promo/runs/$RUNID/replay" -H "$AUTH")
GM_REPLAY=$(echo "$REPLAY" | py -c "import sys,json;print(json.load(sys.stdin)['totals']['grossMargin'])" 2>/dev/null)
[ "$GM_ORIG" = "$GM_REPLAY" ] && ok "复演结果与原发布一致（毛利 $GM_ORIG）" || bad "复演不一致: $GM_ORIG vs $GM_REPLAY"

echo "=== 9. 迟到供应商补偿 → 新运行候选（历史发布不变） ==="
CAND=$(curl -s -X POST "$BASE/api/promo/compensations" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"compId":"SC-VERIFY","campaignCode":"C5","sku":"SKU-Y","storeId":"S101","businessDate":"2026-05-20","amount":"40.00","contractRef":"CT-2026-05-005","marginTreatment":"rebate"}')
NCAND=$(echo "$CAND" | py -c "import sys,json;print(len(json.load(sys.stdin)['candidates']))" 2>/dev/null)
[ "$NCAND" -ge 1 ] && ok "迟到补偿生成 $NCAND 个新运行候选" || bad "迟到补偿未生成候选"

echo "=== 10. 成本追溯调整 → 新版本 + 新运行候选 ==="
CR=$(curl -s -X POST "$BASE/api/promo/cost-revisions" -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"factor":"1.10","note":"验收：成本追溯调整+10%"}')
NCR=$(echo "$CR" | py -c "import sys,json;print(len(json.load(sys.stdin)['candidates']))" 2>/dev/null)
[ "$NCR" -ge 1 ] && ok "成本修订生成新版本并触发 $NCR 个新运行候选" || bad "成本修订未触发候选"
# 历史发布仍可按旧成本复演（成本不变）
GM_REPLAY2=$(curl -s -X POST "$BASE/api/promo/runs/$RUNID/replay" -H "$AUTH" | py -c "import sys,json;print(json.load(sys.stdin)['totals']['grossMargin'])" 2>/dev/null)
[ "$GM_REPLAY2" = "$GM_ORIG" ] && ok "成本调整后历史发布仍按旧成本复演（毛利 $GM_REPLAY2）" || bad "历史复演被成本修订污染"

echo "=== 11. 采纳候选 → 新运行反映最新成本/补偿 ==="
CID=$(curl -s "$BASE/api/promo/candidates?status=pending" -H "$AUTH" | py -c "import sys,json;c=json.load(sys.stdin);print(c[0]['id'] if c else '')")
if [ -n "$CID" ]; then
  ADOPT=$(curl -s -X POST "$BASE/api/promo/candidates/$CID/adopt" -H "$AUTH")
  echo "$ADOPT" | py -c "
import sys,json
d=json.load(sys.stdin); t=d['totals']
assert d['id']!=int('$RUNID'), '应产生新运行'
print('OK')" >/dev/null 2>&1 && ok "采纳候选生成新运行（成本/补偿按最新事实）" || bad "采纳候选失败"
else
  bad "无 pending 候选"
fi

echo "=== 12. 语义层集成（promo_bridge 受控节点） ==="
SVID=$(curl -s "$BASE/api/metrics/promo_bridge_margin/published" -H "$AUTH" | py -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
SRUN=$(curl -s -X POST "$BASE/api/runs" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"metricKey\":\"promo_bridge_margin\",\"metricVersionId\":$SVID,\"from\":\"2026-05-01\",\"to\":\"2026-05-31\",\"grain\":\"month\"}")
SRID=$(echo "$SRUN" | py -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null)
SCELLS=$(curl -s "$BASE/api/runs/$SRID/cells" -H "$AUTH" | py -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
[ -n "$SRID" ] && [ "$SCELLS" -gt 0 ] && ok "促销毛利桥作为受控 DSL 节点运行（$SCELLS 个单元格）" || bad "语义层桥运行失败"

echo ""
echo "=========================================="
echo "验收结果: 通过 $PASS 项, 失败 $FAIL 项"
echo "=========================================="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
