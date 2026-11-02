<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  api,
  guarded,
  time,
  money,
  submitTask,
  statuses,
  percent,
  type Row,
} from "../api";
import { ElMessage } from "element-plus";
import Chart from "../components/Chart.vue";
import DataTable from "../components/DataTable.vue";
const instruments = ref<Row[]>([]),
  bars = ref<Row[]>([]),
  indicators = ref<Row[]>([]),
  quality = ref<Row[]>([]);
const qualityReport = ref<Row>({}),
  qualityDetail = ref<Row | null>(null),
  qualityDrawer = ref(false);
const symbol = ref("510300"),
  nature = ref("REAL"),
  provider = ref("SINA"),
  busy = ref(false);
const dates = ref([
  "2026-01-01",
  new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Shanghai" }),
]);
const selected = computed(() =>
  instruments.value.find((v) => v.symbol === symbol.value),
);
const crossSourceEvidence = computed(
  () =>
    qualityReport.value.items?.find((v: Row) => v.symbol === symbol.value)
      ?.cross_source_evidence,
);
const chart = computed(() => {
  const index = new Map(indicators.value.map((v) => [v.trade_date, v]));
  const dateList = bars.value.map((v) => v.trade_date);
  return {
    tooltip: { trigger: "axis", axisPointer: { type: "cross" } },
    legend: { top: 0, data: ["日K", "MA20", "MA60", "成交量"] },
    grid: [
      { left: 65, right: 25, top: 42, height: "55%" },
      { left: 65, right: 25, top: "74%", height: "12%" },
    ],
    xAxis: [
      { type: "category", data: dateList, boundaryGap: true },
      {
        type: "category",
        data: dateList,
        gridIndex: 1,
        axisLabel: { show: false },
      },
    ],
    yAxis: [
      { scale: true, splitLine: { lineStyle: { color: "#edf2f6" } } },
      {
        scale: true,
        gridIndex: 1,
        splitNumber: 2,
        axisLabel: {
          formatter: (v) =>
            v >= 1e8
              ? (v / 1e8).toFixed(0) + "亿"
              : v >= 1e4
                ? (v / 1e4).toFixed(0) + "万"
                : v,
        },
      },
    ],
    dataZoom: [
      { type: "inside", xAxisIndex: [0, 1] },
      { type: "slider", xAxisIndex: [0, 1], bottom: 0, height: 18 },
    ],
    series: [
      {
        name: "日K",
        type: "candlestick",
        data: bars.value.map((v) => [v.open, v.close, v.low, v.high]),
        itemStyle: {
          color: "#e77976",
          color0: "#43ae98",
          borderColor: "#d65e5b",
          borderColor0: "#369d88",
        },
      },
      ...["ma20", "ma60"].map((name, i) => ({
        name: i ? "MA60" : "MA20",
        type: "line",
        showSymbol: false,
        data: bars.value.map((v) => index.get(v.trade_date)?.[name] ?? null),
        lineStyle: { width: 1.5 },
      })),
      {
        name: "成交量",
        type: "bar",
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: bars.value.map((v) => v.volume),
        itemStyle: { color: "#bdd7e0" },
      },
    ],
  };
});
async function load() {
  busy.value = true;
  await guarded(async () => {
    const [b, i, q, report] = await Promise.all([
      api(
        "/market/bars?" +
          new URLSearchParams({
            symbol: symbol.value,
            nature: nature.value,
            start: dates.value[0],
            end: dates.value[1],
          }),
      ),
      api(
        "/market/indicators?symbol=" + symbol.value + "&nature=" + nature.value,
      ),
      api("/market/quality"),
      api("/market/quality-report"),
    ]);
    bars.value = b;
    indicators.value = i.indicators;
    quality.value = q;
    qualityReport.value = report;
  });
  busy.value = false;
}
/** 展开服务端保存的质检结果，不用前端补齐缺失交易日或修正价格。 */
function inspectQuality(row: Row) {
  qualityDetail.value = row;
  qualityDrawer.value = true;
}
/** 同步来源和数据性质分别过滤，未核验CSV不能混入真实数据报告。 */
function matchesNature(row: Row) {
  const actualNature =
    row.report?.nature ||
    (row.source === "DEMO"
      ? "DEMO"
      : row.source === "CSV"
        ? "CSV_UNVERIFIED"
        : "REAL");
  return row.symbol === symbol.value && actualNature === nature.value;
}
async function sync() {
  await guarded(async () => {
    const job = await submitTask("MARKET_SYNC", {
      symbol: symbol.value,
      provider: provider.value,
      start: dates.value[0],
      end: dates.value[1],
      price_basis: "RAW",
    });
    ElMessage.success("同步任务已提交：" + job.id.slice(0, 8));
  });
}
onMounted(async () => {
  await guarded(async () => {
    instruments.value = await api("/market/instruments");
  });
  await load();
});
</script>
<template>
  <div class="panel">
    <div class="toolbar">
      <el-select v-model="symbol" @change="load" aria-label="ETF品种"
        ><el-option
          v-for="v in instruments"
          :key="v.symbol"
          :label="v.symbol + ' · ' + v.name"
          :value="v.symbol" /></el-select
      ><el-date-picker
        v-model="dates"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        @change="load"
      /><el-select v-model="nature" @change="load" aria-label="数据性质"
        ><el-option label="真实行情" value="REAL" /><el-option
          label="明确标记的演示数据"
          value="DEMO" /><el-option
          label="未核验CSV"
          value="CSV_UNVERIFIED" /></el-select
      ><el-button @click="load">查询</el-button>
    </div>
    <div class="panel-head">
      <div>
        <h2>
          {{ selected?.name || symbol }}
          <span class="small muted">{{ symbol }}</span>
        </h2>
        <p>
          {{ selected?.tracking_index }} · {{ selected?.exchange }} · T+{{
            selected?.settlement_days
          }}
          · {{ selected?.lot_size }}份/手
        </p>
      </div>
      <el-tag :type="nature === 'REAL' ? 'success' : 'warning'"
        >{{ statuses[nature] }} / RAW未复权</el-tag
      >
    </div>
    <div v-loading="busy">
      <Chart v-if="bars.length" :option="chart" :height="460" />
      <div v-else class="empty">
        该日期范围暂无对应性质的行情，请先同步或调整范围。
      </div>
    </div>
    <div class="small muted" v-if="bars.length">
      {{ bars.length }}条 · 来源 {{ bars[bars.length - 1].source }} · 最新获取
      {{ time(bars[bars.length - 1].fetched_at) }}
    </div>
  </div>
  <div class="note warning-note">
    当前RAW行情只表示价格收益。分红和拆分仅在来源核实后计入，未取得的复权资料不会被补造。159915的2021-02-08数据源缺口已登记。
  </div>
  <section class="panel">
    <div class="panel-head">
      <h2>证券元数据与适用规则</h2>
      <span class="small muted"
        >核验时间 {{ time(selected?.verified_at) }}</span
      >
    </div>
    <div class="form-grid">
      <div class="info-row">
        <span>证券名称 / 代码</span
        ><strong>{{ selected?.name || "—" }} / {{ symbol }}</strong>
      </div>
      <div class="info-row">
        <span>交易所 / 跟踪指数</span
        ><strong
          >{{ selected?.exchange || "—" }} /
          {{ selected?.tracking_index || "—" }}</strong
        >
      </div>
      <div class="info-row">
        <span>ETF类型</span
        ><strong>{{
          selected?.etf_type === "STOCK_ETF"
            ? "股票型ETF"
            : selected?.etf_type || "待元数据核验"
        }}</strong>
      </div>
      <div class="info-row">
        <span>交易单位 / 最小价位</span
        ><strong
          >{{ selected?.lot_size ?? "—" }} 份 /
          {{ selected?.tick_size ?? "—" }} 元</strong
        >
      </div>
      <div class="info-row">
        <span>卖出限制 / 涨跌幅限制</span
        ><strong
          >T+{{ selected?.settlement_days ?? "—" }} /
          {{ percent(selected?.limit_rate) }}</strong
        >
      </div>
      <div class="info-row">
        <span>规则适用起始日</span
        ><strong>{{ selected?.rule_valid_from || "—" }}</strong>
      </div>
    </div>
    <div class="tag-row">
      <a
        v-if="selected?.source_url"
        :href="selected.source_url"
        target="_blank"
        rel="noopener noreferrer"
        class="small status-dot"
        >核对证券信息原文 ↗</a
      ><a
        v-if="selected?.rules_source_url"
        :href="selected.rules_source_url"
        target="_blank"
        rel="noopener noreferrer"
        class="small status-dot"
        >核对交易规则原文 ↗</a
      >
    </div>
  </section>
  <section class="panel">
    <div class="panel-head">
      <h2>正式研究行情质量报告</h2>
      <span class="small muted">{{ time(qualityReport.generated_at) }}</span>
    </div>
    <DataTable
      :rows="(qualityReport.items || []).filter((row) => row.symbol === symbol)"
      @row-click="inspectQuality"
      :columns="[
        { key: 'symbol', label: 'ETF' },
        {
          key: 'coverage',
          label: '真实日线 / 交易日',
          format: (v) => (v ? String(v.rows) + ' / ' + String(v.dates) : '—'),
          width: 170,
        },
        {
          key: 'metadata_issues',
          label: '元数据问题数',
          format: (v) => (Array.isArray(v) ? String(v.length) : '未检查'),
        },
        {
          key: 'duplicate_keys',
          label: '重复K线组数',
          format: (v) => (Array.isArray(v) ? String(v.length) : '未检查'),
        },
        {
          key: 'missing_dates',
          label: '缺失交易日数',
          format: (v) => (Array.isArray(v) ? String(v.length) : '未检查'),
        },
        { key: 'invalid_prices', label: '异常价格数' },
        { key: 'nonfinal_bars', label: '未完整收盘记录数', width: 160 },
        { key: 'demo_rows_excluded', label: '已排除非真实记录', width: 160 },
        {
          key: 'cross_source_status',
          label: '跨源对比',
          format: (v) =>
            v === 'NOT_VERIFIED_SINGLE_SOURCE'
              ? '单一来源，尚未交叉验证'
              : v === 'CHECKED_NO_DIFFERENCE_OVER_1_PERCENT'
                ? '未发现超过1%的差异'
                : v === 'SAMPLED_VERIFIED_WITH_CORRECTIONS'
                  ? '抽样核验通过，已修正异常量额'
                  : v === 'SAMPLED_VERIFIED'
                    ? '抽样核验通过（非全历史）'
                    : v === 'ANOMALY'
                      ? '存在异常差异'
                      : v || '未检查',
          width: 220,
        },
      ]"
    />
    <div v-if="crossSourceEvidence" class="note" aria-label="跨源抽样证据">
      <div>
        {{ crossSourceEvidence.provider || "来源未记录" }} · 本ETF
        {{ crossSourceEvidence.sample_count ?? "未记录" }} 个交易日 ·
        {{ crossSourceEvidence.start_date || "起日未记录" }} ～
        {{ crossSourceEvidence.end_date || "截止日未记录" }}
      </div>
      <div>
        {{ crossSourceEvidence.scope || "仅核验所列样本，不代表全历史验证" }}
      </div>
      <div>
        {{
          crossSourceEvidence.price_match === true
            ? "抽样OHLC一致"
            : crossSourceEvidence.price_match === false
              ? "抽样OHLC存在差异"
              : "价格比对结论未记录"
        }}
        · 核验时间 {{ time(crossSourceEvidence.checked_at) }}
      </div>
      <div v-if="crossSourceEvidence.corrected_dates?.length">
        已修正量额日期：{{ crossSourceEvidence.corrected_dates.join("、") }}。
        原始记录和修正来源保留在证据中。
      </div>
    </div>
    <p class="small muted" style="margin-top: 12px">
      点击结果查看缺失日期、异常和来源详情。只有单一数据源时不宣称跨源核验通过；RAW价格收益不等于含分红总回报。
    </p>
  </section>
  <div class="panel">
    <div class="panel-head">
      <h2>增量同步与数据源</h2>
      <div class="toolbar" style="margin: 0">
        <el-select v-model="provider" aria-label="同步数据源"
          ><el-option label="新浪真实行情" value="SINA" /><el-option
            label="东方财富真实行情"
            value="EASTMONEY" /><el-option
            label="确定性演示数据"
            value="DEMO" /></el-select
        ><el-button type="primary" @click="sync">提交同步任务</el-button>
      </div>
    </div>
    <DataTable
      :rows="quality.filter(matchesNature)"
      @row-click="inspectQuality"
      :columns="[
        { key: 'source', label: '提供商' },
        { key: 'status', label: '状态', format: (v) => statuses[v] || v },
        { key: 'started_at', label: '开始时间', format: time, width: 185 },
        {
          key: 'report',
          label: '质量报告',
          width: 280,
          format: (v) =>
            v?.error
              ? '数据源请求失败'
              : `已保存 ${v?.count ?? '—'} 条 · 缺失 ${v?.missing_dates?.length ?? '—'} 日 · 拒收 ${v?.rejected_count ?? '—'} 条`,
        },
      ]"
      :page-size="5"
    />
  </div>
  <section class="panel">
    <div class="panel-head">
      <h2>日线明细</h2>
      <span class="small muted">成交量统一为份</span>
    </div>
    <DataTable
      :rows="[...bars].reverse()"
      :columns="[
        { key: 'trade_date', label: '交易日' },
        { key: 'open', label: '开盘' },
        { key: 'high', label: '最高' },
        { key: 'low', label: '最低' },
        { key: 'close', label: '收盘' },
        {
          key: 'volume',
          label: '成交量（亿份）',
          format: (v) => (Number(v) / 1e8).toFixed(2),
          width: 150,
        },
        {
          key: 'amount',
          label: '成交额（亿元）',
          format: (v) => (v == null ? '—' : (Number(v) / 1e8).toFixed(2)),
          width: 150,
        },
        { key: 'quality_status', label: '清洗状态' },
      ]"
    />
  </section>
  <el-drawer v-model="qualityDrawer" title="行情质量证据" size="65%">
    <pre class="text-output">{{ JSON.stringify(qualityDetail, null, 2) }}</pre>
  </el-drawer>
</template>
