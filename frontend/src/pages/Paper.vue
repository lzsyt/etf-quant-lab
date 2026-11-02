<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  api,
  guarded,
  money,
  percent,
  names,
  statuses,
  time,
  type Row,
} from "../api";
import { ElMessage } from "element-plus";
import DataTable from "../components/DataTable.vue";
import Chart from "../components/Chart.vue";
const account = ref("lightgbm"),
  accounts = ref<Row[]>([]),
  positions = ref<Row[]>([]),
  orders = ref<Row[]>([]),
  fills = ref<Row[]>([]),
  snapshots = ref<Row[]>([]),
  risk = ref<Row>({}),
  events = ref<Row[]>([]),
  diagnosis = ref<Row>({}),
  reconstruction = ref<Row>({}),
  editing = ref(false),
  reason = ref(""),
  busy = ref(false);
const form = reactive<Row>({});
const current = computed(
  () => accounts.value.find((v) => v.id === account.value) || {},
);
/** 账户权益未知时保持未知，不能将NULL转成0并显示-100%收益。 */
const totalReturn = computed(() =>
  current.value.equity == null || Number(current.value.initial_capital) <= 0
    ? null
    : Number(current.value.equity) / Number(current.value.initial_capital) - 1,
);
const labels: Row = {
  max_single_weight: "单只ETF仓位上限",
  max_total_weight: "总仓位上限",
  max_order_weight: "单笔买入上限",
  max_daily_buy_weight: "单日新增买入上限",
  daily_loss_limit: "单日亏损暂停阈值",
  drawdown_lock: "峰值回撤锁定阈值",
};
const curve = computed(() => ({
  tooltip: { trigger: "axis" },
  grid: { left: 65, right: 20, top: 20, bottom: 35 },
  xAxis: { type: "category", data: snapshots.value.map((v) => v.trade_date) },
  yAxis: { type: "value", scale: true },
  series: [
    {
      name: "前瞻模拟净值",
      type: "line",
      data: snapshots.value.map((v) => v.equity),
      showSymbol: true,
    },
  ],
}));
/** 诊断由服务端结合调度、行情、信号和拒单生成；启用标志不等于任务执行成功。 */
function diagnosticMessage() {
  return (
    diagnosis.value.message ||
    "尚未取得运行诊断，不能仅凭启用状态判断策略正在执行。"
  );
}
/** 按账户刷新账本核验，保持只读，不在浏览器重建或覆盖账户。 */
async function verifyLedger() {
  await guarded(async () => {
    reconstruction.value = await api(
      "/paper/reconstruction?account=" + account.value,
    );
  });
}
async function load() {
  busy.value = true;
  diagnosis.value = {};
  reconstruction.value = {};
  await guarded(async () => {
    [
      accounts.value,
      positions.value,
      orders.value,
      fills.value,
      snapshots.value,
      risk.value,
      events.value,
      diagnosis.value,
    ] = await Promise.all([
      api("/paper/accounts"),
      api("/paper/positions?account=" + account.value),
      api("/paper/orders?account=" + account.value),
      api("/paper/fills?account=" + account.value),
      api("/paper/snapshots?account=" + account.value),
      api("/risk"),
      api("/risk/events"),
      api("/paper/diagnostics?account=" + account.value),
    ]);
  });
  busy.value = false;
}
async function toggle() {
  await guarded(async () => {
    await api("/strategies/" + account.value + "/enabled", {
      enabled: !current.value.enabled,
    });
    await load();
    ElMessage.success("模拟策略状态已更新");
  });
}
function editRisk() {
  for (const key of Object.keys(labels))
    form[key] = Number(risk.value.current[key]) * 100;
  reason.value = "";
  editing.value = true;
}
async function saveRisk() {
  if (!reason.value.trim()) {
    ElMessage.warning("请填写修改原因");
    return;
  }
  await guarded(async () => {
    const values = { ...risk.value.current };
    for (const key of Object.keys(labels))
      values[key] = String(form[key] / 100);
    await api("/risk", { parameters: values, reason: reason.value });
    editing.value = false;
    await load();
    ElMessage.success("风险参数已保存并记录历史");
  });
}
onMounted(load);
</script>
<template>
  <div v-loading="busy">
    <div class="toolbar">
      <el-select v-model="account" @change="load" aria-label="模拟账户"
        ><el-option
          v-for="(name, id) in names"
          :key="id"
          :value="id"
          :label="name" /></el-select
      ><el-tag :type="current.enabled ? 'success' : 'info'">{{
        current.enabled ? "策略已启用" : "新增交易已暂停"
      }}</el-tag
      ><el-tag v-if="current.locked" type="danger">回撤风控已锁定</el-tag
      ><el-button
        @click="toggle"
        :type="current.enabled ? 'default' : 'primary'"
        >{{ current.enabled ? "暂停策略" : "启用模拟策略" }}</el-button
      ><el-button @click="load">刷新</el-button>
    </div>
    <div class="stats">
      <div class="stat">
        <div class="stat-label">虚拟总资产</div>
        <div class="stat-value accent">{{ money(current.equity) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">可用虚拟现金</div>
        <div class="stat-value">{{ money(current.cash) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">累计模拟收益</div>
        <div class="stat-value">
          {{ percent(totalReturn) }}
        </div>
      </div>
      <div class="stat">
        <div class="stat-label">累计费用</div>
        <div class="stat-value">
          {{ money(current.state?.cumulative_fees) }}
        </div>
      </div>
    </div>
    <div class="note">
      模拟成交采用下一可用日线的开盘价估计，无法证明真实盘中可成交。暂停策略后继续估值，不清空持仓；模型或行情缺失时停止新订单。仅使用虚拟资金。
    </div>
    <section class="panel" aria-label="模拟交易运行诊断">
      <div class="panel-head">
        <h2>为什么没有交易 / 当前运行证据</h2>
        <el-tag
          :type="
            diagnosis.reason_code === 'TRADES_EXECUTED' ? 'success' : 'warning'
          "
          >{{ diagnosis.reason_code || "待核查" }}</el-tag
        >
      </div>
      <p class="text-output" data-testid="paper-diagnostic-message">
        {{ diagnosticMessage() }}
      </p>
      <div class="form-grid">
        <div class="info-row">
          <span>自动调度配置</span
          ><strong>{{
            diagnosis.scheduling_enabled == null
              ? "未知"
              : diagnosis.scheduling_enabled
                ? "已启用"
                : "未启用"
          }}</strong>
        </div>
        <div class="info-row">
          <span>最近日终任务</span
          ><strong>{{
            statuses[diagnosis.last_task?.status] ||
            diagnosis.last_task?.status ||
            "尚无执行记录"
          }}</strong>
        </div>
        <div class="info-row">
          <span>任务更新时间</span
          ><strong>{{ time(diagnosis.last_task?.updated_at) }}</strong>
        </div>
        <div class="info-row">
          <span>最新行情日</span
          ><strong>{{ diagnosis.latest_market_date || "暂无行情" }}</strong>
        </div>
        <div class="info-row">
          <span>最近信号日</span
          ><strong>{{ diagnosis.last_signal_date || "暂无信号" }}</strong>
        </div>
        <div class="info-row">
          <span>下一交易日</span
          ><strong>{{ diagnosis.next_trade_date || "日历未确认" }}</strong>
        </div>
        <div class="info-row">
          <span>实际成交笔数</span
          ><strong>{{ diagnosis.fill_count ?? "—" }}</strong>
        </div>
        <div class="info-row">
          <span>资金利用率</span
          ><strong>{{ percent(diagnosis.capital_utilization) }}</strong>
        </div>
      </div>
      <p v-if="diagnosis.last_settlement?.reason" class="small muted">
        最近结算：{{ diagnosis.last_settlement.reason }}
      </p>
      <p v-if="diagnosis.last_task?.error_code" class="small muted">
        任务异常：{{ diagnosis.last_task.error_code }}
      </p>
      <p class="small muted">
        “策略已启用”只表示允许新增模拟订单；运行情况以上述任务和结算证据为准。非交易日、信号不足及风控拒单不会伪造成交。
      </p>
    </section>
    <section class="panel">
      <div class="panel-head">
        <h2>账户账本核验</h2>
        <el-button @click="verifyLedger">核对成交与账户</el-button>
      </div>
      <p v-if="reconstruction.consistent != null" class="text-output">
        {{
          reconstruction.consistent
            ? "当前账户与历史账本一致"
            : "发现账本差异，需要核查"
        }}；现金差额 {{ money(reconstruction.cash_difference) }} 元。
      </p>
      <p v-else class="small muted">
        按历史成交与净值核对当前账户，只读检查，不修改资金与持仓。
      </p>
      <div v-if="reconstruction.consistent === false" class="text-output">
        {{ JSON.stringify(reconstruction, null, 2) }}
      </div>
    </section>
    <section class="panel">
      <div class="panel-head">
        <h2>账户净值与每日收益</h2>
        <span class="small muted"
          >最近结算 {{ current.last_trade_date || "尚未开始" }}</span
        >
      </div>
      <Chart v-if="snapshots.length" :option="curve" :height="260" />
      <div v-else class="empty">等待真实交易日积累净值，不以历史回测替代。</div>
      <DataTable
        :rows="[...snapshots].reverse()"
        :page-size="5"
        :columns="[
          { key: 'trade_date', label: '交易日' },
          { key: 'equity', label: '总资产', format: money },
          { key: 'cash', label: '虚拟现金', format: money },
          {
            key: 'details',
            label: '当日盈亏',
            format: (v) => money(v.daily_pnl),
          },
          { key: 'details', label: '回撤', format: (v) => percent(v.drawdown) },
        ]"
      />
    </section>
    <section class="panel">
      <el-tabs
        ><el-tab-pane label="当前持仓"
          ><DataTable
            :rows="positions"
            :columns="[
              { key: 'symbol', label: 'ETF' },
              { key: 'quantity', label: '份额' },
              { key: 'cost_basis', label: '总成本', format: money },
              {
                key: 'lots',
                label: 'T+1持仓批次',
                format: (v) => JSON.stringify(v),
                width: 320,
              },
            ]" /></el-tab-pane
        ><el-tab-pane label="模拟订单"
          ><DataTable
            :rows="orders"
            :columns="[
              { key: 'trade_date', label: '日期' },
              { key: 'symbol', label: 'ETF' },
              { key: 'side', label: '买卖' },
              { key: 'status', label: '状态', format: (v) => statuses[v] || v },
              {
                key: 'details',
                label: '未成交原因',
                format: (v) => v.reason || '—',
                width: 240,
              },
              { key: 'business_key', label: '唯一业务标识', width: 260 },
            ]" /></el-tab-pane
        ><el-tab-pane label="成交与费用"
          ><DataTable
            :rows="fills"
            :columns="[
              { key: 'trade_date', label: '估计成交日' },
              { key: 'symbol', label: 'ETF' },
              { key: 'side', label: '买卖' },
              { key: 'quantity', label: '份额' },
              { key: 'price', label: '估计成交价' },
              { key: 'fee', label: '手续费', format: money },
            ]" /></el-tab-pane
        ><el-tab-pane label="风控事件"
          ><DataTable
            :rows="events.filter((v) => v.account_id === account)"
            :columns="[
              { key: 'trade_date', label: '交易日' },
              { key: 'rule', label: '触发规则', width: 280 },
              {
                key: 'details',
                label: '详情',
                format: (v) => JSON.stringify(v),
                width: 240,
              },
            ]" /></el-tab-pane
      ></el-tabs>
    </section>
    <section class="panel">
      <div class="panel-head">
        <h2>统一模拟风控</h2>
        <el-button @click="editRisk">修改参数</el-button>
      </div>
      <div class="form-grid">
        <div v-for="(label, key) in labels" :key="key" class="info-row">
          <span>{{ label }}</span
          ><strong>{{ percent(risk.current?.[key]) }}</strong>
        </div>
      </div>
      <div class="small muted">
        风险阈值限制新增交易，无法保证持仓不会继续亏损。手续费最低
        {{ money(risk.current?.minimum_commission) }} 元。
      </div>
      <el-collapse style="margin-top: 18px"
        ><el-collapse-item title="参数修改记录"
          ><DataTable
            :rows="risk.history || []"
            :columns="[
              {
                key: 'changed_at',
                label: '修改时间',
                format: time,
                width: 180,
              },
              { key: 'reason', label: '原因', width: 250 },
            ]" /></el-collapse-item
      ></el-collapse>
    </section>
    <el-dialog v-model="editing" title="修改模拟风险参数" width="560px"
      ><el-form label-position="top"
        ><div class="form-grid">
          <el-form-item
            v-for="(label, key) in labels"
            :key="key"
            :label="label + '（%）'"
            ><el-input-number
              v-model="form[key]"
              :min="0.1"
              :max="100"
              :precision="2"
          /></el-form-item>
        </div>
        <el-form-item label="修改原因（必填）"
          ><el-input
            v-model="reason"
            type="textarea"
            :maxlength="500" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="editing = false">取消</el-button
        ><el-button type="primary" @click="saveRisk"
          >保存并记录</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
