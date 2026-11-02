<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from "vue";
import {
  api,
  guarded,
  names,
  percent,
  money,
  number,
  time,
  submitTask,
  type Row,
  statuses,
} from "../api";
import { ElMessage } from "element-plus";
import Chart from "../components/Chart.vue";
import DataTable from "../components/DataTable.vue";
const form = reactive({
  symbols: ["510300", "510500", "159915"],
  strategies: ["lightgbm", "xgboost", "ma", "buy_hold", "cash"],
  dates: ["2026-04-01", "2026-10-23"],
  initial_capital: 100000,
  nature: "REAL",
});
const experiments = ref<Row[]>([]),
  instruments = ref<Row[]>([]),
  result = ref<Row | null>(null),
  selected = ref(""),
  activeStrategy = ref("lightgbm"),
  pending = ref(""),
  jobStatus = ref(""),
  busy = ref(false);
let timer: ReturnType<typeof setTimeout> | undefined;
const strategies = computed(() => Object.keys(result.value?.results || {}));
const metrics = computed(() =>
  Object.entries(result.value?.results || {}).map(([id, v]: [string, any]) => ({
    id,
    ...v.metrics,
    status: v.status || "SUCCEEDED",
    reason: v.reason,
    activity_status: v.metrics?.activity_status,
  })),
);
const active = computed(() => result.value?.results?.[activeStrategy.value]);
/** 低回撤必须与成交和持仓一起解释，旧版本缺少活动指标时明确标记。 */
const activityNote = computed(() => {
  const value = active.value?.metrics;
  if (!value) return "";
  if (value.activity_note) return value.activity_note;
  if (value.trade_count === 0)
    return "未发生交易：收益与低回撤不能证明策略具有预测或风险控制能力。";
  if (value.trade_count != null && value.trade_count < 5)
    return "交易稀少：请结合资金利用率和实际持仓天数判断，低回撤可能主要来自长期空仓。";
  if (value.capital_utilization == null)
    return "此历史结果尚未记录资金利用率等V1.1指标；需要新的审计回测，原记录保持不变。";
  return "成交和资金使用均来自本次回测记录，不能以历史收益推定未来表现。";
});
const curve = computed(() => {
  const entries = Object.entries(result.value?.results || {}).filter(
    ([, v]: [string, any]) => v.curve?.length,
  );
  const first: any = entries[0]?.[1];
  return {
    tooltip: { trigger: "axis" },
    legend: { type: "scroll", top: 0 },
    grid: { left: 65, right: 25, top: 48, bottom: 60 },
    xAxis: {
      type: "category",
      data: first?.curve.map((v) => v.trade_date) || [],
      boundaryGap: false,
    },
    yAxis: {
      type: "value",
      scale: true,
      splitLine: { lineStyle: { color: "#eef2f6" } },
    },
    dataZoom: [{ type: "inside" }, { type: "slider", height: 15, bottom: 8 }],
    series: entries.map(([id, v]: [string, any]) => ({
      name: names[id] || id,
      type: "line",
      showSymbol: false,
      data: v.curve.map((p) => p.equity),
      lineStyle: {
        width: id === "buy_hold" ? 2.5 : 1.5,
        type: id === "cash" ? "dashed" : "solid",
      },
    })),
  };
});
const drawdown = computed(() => ({
  tooltip: { trigger: "axis" },
  grid: { left: 55, right: 15, top: 20, bottom: 35 },
  xAxis: {
    type: "category",
    data: active.value?.curve?.map((v) => v.trade_date) || [],
  },
  yAxis: {
    type: "value",
    axisLabel: { formatter: (v) => (v * 100).toFixed(0) + "%" },
  },
  series: [
    {
      name: "回撤",
      type: "line",
      showSymbol: false,
      data: active.value?.curve?.map((v) => -v.drawdown) || [],
      areaStyle: { opacity: 0.1 },
      color: "#d58772",
    },
  ],
}));
async function load() {
  [experiments.value, instruments.value] = await Promise.all([
    api("/backtests"),
    api("/market/instruments"),
  ]);
  if (!selected.value) {
    const latest = experiments.value.find((v) => v.status === "SUCCEEDED");
    if (latest) {
      selected.value = latest.id;
      await inspect();
    }
  }
}
async function inspect() {
  await guarded(async () => {
    const response = await api("/backtests/" + selected.value);
    result.value = response.result;
    activeStrategy.value =
      Object.keys(response.result?.results || {}).find(
        (k) => response.result.results[k].metrics,
      ) || "lightgbm";
  });
}
async function run() {
  if (!form.symbols.length || !form.strategies.length || !form.dates) {
    ElMessage.warning("请选择ETF、策略与完整日期区间");
    return;
  }
  busy.value = true;
  await guarded(async () => {
    const job = await submitTask("BACKTEST", {
      ...form,
      start: form.dates[0],
      end: form.dates[1],
      initial_capital: String(form.initial_capital),
    });
    pending.value = job.id;
    jobStatus.value = "任务已入队";
    poll();
  });
  busy.value = false;
}
async function poll() {
  try {
    const job = await api("/tasks/" + pending.value);
    jobStatus.value = job.status;
    if (job.status === "SUCCEEDED") {
      result.value = job.result;
      selected.value = job.result.id;
      await load();
      ElMessage.success("回测完成");
      pending.value = "";
    } else if (job.status === "FAILED") {
      ElMessage.error("回测失败：" + job.error_code);
      pending.value = "";
    } else {
      timer = setTimeout(poll, 2000);
    }
  } catch {
    pending.value = "";
  }
}
onMounted(() => guarded(load));
onBeforeUnmount(() => clearTimeout(timer));
</script>
<template>
  <section class="panel">
    <div class="panel-head">
      <h2>创建研究实验</h2>
      <el-tag effect="plain">统一成本与风控口径</el-tag>
    </div>
    <el-form label-position="top" class="form-grid" @submit.prevent="run"
      ><el-form-item label="ETF研究池"
        ><el-select v-model="form.symbols" multiple
          ><el-option
            v-for="item in instruments"
            :key="item.symbol"
            :label="item.symbol + ' · ' + item.name"
            :value="item.symbol" /></el-select></el-form-item
      ><el-form-item label="策略与基准"
        ><el-select
          v-model="form.strategies"
          multiple
          collapse-tags
          collapse-tags-tooltip
          ><el-option
            v-for="(name, id) in names"
            :key="id"
            :value="id"
            :label="name" /></el-select></el-form-item
      ><el-form-item label="回测日期"
        ><el-date-picker
          v-model="form.dates"
          type="daterange"
          value-format="YYYY-MM-DD" /></el-form-item
      ><el-form-item label="虚拟本金（元 / 每组）"
        ><el-input-number
          v-model="form.initial_capital"
          :min="10000"
          :max="100000000"
          :step="10000" /></el-form-item
      ><el-form-item label="行情性质"
        ><el-select v-model="form.nature"
          ><el-option value="REAL" label="真实历史行情" /><el-option
            value="DEMO"
            label="确定性演示数据（非市场收益）" /></el-select></el-form-item
      ><el-form-item label="执行实验"
        ><el-button
          native-type="submit"
          type="primary"
          :loading="busy || !!pending"
          >{{ pending ? "执行中 · " + jobStatus : "启动回测" }}</el-button
        ></el-form-item
      ></el-form
    >
    <p class="small muted">
      日线次日开盘假设成交，包含佣金、最低费用、价差、滑点和成交限制。机器学习仅使用样本外区间；LLM缺少点时历史新闻时不输出历史收益。
    </p>
  </section>
  <section class="panel">
    <div class="panel-head">
      <h2>实验结果</h2>
      <el-select
        v-model="selected"
        @change="inspect"
        class="result-select"
        placeholder="选择历史实验"
        ><el-option
          v-for="item in experiments.filter((v) => v.status === 'SUCCEEDED')"
          :key="item.id"
          :value="item.id"
          :label="time(item.created_at) + ' · ' + item.id.slice(0, 8)"
      /></el-select>
    </div>
    <template v-if="result"
      ><div class="tag-row">
        <el-tag>{{ result.nature === "REAL" ? "真实行情" : "演示数据" }}</el-tag
        ><el-tag type="info">{{ result.start }} — {{ result.end }}</el-tag
        ><el-tag type="warning">历史研究 · 非前瞻模拟</el-tag>
      </div>
      <Chart :option="curve" :height="380" /><DataTable
        :rows="metrics"
        :columns="[
          {
            key: 'id',
            label: '策略',
            format: (v) => names[v] || v,
            width: 170,
          },
          { key: 'total_return', label: '扣费收益', format: percent },
          { key: 'annualized_return', label: '年化收益', format: percent },
          { key: 'max_drawdown', label: '最大回撤', format: percent },
          { key: 'sharpe', label: 'Sharpe', format: number },
          { key: 'sortino', label: 'Sortino', format: number },
          { key: 'trade_count', label: '成交笔数' },
          {
            key: 'activity_status',
            label: '交易活跃度',
            format: (v) => statuses[v] || v || '旧结果未记录',
            width: 145,
          },
          { key: 'capital_utilization', label: '资金利用率', format: percent },
          { key: 'holding_sessions', label: '实际持仓交易日', width: 145 },
          { key: 'fees', label: '手续费（元）', format: money },
          {
            key: 'slippage_cost',
            label: '滑点及价差成本（元）',
            format: money,
            width: 180,
          },
          {
            key: 'excess_return_vs_buy_hold',
            label: '较约束买入持有超额',
            format: percent,
            width: 175,
          },
          { key: 'reason', label: '无法评估原因', width: 250 },
        ]"
    /></template>
    <div v-else class="empty">暂无已完成实验，请创建一次回测。</div>
  </section>
  <section v-if="active?.metrics" class="panel">
    <div class="panel-head">
      <h2>回撤与成交明细</h2>
      <el-select v-model="activeStrategy" style="width: 220px"
        ><el-option
          v-for="id in strategies"
          :key="id"
          :value="id"
          :label="names[id] || id"
      /></el-select>
    </div>
    <div class="stats">
      <div class="stat">
        <div class="stat-label">已平仓交易胜率</div>
        <div class="stat-value">{{ percent(active.metrics.win_rate) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">平均盈利 / 平均亏损（绝对额）比</div>
        <div class="stat-value">
          {{ number(active.metrics.profit_loss_ratio) }}
        </div>
      </div>
      <div class="stat">
        <div class="stat-label">双边累计换手</div>
        <div class="stat-value">{{ percent(active.metrics.turnover) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">年化波动率</div>
        <div class="stat-value">
          {{ percent(active.metrics.annualized_volatility) }}
        </div>
      </div>
    </div>
    <div class="note warning-note" data-testid="backtest-activity">
      {{ activityNote }}
    </div>
    <div class="form-grid">
      <div class="info-row">
        <span>平均盈利金额</span
        ><strong>{{ money(active.metrics.avg_win_amount) }} 元</strong>
      </div>
      <div class="info-row">
        <span>平均亏损金额</span
        ><strong>{{ money(active.metrics.avg_loss_amount) }} 元</strong>
      </div>
      <div class="info-row">
        <span>最大连续亏损次数</span
        ><strong>{{ active.metrics.max_consecutive_losses ?? "—" }}</strong>
      </div>
      <div class="info-row">
        <span>已实现卖出次数</span
        ><strong>{{ active.metrics.closed_trades ?? "—" }}</strong>
      </div>
      <div class="info-row">
        <span>实际持仓 / 总交易日</span
        ><strong
          >{{ active.metrics.holding_sessions ?? "—" }} /
          {{ active.metrics.sessions ?? "—" }}</strong
        >
      </div>
      <div class="info-row">
        <span>平均资金利用率</span
        ><strong>{{ percent(active.metrics.capital_utilization) }}</strong>
      </div>
    </div>
    <p class="small muted">
      胜率及盈亏比只使用已实现卖出净损益；没有平仓样本时显示“—”。风险约束买入持有与各策略使用相同仓位限制，不等同于全仓买入持有。
    </p>
    <Chart :option="drawdown" :height="220" /><el-tabs
      ><el-tab-pane label="模拟成交"
        ><DataTable
          :rows="active.fills || []"
          :columns="[
            { key: 'trade_date', label: '估计成交日' },
            { key: 'signal_date', label: '信号日' },
            { key: 'symbol', label: 'ETF' },
            {
              key: 'side',
              label: '方向',
              format: (v) => (v === 'BUY' ? '买入' : '卖出'),
            },
            { key: 'quantity', label: '份额' },
            { key: 'price', label: '估计价格' },
            { key: 'fee', label: '费用', format: money },
            { key: 'realized_pnl', label: '已实现盈亏', format: money },
          ]" /></el-tab-pane
      ><el-tab-pane label="订单与拒单"
        ><DataTable
          :rows="active.orders || []"
          :columns="[
            { key: 'trade_date', label: '交易日' },
            { key: 'symbol', label: 'ETF' },
            { key: 'side', label: '方向' },
            { key: 'status', label: '状态' },
            { key: 'reason', label: '约束原因', width: 280 },
          ]" /></el-tab-pane
      ><el-tab-pane label="假设与复现"
        ><div class="text-output">
          {{
            JSON.stringify(
              {
                execution_model: active.execution_model,
                dividend_basis: active.dividend_basis,
                input_sha256: active.input_sha256,
                assumptions: active.assumptions,
              },
              null,
              2,
            )
          }}
        </div></el-tab-pane
      ></el-tabs
    >
  </section>
</template>
