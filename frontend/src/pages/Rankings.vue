<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  api,
  guarded,
  names,
  percent,
  number,
  money,
  statuses,
  time,
  type Row,
} from "../api";
import Chart from "../components/Chart.vue";
import DataTable from "../components/DataTable.vue";

const mode = ref("history"),
  horizon = ref(1),
  busy = ref(false);
const data = ref<Row>({}),
  fusion = ref<Row>({}),
  models = ref<Row[]>([]);
const compareA = ref(""),
  compareB = ref("");
let comparisonRequest = 0;
const modes = [
  {
    id: "history",
    label: "历史回测",
    note: "使用历史行情重放策略。历史研究结果不能冒充当时真实生成的预测。",
  },
  {
    id: "oos",
    label: "严格样本外回测",
    note: "只展示独立测试区间及其可核实成绩；训练拟合、参数选择和校准区间不能进入测试。",
  },
  {
    id: "forward",
    label: "实时前瞻性验证",
    note: "仅使用真实时间生成、及时冻结且已经到期的预测；不会补造历史预测增加样本。",
  },
  {
    id: "paper",
    label: "实时模拟交易",
    note: "仅使用上线后虚拟账户账本和实际结算净值；与历史回测严格分开。",
  },
];
const description = computed(
  () => modes.find((item) => item.id === mode.value)?.note,
);
const sampleLabel = computed(() =>
  mode.value === "forward"
    ? "有效预测样本"
    : mode.value === "paper"
      ? "实际结算日"
      : "回测交易日",
);
const comparisonContext = computed(() => {
  const value = data.value.comparability || {};
  if (typeof value === "string") return [{ label: "可比口径", value }];
  return [
    {
      label: "共同研究区间",
      value: value.start && value.end ? value.start + " — " + value.end : null,
    },
    {
      label: "每组虚拟本金",
      value:
        value.initial_capital == null
          ? null
          : money(value.initial_capital) + " 元",
    },
    {
      label: "预测跨度",
      value: value.horizon == null ? null : value.horizon + " 个交易日",
    },
    { label: "比较口径", value: value.basis },
  ].filter((row) => row.value != null);
});
const rows = computed<Row[]>(() => data.value.rows || []);
const curves = computed<Row[]>(() => data.value.curves || []);
const calibration = computed<Row[]>(() => data.value.calibration || []);
const limitations = computed<string[]>(() => {
  const value = data.value.limitations;
  return Array.isArray(value) ? value : value ? [String(value)] : [];
});
const comparison = computed(() => {
  const a = rows.value.find((row) => row.strategy_id === compareA.value);
  const b = rows.value.find((row) => row.strategy_id === compareB.value);
  if (
    a?.total_return == null ||
    b?.total_return == null ||
    compareA.value === compareB.value
  )
    return null;
  return Number(a.total_return) - Number(b.total_return);
});
const historicalModels = computed(() =>
  models.value.filter(
    (row) => row.nature === "REAL" && row.horizon === horizon.value,
  ),
);

