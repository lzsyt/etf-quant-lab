<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, guarded, money, percent, time, names, type Row } from "../api";
import Chart from "../components/Chart.vue";
import DataTable from "../components/DataTable.vue";
const account = ref("lightgbm"),
  data = ref<Row>({}),
  snapshots = ref<Row[]>([]),
  positions = ref<Row[]>([]),
  busy = ref(false);
const current = computed(() => data.value.account || {});
const state = computed(() => {
  if (typeof current.value.state === "object") return current.value.state || {};
  try {
    return JSON.parse(current.value.state || "{}");
  } catch {
    return {};
  }
});
const drawdown = computed(() => {
  const values = snapshots.value
    .map((v) => v.details?.drawdown)
    .filter((value) => value != null && Number.isFinite(Number(value)))
    .map(Number);
  return values.length ? Math.max(...values) : null;
});
/** 缺失资金字段不能先转成0，否则会展示虚假的亏损或零风险。 */
const balance = computed(() => {
  const equity = current.value.equity;
  const cash = current.value.cash;
  const capital = current.value.initial_capital;
  const previous = data.value.previous_equity?.[0] ?? capital;
  return {
    exposure:
      equity == null || cash == null ? null : Number(equity) - Number(cash),
    pnl:
      equity == null || capital == null
        ? null
        : Number(equity) - Number(capital),
    totalReturn:
      equity == null || Number(capital) <= 0
        ? null
        : Number(equity) / Number(capital) - 1,
    dailyPnl:
      equity == null || previous == null
        ? null
        : Number(equity) - Number(previous),
  };
});
const latest = computed(() => data.value.latest_prediction?.[0]);
const chart = computed(() => ({
  tooltip: { trigger: "axis" },
  grid: { left: 65, right: 22, top: 25, bottom: 40 },
  xAxis: {
    type: "category",
    boundaryGap: false,
    data: snapshots.value.map((v) => v.trade_date),
    axisLine: { lineStyle: { color: "#e1e9f0" } },
    axisLabel: { color: "#92a1ad" },
  },
  yAxis: {
    type: "value",
    scale: true,
    splitLine: { lineStyle: { color: "#eef2f6" } },
  },
  series: [
    {
      name: "模拟净值（元）",
      type: "line",
      showSymbol: snapshots.value.length < 10,
      data: snapshots.value.map((v) => v.equity),
      lineStyle: { width: 2.5 },
      areaStyle: { color: "#087f8c", opacity: 0.06 },
    },
  ],
}));
async function load() {
  busy.value = true;
  await guarded(async () => {
    const [d, s, p] = await Promise.all([
      api("/dashboard?account=" + account.value),
      api("/paper/snapshots?account=" + account.value),
      api("/paper/positions?account=" + account.value),
    ]);
    data.value = d;
    snapshots.value = s;
    positions.value = p;
  });
  busy.value = false;
}
onMounted(load);
</script>
<template>
  <div v-loading="busy">
    <div class="toolbar">
      <el-select v-model="account" @change="load" aria-label="选择模拟账户"
        ><el-option
          v-for="(name, id) in names"
          :key="id"
          :label="name"
          :value="id" /></el-select
      ><el-tag effect="plain">上线后前瞻模拟</el-tag
      ><span class="small muted">各策略独立使用100,000元虚拟本金</span
      ><el-button @click="load">刷新</el-button>
    </div>
    <div class="stats">
      <div class="stat">
        <div class="stat-label">模拟总资产 / CNY</div>
        <div class="stat-value accent">{{ money(current.equity) }}</div>
        <div class="stat-note">{{ names[account] }} · 独立账户</div>
      </div>
      <div class="stat">
        <div class="stat-label">虚拟现金</div>
        <div class="stat-value">{{ money(current.cash) }}</div>
        <div class="stat-note">持仓及应收 {{ money(balance.exposure) }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">累计盈亏</div>
        <div
          class="stat-value"
          :class="
            balance.pnl == null
              ? ''
              : balance.pnl >= 0
                ? 'positive'
                : 'negative'
          "
        >
          {{ money(balance.pnl) }}
        </div>
        <div class="stat-note">
          累计收益
          {{ percent(balance.totalReturn) }}
        </div>
      </div>
      <div class="stat">
        <div class="stat-label">历史最大回撤</div>
        <div class="stat-value">{{ percent(drawdown) }}</div>
        <div class="stat-note">
          风险锁定 {{ current.locked ? "已触发" : "未触发" }}
        </div>
      </div>
    </div>
    <div class="grid-two">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>前瞻模拟资金曲线</h2>
            <p>不包含历史回测净值</p>
          </div>
          <el-tag type="info" effect="plain"
            >{{ snapshots.length }} 个结算日</el-tag
          >
        </div>
        <Chart v-if="snapshots.length" :option="chart" />
        <div v-else class="empty">
          <strong>等待首个交易日结算</strong
          >未来的预测与盈亏将在真实交易日逐日积累。
        </div>
      </section>
      <section class="panel">
        <div class="panel-head">
          <h2>运行概况</h2>
          <span class="small status-dot">● 后端已连接</span>
        </div>
        <div class="info-row">
          <span>最近结算日</span
          ><strong>{{ current.last_trade_date || "尚未结算" }}</strong>
        </div>
        <div class="info-row">
          <span>当日盈亏</span><strong>{{ money(balance.dailyPnl) }}</strong>
        </div>
        <div class="info-row">
          <span>累计交易费用</span
          ><strong>{{ money(state.cumulative_fees) }}</strong>
        </div>
        <div class="info-row">
          <span>最近预测日期</span
          ><strong>{{ latest?.signal_date || "尚未生成" }}</strong>
        </div>
        <div class="info-row">
          <span>预测冻结时间</span
          ><strong>{{ time(latest?.created_at) }}</strong>
        </div>
        <div class="info-row">
          <span>已启用策略（非执行证明）</span
          ><strong>{{ data.enabled_strategies?.length || 0 }} 组</strong>
        </div>
        <div class="info-row">
          <span>数据库状态</span
          ><strong>{{ data.health?.database || "检查中" }}</strong>
        </div>
      </section>
    </div>
    <section class="panel">
      <div class="panel-head">
        <h2>当前模拟持仓</h2>
        <router-link to="/paper" class="small status-dot"
          >查看模拟交易 →</router-link
        >
      </div>
      <DataTable
        :rows="positions"
        :columns="[
          { key: 'symbol', label: 'ETF代码' },
          { key: 'quantity', label: '持有份额' },
          { key: 'cost_basis', label: '持仓成本（元）', format: money },
        ]"
        empty="当前为空仓，未产生模拟持仓"
      />
    </section>
    <section class="panel">
      <div class="panel-head">
        <h2>真实行情覆盖</h2>
        <router-link to="/market" class="small status-dot"
          >行情与质量 →</router-link
        >
      </div>
      <DataTable
        :rows="(data.market || []).filter((v) => v.nature === 'REAL')"
        :columns="[
          { key: 'symbol', label: 'ETF' },
          { key: 'source', label: '来源' },
          { key: 'count', label: '日线条数' },
          { key: 'last_date', label: '最新交易日' },
          {
            key: 'fetched_at',
            label: '获取时间（北京）',
            format: time,
            width: 190,
          },
        ]"
      />
    </section>
    <div class="note">
      研究平台仅进行历史回测和虚拟资金模拟。未来多日准确率与盈利情况尚需实际交易日验证；情绪分数不等于上涨概率。
    </div>
  </div>
</template>
