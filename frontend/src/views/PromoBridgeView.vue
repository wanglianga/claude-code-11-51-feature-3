<template>
  <div class="promo-view">
    <!-- 顶部：运行控制 -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span>促销毛利桥</span>
          <div class="actions">
            <el-button size="small" @click="showLateComp = true">回放迟到补偿</el-button>
            <el-button size="small" @click="showCostRev = true">成本追溯调整</el-button>
            <el-button type="primary" size="small" :loading="running" @click="createRun">生成桥运行</el-button>
          </div>
        </div>
      </template>
      <el-form :inline="true" :model="form" size="small">
        <el-form-item label="期间">
          <el-date-picker v-model="form.range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item label="主口径">
          <el-select v-model="form.method" style="width: 150px">
            <el-option label="按金额分摊" value="amount" />
            <el-option label="按件数均摊" value="quantity" />
          </el-select>
        </el-form-item>
        <el-form-item label="税基">
          <el-select v-model="form.taxBasis" style="width: 150px">
            <el-option label="含税" value="tax_inclusive" />
            <el-option label="不含税" value="tax_exclusive" />
          </el-select>
        </el-form-item>
        <el-form-item label="粒度">
          <el-radio-group v-model="form.grain">
            <el-radio-button label="month">月</el-radio-button>
            <el-radio-button label="day">日</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="成本版本">
          <el-select v-model="form.costVersionId" clearable placeholder="最新" style="width: 170px">
            <el-option v-for="c in costVersions" :key="c.id" :label="`v${c.version} (${c.status})`" :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon style="margin-top: 8px">
        本页只重建促销让利与毛利守恒，不输出活动因果增量；因果增量须经对照设计验证。
        合同或 POS 证据不足以判断分摊基础时，金额列为“未分摊”，不自行选择更有利的算法。
      </el-alert>
    </el-card>

    <!-- 运行列表 -->
    <el-card style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>桥运行</span>
          <el-button size="small" @click="loadRuns">刷新</el-button>
        </div>
      </template>
      <el-table :data="runs" stripe size="small" @row-click="selectRun" highlight-current-row>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="期间" width="190">
          <template #default="{ row }">{{ row.periodFrom }} ~ {{ row.periodTo }}</template>
        </el-table-column>
        <el-table-column prop="method" label="口径" width="90" />
        <el-table-column prop="taxBasis" label="税基" width="130" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
            <el-tag v-if="row.publicationVersion" size="small" type="success" style="margin-left: 4px">v{{ row.publicationVersion }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="净销售额" width="120" align="right">
          <template #default="{ row }">{{ money(row.totals?.netSales) }}</template>
        </el-table-column>
        <el-table-column label="净毛利贡献" width="130" align="right">
          <template #default="{ row }">{{ money(row.totals?.netMarginContribution) }}</template>
        </el-table-column>
        <el-table-column label="守恒校验" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="row.totals?.checksFailed > 0 ? 'danger' : 'success'">
              {{ row.totals?.checksPassed || 0 }}/{{ row.totals?.checksTotal || 0 }} 通过
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button size="small" link @click.stop="viewRun(row)">详情</el-button>
            <el-button size="small" link :disabled="row.status === 'published'" @click.stop="publishRun(row)">发布</el-button>
            <el-button size="small" link @click.stop="replayRun(row)">复演</el-button>
            <el-button size="small" link @click.stop="compareRun(row)">口径对照</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 桥详情 -->
    <el-card v-if="detail" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>毛利桥明细（运行 {{ detail.id }} · {{ detail.method }} · {{ detail.taxBasis }}）</span>
          <div>
            <el-button size="small" @click="exportCsv">导出 CSV</el-button>
            <el-button v-if="detail.status !== 'published'" type="primary" size="small" @click="publishRun(detail)">发布</el-button>
          </div>
        </div>
      </template>

      <!-- 守恒校验 -->
      <el-alert v-if="detail.warnings && detail.warnings.length" type="warning" :closable="false" show-icon style="margin-bottom: 12px">
        <div v-for="(w, i) in detail.warnings" :key="i">{{ w }}</div>
      </el-alert>
      <el-row :gutter="12">
        <el-col :span="14">
          <el-table :data="detail.checks" size="small" border>
            <el-table-column prop="checkCode" label="校验项" width="240" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'pass' ? 'success' : (row.status === 'warn' ? 'warning' : 'danger')">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源/期望" width="120" align="right">
              <template #default="{ row }">{{ money(row.expected) }}</template>
            </el-table-column>
            <el-table-column label="桥合计" width="120" align="right">
              <template #default="{ row }">{{ money(row.actual) }}</template>
            </el-table-column>
            <el-table-column prop="message" label="说明" />
          </el-table>
        </el-col>
        <el-col :span="10">
          <el-descriptions :column="1" border size="small" title="桥合计">
            <el-descriptions-item label="标价销售">{{ money(detail.totals?.listSales) }}</el-descriptions-item>
            <el-descriptions-item label="− 顾客折让">{{ money(detail.totals?.customerConcession) }}</el-descriptions-item>
            <el-descriptions-item label="− 退货冲回">{{ money(detail.totals?.refundReversal) }}</el-descriptions-item>
            <el-descriptions-item label="+ 重分配调整">{{ money(detail.totals?.reallocationAdjustment) }}</el-descriptions-item>
            <el-descriptions-item label="= 净销售额">
              <b>{{ money(detail.totals?.netSales) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="− 销售成本">{{ money(detail.totals?.costOfSales) }}</el-descriptions-item>
            <el-descriptions-item label="= 商品毛利">
              <b>{{ money(detail.totals?.grossMargin) }}</b>
            </el-descriptions-item>
            <el-descriptions-item label="+ 厂商补偿（独立）">{{ money(detail.totals?.supplierCompensation) }}</el-descriptions-item>
            <el-descriptions-item label="= 净毛利贡献">
              <b class="big">{{ money(detail.totals?.netMarginContribution) }}</b>
            </el-descriptions-item>
          </el-descriptions>
        </el-col>
      </el-row>

      <!-- 桥明细行 -->
      <el-tabs v-model="tab" style="margin-top: 16px">
        <el-tab-pane label="桥明细（活动×商品×门店）" name="lines">
          <el-table :data="detail.lines" size="small" border stripe max-height="460">
            <el-table-column label="活动" width="80">
              <template #default="{ row }">{{ row.campaignCode || '-' }}</template>
            </el-table-column>
            <el-table-column label="商品" width="110">
              <template #default="{ row }">{{ row.sku }}</template>
            </el-table-column>
            <el-table-column label="门店" width="90">
              <template #default="{ row }">{{ row.storeId }}</template>
            </el-table-column>
            <el-table-column label="行类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="kindType(row.lineKind)">{{ kindText(row.lineKind) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="标价销售" width="110" align="right">
              <template #default="{ row }">{{ money(row.listSales) }}</template>
            </el-table-column>
            <el-table-column label="顾客折让" width="110" align="right">
              <template #default="{ row }">{{ money(row.customerConcession) }}</template>
            </el-table-column>
            <el-table-column label="退货冲回" width="110" align="right">
              <template #default="{ row }">{{ money(row.refundReversal) }}</template>
            </el-table-column>
            <el-table-column label="净销售额" width="110" align="right">
              <template #default="{ row }">{{ money(row.netSales) }}</template>
            </el-table-column>
            <el-table-column label="销售成本" width="110" align="right">
              <template #default="{ row }">{{ money(row.costOfSales) }}</template>
            </el-table-column>
            <el-table-column label="商品毛利" width="110" align="right">
              <template #default="{ row }">{{ money(row.grossMargin) }}</template>
            </el-table-column>
            <el-table-column label="厂商补偿" width="110" align="right">
              <template #default="{ row }">{{ money(row.supplierCompensation) }}</template>
            </el-table-column>
            <el-table-column label="净毛利贡献" width="120" align="right">
              <template #default="{ row }"><b>{{ money(row.netMarginContribution) }}</b></template>
            </el-table-column>
            <el-table-column label="已售/退" width="80" align="center">
              <template #default="{ row }">{{ row.quantitySold }}/{{ row.quantityReturned }}</template>
            </el-table-column>
            <el-table-column label="血缘" width="70">
              <template #default="{ row }">
                <el-button size="small" link @click="openLineage(row)">下钻</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="订单级折让分摊" name="alloc">
          <el-alert type="info" :closable="false" style="margin-bottom: 8px">
            保留原始订单级金额；已分摊 + 未分摊 = 优惠执行原始金额（分）。未分摊表示合同/POS 证据不足，未自行选择算法。
          </el-alert>
          <el-table :data="detail.allocations" size="small" border stripe max-height="460">
            <el-table-column prop="orderId" label="订单" width="90" />
            <el-table-column prop="executionId" label="执行明细" width="100" />
            <el-table-column prop="campaignCode" label="活动" width="80" />
            <el-table-column prop="basis" label="分摊基础" width="100" />
            <el-table-column label="原始金额" width="110" align="right">
              <template #default="{ row }">{{ money(row.originalAmount) }}</template>
            </el-table-column>
            <el-table-column label="已分摊" width="110" align="right">
              <template #default="{ row }">{{ money(row.allocatedAmount) }}</template>
            </el-table-column>
            <el-table-column label="未分摊" width="110" align="right">
              <template #default="{ row }">{{ money(row.unallocatedAmount) }}</template>
            </el-table-column>
            <el-table-column label="尾差(分)" width="90" align="center" prop="remainderCents" />
            <el-table-column label="状态" width="160">
              <template #default="{ row }">
                <el-tag size="small" :type="allocStatusType(row.status)">{{ allocStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="说明" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="退货重算" name="refund">
          <el-alert type="info" :closable="false" style="margin-bottom: 8px">
            按原订单行、原优惠版本与剩余商品重检门槛/买赠资格；退款现金与折让重分配分别列示，不做“按退款金额同比例冲回整单折让”。
          </el-alert>
          <el-table :data="detail.refundAdjustments" size="small" border stripe max-height="460">
            <el-table-column prop="orderId" label="订单" width="80" />
            <el-table-column prop="orderLineId" label="原订单行" width="100" />
            <el-table-column prop="returnBusinessDate" label="退货营业日" width="110" />
            <el-table-column label="退货标价" width="100" align="right">
              <template #default="{ row }">{{ money(row.returnedList) }}</template>
            </el-table-column>
            <el-table-column label="原实付" width="100" align="right">
              <template #default="{ row }">{{ money(row.originalPaid) }}</template>
            </el-table-column>
            <el-table-column label="剩余应付" width="100" align="right">
              <template #default="{ row }">{{ money(row.remainingPaid) }}</template>
            </el-table-column>
            <el-table-column label="退款现金" width="100" align="right">
              <template #default="{ row }">{{ money(row.refundCash) }}</template>
            </el-table-column>
            <el-table-column label="折让冲回(memo)" width="120" align="right">
              <template #default="{ row }">{{ money(row.discountReversal) }}</template>
            </el-table-column>
            <el-table-column label="门槛失效" width="90" align="center">
              <template #default="{ row }"><el-tag v-if="row.thresholdLost" size="small" type="danger">是</el-tag><span v-else>否</span></template>
            </el-table-column>
            <el-table-column label="买赠失效" width="90" align="center">
              <template #default="{ row }"><el-tag v-if="row.giftInvalidated" size="small" type="danger">是</el-tag><span v-else>否</span></template>
            </el-table-column>
            <el-table-column prop="note" label="说明" min-width="200" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="双口径对照" name="compare">
          <div v-if="compare">
            <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 8px">
              {{ compare.note }}
            </el-alert>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-card shadow="never">
                  <template #header>按金额分摊（金额守恒）</template>
                  <el-descriptions :column="1" size="small" border>
                    <el-descriptions-item label="顾客折让">{{ money(compare.byAmount.customerConcession) }}</el-descriptions-item>
                    <el-descriptions-item label="净销售额">{{ money(compare.byAmount.netSales) }}</el-descriptions-item>
                    <el-descriptions-item label="商品毛利">{{ money(compare.byAmount.grossMargin) }}</el-descriptions-item>
                    <el-descriptions-item label="净毛利贡献">{{ money(compare.byAmount.netMarginContribution) }}</el-descriptions-item>
                  </el-descriptions>
                </el-card>
              </el-col>
              <el-col :span="12">
                <el-card shadow="never">
                  <template #header>按件数均摊</template>
                  <el-descriptions :column="1" size="small" border>
                    <el-descriptions-item label="顾客折让">{{ money(compare.byQuantity.customerConcession) }}</el-descriptions-item>
                    <el-descriptions-item label="净销售额">{{ money(compare.byQuantity.netSales) }}</el-descriptions-item>
                    <el-descriptions-item label="商品毛利">{{ money(compare.byQuantity.grossMargin) }}</el-descriptions-item>
                    <el-descriptions-item label="净毛利贡献">{{ money(compare.byQuantity.netMarginContribution) }}</el-descriptions-item>
                  </el-descriptions>
                </el-card>
              </el-col>
            </el-row>
            <el-table :data="compare.skuDiff" size="small" border style="margin-top: 12px">
              <el-table-column prop="skuStore" label="商品@门店" />
              <el-table-column label="按金额分摊毛利" align="right">
                <template #default="{ row }">{{ money(row.grossMarginByAmount) }}</template>
              </el-table-column>
              <el-table-column label="按件数均摊毛利" align="right">
                <template #default="{ row }">{{ money(row.grossMarginByQuantity) }}</template>
              </el-table-column>
              <el-table-column label="差异" align="right">
                <template #default="{ row }">{{ money(row.delta) }}</template>
              </el-table-column>
            </el-table>
            <p class="causal-note">因果增量：{{ compare.causalIncrement }}</p>
          </div>
        </el-tab-pane>

        <el-tab-pane label="输入版本" name="versions">
          <el-table :data="detail.inputVersionLog" size="small" border>
            <el-table-column prop="kind" label="类型" width="160">
              <template #default="{ row }">{{ versionKindText(row.kind) }}</template>
            </el-table-column>
            <el-table-column prop="versionLabel" label="版本" />
            <el-table-column label="输入哈希" width="130">
              <template #default="{ row }">{{ shortHash(row.inputHash) }}</template>
            </el-table-column>
            <el-table-column label="输出哈希" width="130">
              <template #default="{ row }">{{ shortHash(row.outputHash) }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" width="180" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 血缘 -->
    <el-dialog v-model="lineageVisible" title="单元格血缘下钻" width="720px">
      <div class="lineage-key">{{ lineageKey }}</div>
      <el-table :data="lineage" size="small" border max-height="420">
        <el-table-column prop="recordType" label="记录类型" width="140" />
        <el-table-column prop="recordId" label="记录" width="200" />
        <el-table-column label="贡献" width="120" align="right">
          <template #default="{ row }">{{ money(row.contribution) }}</template>
        </el-table-column>
        <el-table-column label="计入" width="70" align="center">
          <template #default="{ row }"><el-tag size="small" :type="row.included ? 'success' : 'info'">{{ row.included ? '是' : '否' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="reason" label="说明" />
      </el-table>
    </el-dialog>

    <!-- 候选 -->
    <el-card style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>新运行候选（成本追溯调整 / 迟到补偿只生成新运行，历史发布仍可按旧输入复演）</span>
          <el-button size="small" @click="loadCandidates">刷新</el-button>
        </div>
      </template>
      <el-table :data="candidates" size="small" border>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="sourceRunId" label="源运行" width="90" />
        <el-table-column prop="triggerType" label="触发" width="160" />
        <el-table-column prop="triggerRef" label="触发引用" width="140" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="note" label="说明" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" link :disabled="row.status !== 'pending'" @click="adopt(row)">采纳</el-button>
            <el-button size="small" link :disabled="row.status !== 'pending'" @click="reject(row)">拒绝</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 迟到补偿 -->
    <el-dialog v-model="showLateComp" title="回放迟到供应商补偿" width="480px">
      <el-form :model="lateComp" label-width="110px" size="small">
        <el-form-item label="补偿凭证号"><el-input v-model="lateComp.compId" /></el-form-item>
        <el-form-item label="活动">
          <el-select v-model="lateComp.campaignCode" style="width: 100%">
            <el-option v-for="c in campaigns" :key="c.campaignCode" :label="`${c.campaignCode} ${c.name}`" :value="c.campaignCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="SKU"><el-input v-model="lateComp.sku" /></el-form-item>
        <el-form-item label="门店"><el-input v-model="lateComp.storeId" /></el-form-item>
        <el-form-item label="营业日期">
          <el-date-picker v-model="lateComp.businessDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="金额"><el-input-number v-model="lateComp.amount" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="合同处理">
          <el-select v-model="lateComp.marginTreatment" style="width: 100%">
            <el-option label="rebate（计入毛利分析）" value="rebate" />
            <el-option label="cost_reduction（成本回补）" value="cost_reduction" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showLateComp = false">取消</el-button>
        <el-button type="primary" @click="submitLateComp">提交</el-button>
      </template>
    </el-dialog>

    <!-- 成本修订 -->
    <el-dialog v-model="showCostRev" title="成本追溯调整（生成新运行候选）" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="调整系数">
          <el-input-number v-model="costRev.factor" :min="0.1" :step="0.05" :precision="2" />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="costRev.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCostRev = false">取消</el-button>
        <el-button type="primary" @click="submitCostRev">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api/client'

const form = ref({
  range: ['2026-05-01', '2026-05-31'],
  method: 'amount',
  taxBasis: 'tax_inclusive',
  grain: 'month',
  costVersionId: null
})
const running = ref(false)
const runs = ref([])
const detail = ref(null)
const compare = ref(null)
const tab = ref('lines')
const candidates = ref([])
const costVersions = ref([])
const campaigns = ref([])
const lineage = ref([])
const lineageKey = ref('')
const lineageVisible = ref(false)
const showLateComp = ref(false)
const showCostRev = ref(false)
const lateComp = ref({ compId: 'SC002', campaignCode: 'C5', sku: 'SKU-Y', storeId: 'S101', businessDate: '2026-05-20', amount: 40, marginTreatment: 'rebate' })
const costRev = ref({ factor: 1.1, note: '成本追溯调整' })

function money(v) {
  if (v === null || v === undefined) return '-'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function shortHash(h) { return h ? h.substring(0, 10) : '-' }
function statusType(s) { return s === 'published' ? 'success' : s === 'superseded' ? 'info' : s === 'failed' ? 'danger' : 'warning' }
function statusText(s) { return ({ draft: '草稿', published: '已发布', superseded: '已替代', failed: '失败' })[s] || s }
function kindType(k) { return ({ item: '', unallocated: 'warning', compensation: 'success' })[k] }
function kindText(k) { return ({ item: '销售行', unallocated: '未分摊折让', compensation: '厂商补偿' })[k] || k }
function allocStatusType(s) {
  return ({ allocated: 'success', partial: 'warning', unallocated: 'warning', conflict: 'danger', invalid: 'danger', classified_compensation: 'info' })[s] || 'info'
}
function allocStatusText(s) {
  return ({ allocated: '已分摊', partial: '部分分摊', unallocated: '未分摊', conflict: '违反叠加', invalid: '无效', classified_compensation: '归为补偿' })[s] || s
}
function versionKindText(k) {
  return ({ rule: '活动规则版本', cost: '商品成本版本', discount_compilation: '订单优惠编译', refund_recalc: '退货重算', publication: '毛利发布' })[k] || k
}

async function loadRuns() {
  try {
    const res = await api.get('/promo/runs')
    runs.value = res.data
  } catch (e) { ElMessage.error('加载运行失败: ' + (e.response?.data?.error || e.message)) }
}
async function loadCostVersions() {
  try { costVersions.value = (await api.get('/promo/cost-versions')).data } catch (e) { /* ignore */ }
}
async function loadCampaigns() {
  try { campaigns.value = (await api.get('/promo/campaigns')).data } catch (e) { /* ignore */ }
}
async function loadCandidates() {
  try { candidates.value = (await api.get('/promo/candidates')).data } catch (e) { /* ignore */ }
}

async function createRun() {
  if (!form.value.range || form.value.range.length !== 2) { ElMessage.warning('请选择期间'); return }
  running.value = true
  try {
    const body = {
      from: form.value.range[0], to: form.value.range[1],
      method: form.value.method, taxBasis: form.value.taxBasis, grain: form.value.grain
    }
    if (form.value.costVersionId) body.costVersionId = form.value.costVersionId
    const res = await api.post('/promo/runs', body)
    detail.value = res.data
    compare.value = null
    tab.value = 'lines'
    ElMessage.success('已生成桥运行 #' + res.data.id)
    await loadRuns()
  } catch (e) {
    ElMessage.error('生成失败: ' + (e.response?.data?.error || e.message))
  } finally { running.value = false }
}

async function selectRun(row) { viewRun(row) }
async function viewRun(row) {
  try {
    const res = await api.get(`/promo/runs/${row.id}`)
    detail.value = res.data
    compare.value = null
    tab.value = 'lines'
  } catch (e) { ElMessage.error('加载详情失败: ' + (e.response?.data?.error || e.message)) }
}

async function publishRun(row) {
  try {
    const res = await api.post(`/promo/runs/${row.id}/publish`)
    detail.value = res.data
    ElMessage.success('已发布 v' + res.data.publicationVersion)
    await loadRuns()
  } catch (e) { ElMessage.error('发布失败: ' + (e.response?.data?.error || e.message)) }
}

async function replayRun(row) {
  try {
    const res = await api.post(`/promo/runs/${row.id}/replay`)
    ElMessage.success('已按历史快照复演为运行 #' + res.data.id)
    detail.value = res.data
    await loadRuns()
  } catch (e) { ElMessage.error('复演失败: ' + (e.response?.data?.error || e.message)) }
}

async function compareRun(row) {
  try {
    compare.value = (await api.get(`/promo/runs/${row.id}/compare`)).data
    if (!detail.value || detail.value.id !== row.id) await viewRun(row)
    tab.value = 'compare'
  } catch (e) { ElMessage.error('对照失败: ' + (e.response?.data?.error || e.message)) }
}

async function openLineage(row) {
  lineageKey.value = row.lineKey
  try {
    lineage.value = (await api.get(`/promo/runs/${detail.value.id}/lineage`, { params: { lineKey: row.lineKey } })).data
    lineageVisible.value = true
  } catch (e) { ElMessage.error('血缘加载失败: ' + (e.response?.data?.error || e.message)) }
}

async function submitLateComp() {
  try {
    const res = await api.post('/promo/compensations', lateComp.value)
    ElMessage.success(res.data.message || '已提交')
    showLateComp.value = false
    await loadCandidates()
  } catch (e) { ElMessage.error('提交失败: ' + (e.response?.data?.error || e.message)) }
}
async function submitCostRev() {
  try {
    const res = await api.post('/promo/cost-revisions', costRev.value)
    ElMessage.success(res.data.message || '已提交')
    showCostRev.value = false
    await Promise.all([loadCandidates(), loadCostVersions()])
  } catch (e) { ElMessage.error('提交失败: ' + (e.response?.data?.error || e.message)) }
}
async function adopt(row) {
  try {
    const res = await api.post(`/promo/candidates/${row.id}/adopt`)
    ElMessage.success('已采纳为运行 #' + res.data.id)
    detail.value = res.data
    await Promise.all([loadCandidates(), loadRuns()])
  } catch (e) { ElMessage.error('采纳失败: ' + (e.response?.data?.error || e.message)) }
}
async function reject(row) {
  try {
    await api.post(`/promo/candidates/${row.id}/reject`, { note: '负责人拒绝' })
    ElMessage.success('已拒绝')
    await loadCandidates()
  } catch (e) { ElMessage.error('拒绝失败: ' + (e.response?.data?.error || e.message)) }
}

function exportCsv() {
  if (!detail.value) return
  const cols = ['campaignCode', 'sku', 'storeId', 'lineKind', 'listSales', 'customerConcession',
    'refundReversal', 'reallocationAdjustment', 'netSales', 'costOfSales', 'grossMargin',
    'supplierCompensation', 'netMarginContribution', 'quantitySold', 'quantityReturned']
  const head = cols.join(',')
  const rows = detail.value.lines.map(l => cols.map(c => `"${l[c] ?? ''}"`).join(','))
  const blob = new Blob([head + '\n' + rows.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `promo-bridge-run-${detail.value.id}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(async () => {
  await Promise.all([loadRuns(), loadCostVersions(), loadCampaigns(), loadCandidates()])
  if (runs.value.length) await viewRun(runs.value[0])
})
</script>

<style scoped>
.promo-view { padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.actions { display: flex; gap: 8px; }
.big { font-size: 18px; color: #409eff; }
.causal-note { color: #b88230; margin-top: 12px; }
.lineage-key { font-family: monospace; color: #606266; margin-bottom: 8px; word-break: break-all; }
</style>