/** 按真实日期合并横轴，缺少某天数据的策略保留断点，禁止补造零收益。 */
function metricChart(metric: string, cumulativeDrawdown = false) {
  const dates = [
    ...new Set(
      curves.value.flatMap((curve) =>
        (curve.points || []).map((point: Row) => point.date),
      ),
    ),
  ].sort();
  return {
    tooltip: { trigger: "axis", valueFormatter: percent },
    legend: { type: "scroll", top: 0 },
    grid: { left: 65, right: 20, top: 50, bottom: 45 },
    xAxis: { type: "category", data: dates, boundaryGap: false },
    yAxis: {
      type: "value",
      axisLabel: {
        formatter: (value: number) => (value * 100).toFixed(1) + "%",
      },
    },
    series: curves.value.map((curve) => {
      const points = new Map<string, number | null>();
      let maximum: number | null = null;
      for (const point of curve.points || []) {
        const value = point[metric];
        if (value == null || !Number.isFinite(Number(value))) {
          points.set(point.date, null);
          continue;
        }
        if (cumulativeDrawdown)
          maximum = Math.max(maximum ?? 0, Math.abs(Number(value)));
        points.set(
          point.date,
          cumulativeDrawdown ? -(maximum ?? 0) : Number(value),
        );
      }
      return {
        name: curve.label || names[curve.strategy_id] || curve.strategy_id,
        type: "line",
        showSymbol: dates.length < 12,
        connectNulls: false,
        data: dates.map((date) => points.get(String(date)) ?? null),
      };
    }),
  };
}
/** 只有接口确实提供有效数值才渲染图表，避免空数组呈现“零风险”。 */
function hasPoints(metric: string) {
  return curves.value.some((curve) =>
    (curve.points || []).some(
      (point: Row) =>
        point[metric] != null && Number.isFinite(Number(point[metric])),
    ),
  );
}
const returnChart = computed(() => metricChart("total_return"));
const drawdownChart = computed(() => metricChart("drawdown", true));
const accuracyChart = computed(() => metricChart("accuracy"));
const calibrationChart = computed(() => {
  const groups = [...new Set(calibration.value.map((row) => row.strategy_id))];
  return {
    tooltip: {
      trigger: "item",
      formatter: (point: Row) =>
        point.seriesName +
        "：预测 " +
        percent(point.value?.[0]) +
        " / 实际 " +
        percent(point.value?.[1]) +
        (point.data?.samples != null
          ? " / " + point.data.samples + "个样本"
          : ""),
    },
    legend: { type: "scroll", top: 0 },
    grid: { left: 65, right: 20, top: 50, bottom: 55 },
    xAxis: {
      type: "value",
      min: 0,
      max: 1,
      name: "平均预测上涨概率",
      nameLocation: "middle",
      nameGap: 30,
      axisLabel: {
        formatter: (value: number) => (value * 100).toFixed(0) + "%",
      },
    },
    yAxis: {
      type: "value",
      min: 0,
      max: 1,
      name: "实际上涨频率",
      axisLabel: {
        formatter: (value: number) => (value * 100).toFixed(0) + "%",
      },
    },
    series: [
      {
        name: "理想校准参考线",
        type: "line",
        data: [
          [0, 0],
          [1, 1],
        ],
        showSymbol: false,
        lineStyle: { type: "dashed", color: "#a7b3bd" },
        silent: true,
      },
      ...groups.map((id) => ({
        name: names[id] || id,
        type: "line",
        lineStyle: { width: 0 },
        symbolSize: 9,
        data: calibration.value
          .filter(
            (row) =>
              row.strategy_id === id &&
              row.predicted_probability != null &&
              row.observed_frequency != null,
          )
          .map((row) => ({
            value: [
              Number(row.predicted_probability),
              Number(row.observed_frequency),
            ],
            samples: row.samples,
          })),
      })),
    ],
  };
});

/** 切换证据类别时清空旧结果，防止新请求失败后仍显示上一类别的收益。 */
async function loadComparison() {
  const requestId = ++comparisonRequest;
  busy.value = true;
  data.value = {};
  await guarded(async () => {
    const response = await api(
      "/rankings?" +
        new URLSearchParams({
          mode: mode.value,
          horizon: String(horizon.value),
        }),
    );
    if (requestId !== comparisonRequest) return;
    data.value = response;
    compareA.value = rows.value[0]?.strategy_id || "";
    compareB.value =
      rows.value.find((row) => row.strategy_id === "buy_hold")?.strategy_id ||
      rows.value[1]?.strategy_id ||
      "";
  });
  if (requestId === comparisonRequest) busy.value = false;
}
/** 融合验证门槛只采用服务端真实到期日期计数，不能用预测行数替换。 */
async function loadContext() {
  await guarded(async () => {
    [models.value, fusion.value] = await Promise.all([
      api("/models"),
      api("/fusion/evaluation?horizon=" + horizon.value),
    ]);
  });
}
/** 同时切换预测跨度及其独立的到期证据。 */
async function changeHorizon() {
  await Promise.all([loadComparison(), loadContext()]);
}
/** 当前模拟账户只消费1日信号，不能将同一曲线解释为独立5日策略。 */
async function changeMode() {
  if (mode.value === "paper" && horizon.value !== 1) {
    horizon.value = 1;
    await changeHorizon();
    return;
  }
  await loadComparison();
}
onMounted(changeHorizon);
</script>
<template>
  <div class="note warning-note">
    预测正确率、概率校准和扣费收益衡量不同问题。低回撤必须结合成交次数、持仓天数和资金利用率判断；样本不足时无法得出可靠结论。
  </div>
  <section class="panel">
    <div class="panel-head">
      <h2>按证据类型比较</h2>
      <el-button @click="changeHorizon">刷新</el-button>
    </div>
    <div class="toolbar">
      <el-radio-group
        v-model="mode"
        @change="changeMode"
        aria-label="比较证据类型"
        ><el-radio-button
          v-for="item in modes"
          :key="item.id"
          :value="item.id"
          >{{ item.label }}</el-radio-button
        ></el-radio-group
      >
      <el-radio-group
        v-if="mode !== 'paper'"
        v-model="horizon"
        @change="changeHorizon"
        aria-label="比较预测跨度"
        ><el-radio-button :value="1">1个交易日</el-radio-button
        ><el-radio-button :value="5">5个交易日</el-radio-button></el-radio-group
      >
      <el-tag v-else type="info">模拟账户仅消费1日信号</el-tag>
    </div>
    <div class="note" data-testid="ranking-evidence-mode">
      {{ description }}
    </div>
    <div v-loading="busy">
      <DataTable
        :rows="rows"
        :columns="[
          {
            key: 'strategy_id',
            label: '模型 / 策略',
            format: (v, row) => row.label || names[v] || v,
            width: 175,
          },
          {
            key: 'total_return',
            label: '累计扣费收益',
            format: percent,
            width: 140,
          },
          { key: 'max_drawdown', label: '最大回撤', format: percent },
          { key: 'accuracy', label: '方向正确率', format: percent },
          { key: 'samples', label: sampleLabel },
          { key: 'trade_count', label: '成交笔数' },
          { key: 'capital_utilization', label: '资金利用率', format: percent },
          {
            key: 'excess_return',
            label: '较约束买入持有超额',
            format: percent,
            width: 175,
          },
          { key: 'fees', label: '手续费（元）', format: money, width: 140 },
          {
            key: 'slippage_cost',
            label: '滑点及价差成本（元）',
            format: money,
            width: 180,
          },
          {
            key: 'activity_status',
            label: '交易状态',
            format: (v) => statuses[v] || v || '不适用 / 未记录',
            width: 165,
          },
          { key: 'reason', label: '未评估说明', width: 220 },
        ]"
        empty="该证据类别暂无可验证结果。样本不足，无法得出可靠结论。"
      />
    </div>
    <div v-if="data.comparability" class="comparison-context">
      <div v-for="item in comparisonContext" :key="item.label" class="info-row">
        <span>{{ item.label }}</span
        ><strong>{{ item.value }}</strong>
      </div>
      <el-collapse v-if="data.comparability.risk"
        ><el-collapse-item title="已冻结的资金、成本和风控参数">
          <pre class="text-output">{{
            JSON.stringify(data.comparability.risk, null, 2)
          }}</pre>
        </el-collapse-item></el-collapse
      >
    </div>
    <p v-for="(item, index) in limitations" :key="index" class="small muted">
      {{ item }}
    </p>
    <div
      v-if="rows.length > 1 && ['history', 'oos'].includes(mode)"
      class="toolbar"
      style="margin-top: 20px"
    >
      <span class="small">收益差异</span>
      <el-select v-model="compareA" aria-label="收益比较模型A"
        ><el-option
          v-for="row in rows"
          :key="row.strategy_id"
          :label="row.label || names[row.strategy_id] || row.strategy_id"
          :value="row.strategy_id"
      /></el-select>
      <span>−</span
      ><el-select v-model="compareB" aria-label="收益比较模型B"
        ><el-option
          v-for="row in rows"
          :key="row.strategy_id"
          :label="row.label || names[row.strategy_id] || row.strategy_id"
          :value="row.strategy_id"
      /></el-select>
      <strong>{{ percent(comparison) }}</strong
      ><span class="small muted">收益率之差；仅在上述共同口径下比较</span>
    </div>
  </section>
  <div class="grid-equal">
    <section class="panel">
      <div class="panel-head"><h2>累计收益曲线</h2></div>
      <Chart v-if="hasPoints('total_return')" :option="returnChart" />
      <div v-else class="empty">
        暂无该类别的真实收益曲线，不以其他类别替代。
      </div>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>累计最大回撤曲线</h2></div>
      <Chart v-if="hasPoints('drawdown')" :option="drawdownChart" />
      <div v-else class="empty">暂无可计算回撤的净值序列。</div>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>预测正确率随时间变化</h2></div>
      <Chart v-if="hasPoints('accuracy')" :option="accuracyChart" />
      <div v-else class="empty">样本不足，无法绘制有效正确率曲线。</div>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>上涨概率校准</h2></div>
      <Chart
        v-if="
          calibration.some(
            (row) =>
              row.predicted_probability != null &&
              row.observed_frequency != null,
          )
        "
        :option="calibrationChart"
      />
      <div v-else class="empty">
        暂无到期概率样本。LLM情绪与实验融合分数不作为概率校准。
      </div>
    </section>
  </div>
  <section v-if="calibration.length" class="panel">
    <div class="panel-head"><h2>校准分桶样本</h2></div>
    <DataTable
      :rows="calibration"
      :columns="[
        { key: 'strategy_id', label: '模型', format: (v) => names[v] || v },
        { key: 'bucket', label: '概率分桶' },
        {
          key: 'predicted_probability',
          label: '平均预测概率',
          format: percent,
        },
        { key: 'observed_frequency', label: '实际上涨频率', format: percent },
        { key: 'samples', label: '有效样本数' },
      ]"
    />
  </section>
  <section v-if="mode === 'oos'" class="panel">
    <div class="panel-head">
      <h2>模型工件独立测试（全版本，不对应上方冻结实验）</h2>
      <el-tag type="info">模型概率评价 · 非模拟账户收益</el-tag>
    </div>
    <p class="small muted" style="margin-bottom: 12px">
      展示当前预测跨度下全部真实模型工件；工件ID标识具体版本，特征版本单独列出。此表不代表上方冻结实验实际使用的模型。
    </p>
    <DataTable
      :rows="historicalModels"
      :columns="[
        { key: 'symbol', label: 'ETF' },
        { key: 'algorithm', label: '算法' },
        { key: 'id', label: '模型工件ID / 版本', width: 280 },
        {
          key: 'trained_at',
          label: '生成时间（北京）',
          format: time,
          width: 190,
        },
        {
          key: 'metrics',
          label: '样本外正确率',
          format: (v) => percent(v?.test?.accuracy),
        },
        {
          key: 'metrics',
          label: 'Brier',
          format: (v) => number(v?.test?.brier_score),
        },
        {
          key: 'metrics',
          label: '测试样本数',
          format: (v) =>
            v?.test?.samples == null ? '—' : String(v.test.samples),
        },
        {
          key: 'training_range',
          label: '测试区间',
          format: (v) =>
            v?.test ? v.test.start + ' — ' + v.test.end : '历史未记录',
          width: 230,
        },
        { key: 'feature_version', label: '特征版本' },
      ]"
    />
  </section>
  <section class="panel">
    <div class="panel-head">
      <h2>正式融合效果检验</h2>
      <el-tag type="warning">{{
        fusion.status === "EVALUATED" ? "已有样本外结果" : "等待前瞻证据"
      }}</el-tag>
    </div>
    <div v-if="fusion.status !== 'EVALUATED'" class="empty">
      <strong
        >{{ fusion.available_dates ?? "—" }} /
        {{ fusion.required_dates ?? 150 }} 个到期信号交易日</strong
      >样本不足，无法得出可靠结论。足量后按时间划分训练、验证、测试；权重在验证段学习，最后独立测试。
    </div>
    <div v-else class="text-output">{{ JSON.stringify(fusion, null, 2) }}</div>
  </section>
</template>
