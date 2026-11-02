<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, money, number, percent, time, type Row } from "../api";
import Chart from "../components/Chart.vue";
import ForecastEvidence from "../components/ForecastEvidence.vue";
import ForecastExecution from "../components/ForecastExecution.vue";
import { label as fundLabel } from "../funds/client";

const route = useRoute(),
  router = useRouter();
const assets = ref<Row[]>([]),
  participants = ref<Row[]>([]);
const catalogBusy = ref(true),
  catalogError = ref("");
const busy = ref(false),
  recordsError = ref(""),
  trendError = ref(""),
  evaluationError = ref("");
const records = ref<Row[]>([]),
  observations = ref<Row[]>([]),
  evaluation = ref<Row>({});
const detail = ref<Row | null>(null),
  detailBusy = ref(false),
  detailError = ref("");
const pagination = ref<Row>({}),
  loadingMore = ref(false);
const latestNews = ref<Row | null>(null),
  newsError = ref("");
let generation = 0,
  detailGeneration = 0;
const asset = computed(
  () => assets.value.find((a) => a.id === route.query.asset) || assets.value[0],
);
const target = computed<Row | undefined>(
  () =>
    asset.value?.targets.find((t: Row) => t.id === route.query.target) ||
    asset.value?.targets[0],
);
const horizon = computed<number>(() =>
  target.value?.horizons.includes(Number(route.query.horizon))
    ? Number(route.query.horizon)
    : target.value?.horizons[0],
);
/** 新目标直接说明价格锚点和期限单位，避免把研究输出当作可成交收益。 */
const targetDescription = computed(() => {
  if (target.value?.id === "FUND_NAV_UP")
    return "以最新已公布单位净值为起点，预测随后第5个有效净值观测是否上涨；平盘计入不上涨。有效观测数不是自然日。";
  if (target.value?.id === "ETF_CLOSE_RETURN")
    return `以基准日收盘价为起点，预测随后第${horizon.value}个交易日收盘价格变化；这是价格变化研究，不等于实际成交收益，不含完整分红总回报。`;
  return "";
});
// 能力矩阵决定可选模式。ETF旧协议可读DEMO，新前瞻及其他资产保持REAL。
const modes = computed<string[]>(() => target.value?.data_modes || ["REAL"]);
const mode = computed(() =>
  modes.value.includes(String(route.query.mode))
    ? String(route.query.mode)
    : "REAL",
);
const context = computed(() => ({
  asset: asset.value?.id,
  target: target.value?.id,
  horizon: String(horizon.value),
  mode: mode.value,
}));
const contextKey = computed(() =>
  asset.value && target.value
    ? new URLSearchParams(context.value).toString()
    : "",
);
const validating = computed(() => route.meta.forecastTab === "validation");
const view = computed(() =>
  ["analysis", "runs"].includes(String(route.query.view))
    ? String(route.query.view)
    : "overview",
);
const evidenceMode = computed(() =>
  route.query.evidence === "latest" ? "latest" : "frozen",
);
const selectedKey = computed(() =>
  typeof route.query.record === "string" ? route.query.record : "",
);
// 路由同时切组合并清除record时，详情watch可能先于列表清空执行。
// 默认记录必须属于当前完整组合，不能把旧资产列表第一条配上新请求参数。
const selected = computed(() =>
  records.value.find(
    (row) =>
      row.asset === context.value.asset &&
      row.target === context.value.target &&
      Number(row.horizon) === Number(context.value.horizon) &&
      row.mode === context.value.mode &&
      (!selectedKey.value || recordKey(row) === selectedKey.value),
  ),
);
const latest = computed(() => {
  const found = new Set<string>();
  return records.value.filter((r) => {
    if (found.has(r.scheme)) return false;
    found.add(r.scheme);
    return true;
  });
});
const frozenAnalysis = computed<Row | null>(
  () =>
    detail.value?.analysis ||
    detail.value?.evidence?.record?.details?.news_snapshot ||
    null,
);
/** 实际结果已存在时不能因为旧表没有status列而误标等待到期。 */
const maturityStatus = computed(
  () =>
    detail.value?.evaluation_status ||
    detail.value?.evaluation?.status ||
    detail.value?.evaluation?.evaluation_status ||
    (detail.value?.actual_return != null ? "EVALUATED" : "PENDING"),
);
const labels: Row = {
  FUND: "场外基金",
  ETF: "场内ETF",
  GOLD: "黄金基准",
  FUND_NAV_OBSERVATIONS: "个有效净值观测",
  NAV_OBSERVATIONS: "个有效净值观测",
  TRADING_DAYS: "个交易日",
  EXCHANGE_TRADING_DAYS: "个交易日",
  SGE_TRADING_DAYS: "个上金所交易日",
  PREDICTOR: "独立预测",
  STRATEGY_EXPERIMENT: "策略实验",
  FUND_LIGHTGBM: "基金 LightGBM",
  FUND_XGBOOST: "基金 XGBoost",
  CLOSE_LIGHTGBM: "ETF LightGBM",
  CLOSE_XGBOOST: "ETF XGBoost",
  GOLD_LIGHTGBM: "黄金 LightGBM",
  GOLD_XGBOOST: "黄金 XGBoost",
  FROZEN_RECORD_AVAILABLE: "原冻结记录可回查",
  NATIVE_CAPABILITIES: "按已支持目标预测",
  EXISTING_EXPLANATION_ONLY: "现有能力仅作解释",
  FROZEN_REFERENCE: "仅使用原冻结关联",
  NOT_LINKED_TO_RECORD: "未绑定本条预测",
  FUND_NAV_CHANGE: "单位净值变化",
  FUND_NAV_UP: "基金短期净值上涨概率",
  GOLD_BENCHMARK_RETURN: "黄金基准收益",
  ETF_FORWARD_OPEN: "下一开盘至再下一开盘",
  ETF_LEGACY_CLOSE: "旧收盘协议",
  ETF_CLOSE_RETURN: "ETF中期收盘价格变化",
  FUTURE_CALENDAR_UNAVAILABLE: "目标日期待交易日历确认，仍按既定交易日数量研究",
  HISTORICAL_ONLY: "仅完成历史检验，待前瞻验证",
  PRICE_RETURN_NOT_TOTAL_RETURN_OR_EXECUTABLE_PROFIT:
    "仅研究价格变化，不含完整分红总回报，也不代表实际成交收益",
  HISTORICAL_DATA_REVISIONS_AND_POINT_IN_TIME_AVAILABILITY_UNVERIFIED:
    "历史行情修订及当时可得性尚未完整核验",
  CORPORATE_ACTION_COVERAGE_UNVERIFIED: "分红、拆分等公司行动覆盖尚未完整核验",
  VALIDATION_BASELINES_NOT_BEATEN: "验证阶段尚未优于简单基线",
  PROBABILITY: "上涨概率",
  GPT: "GPT 独立预测",
  GPT_DISAGREEMENT: "GPT 分歧分析（历史记录，已停用）",
  GPT_DISAGREEMENT_DISABLED: "GPT 分歧分析已关闭，未发起调用",
  DISAGREEMENT_ANALYSIS: "历史分歧分析（不输出综合预测）",
  REVIEW: "分歧研究，不参与预测排名",
  ANALYZED: "分析已保存",
  NOT_APPLICABLE: "不适用，分歧分析不参与到期评分",
  DISABLED: "未启用",
  UNVERIFIED: "待身份核验",
  INVALID: "无效",
  FUND_IDENTITY_NOT_VERIFIED: "旧基金分析尚未通过身份校验",
  FUND_IDENTITY_MISMATCH: "基金身份或材料归属不一致",
  UNCALIBRATED: "尚未校准，待前瞻检验",
  NOT_PROVIDED: "本目标不提供概率",
  RETURN_POINT: "收益终点",
  RETURN_INTERVAL_PROBABILITY: "收益区间与概率",
  MIXED_PREDICTION: "不同输出能力的独立预测",
  STRATEGY_SCORE: "策略实验分数",
  POSITION_SIGNAL: "仓位信号",
  EVENT_SENTIMENT: "事件情绪",
  INDEPENDENT: "独立预测",
  INDEPENDENT_FORECAST: "已接入独立预测",
  INDEPENDENT_PREDICTION_EVIDENCE: "独立预测冻结依据",
  QUANT_INPUT_ONLY: "冻结行情与量化模型依据",
  EXPLANATION: "资料解释",
  COMBINATION: "组合实验",
  NOT_CONNECTED: "尚未接入",
  EXPLANATION_ONLY: "只作解释",
  NOT_SUPPORTED: "不支持",
  NO_RECORDS: "尚无冻结预测",
  NO_PREDICTIONS: "尚无冻结预测",
  NO_MATCHED_SAMPLES: "尚无匹配样本",
  WAITING_MATURITY: "等待到期",
  PENDING_MATURITY: "等待到期",
  PENDING: "等待到期核验",
  AVAILABLE: "可参与预测",
  READY: "已就绪，等待运行",
  NO_RECOMMENDATION: "尚不能判断哪个方案更可靠",
  NO_AUTO_RECOMMENDATION: "尚不能判断哪个方案更可靠",
  LGBM_BASE: "LightGBM",
  XGB_BASE: "XGBoost",
  LGBM_MATCHED_CONTROL: "LightGBM匹配对照",
  FUSION_EXPERIMENT: "融合策略实验",
  lightgbm: "LightGBM",
  xgboost: "XGBoost",
  deepseek: "DeepSeek",
  gpt: "GPT",
  DEEPSEEK: "DeepSeek",
  EVALUATED: "已到期核验",
  ABSTAIN: "信息不足，未给出预测",
  REUSED: "复用相同输入结果",
  COMPLETED: "全部完成",
  PARTIAL: "部分完成",
  BLOCKED: "暂不可执行",
  QUEUED: "等待执行",
  RUNNING: "执行中",
  INSUFFICIENT_DATA: "证据不足，暂不推荐",
  CANDIDATE: "历史候选，等待后续验证",
  RECOMMENDED: "已有后续验证的参考方案",
  NO_BASELINE_ADVANTAGE: "尚未证明优于简单基线",
  fusion: "旧融合实验",
  llm: "事件情绪解释",
};

/** 服务端未知原因保留原文；缺失值不伪装成成功或零收益。 */
function label(value: unknown): string {
  return value == null || value === ""
    ? "未记录"
    : labels[String(value)] || fundLabel(value);
}
/** 推荐状态来自独立选择与留出评价，普通运行成功不等于推荐有效。 */
function recommendationLabel(value: unknown): string {
  const recommendationLabels: Row = {
    AVAILABLE: "已有后续验证的参考方案",
    INSUFFICIENT_SAMPLES: "样本不足，暂不推荐",
    NO_VALIDATED_ADVANTAGE: "尚无经后续验证的基线优势",
    INCOMPARABLE: "样本不可公平比较，暂不推荐",
    INSUFFICIENT_CANDIDATES: "有效独立候选不足，暂不推荐",
    INSUFFICIENT_PIT_EVIDENCE: "实际结果可得时间证据不足",
  };
  return recommendationLabels[String(value)] || label(value);
}
/** 原表来源参与记录身份，避免不同预测域的ID或目标混淆。 */
function recordKey(record: Row): string {
  return `${record.source}:${record.id}`;
}
/** 跨年日历未确认时保留交易日长度，不把未来日期推算成自然日。 */
function targetDateText(record: Row): string {
  if (
    record.target === "ETF_CLOSE_RETURN" &&
    record.calendar_status === "UNCONFIRMED" &&
    !record.target_date
  )
    return `第${record.horizon}个交易日，日期待交易日历确认`;
  return record.target_date || "按后续实际观测确定";
}
/** 页面只格式化真实存在的输出，不从情绪分数推算概率或收益。 */
function resultText(record: Row): string {
  const out = record.output || {};
  if (record.role === "REVIEW")
    return record.analysis?.summary || "已保存分歧说明，请查看冻结依据";
  if (record.output_type === "STRATEGY_SCORE")
    return out.score == null
      ? "未记录实验分数"
      : `实验分数 ${number(out.score)}`;
  if (record.output_type === "POSITION_SIGNAL")
    return out.score == null
      ? "原记录未注明仓位分数"
      : `仓位分数 ${number(out.score)}（不是概率）`;
  if (record.output_type === "EVENT_SENTIMENT")
    return "事件情绪解释，不参与概率排名";
  const parts: string[] = [];
  if (out.point_return != null)
    parts.push(
      `${record.target === "FUND_NAV_CHANGE" ? "预测净值变化" : record.target === "ETF_CLOSE_RETURN" ? "预测价格变化" : "预测收益"} ${percent(out.point_return)}`,
    );
  if (out.probability_up != null)
    parts.push(`上涨概率 ${percent(out.probability_up)}`);
  if (out.interval_lower != null && out.interval_upper != null) {
    parts.push(
      out.interval_unit === "CNY_PER_GRAM"
        ? `价格区间 ${money(out.interval_lower)} ～ ${money(out.interval_upper)} 元/克`
        : `收益区间 ${percent(out.interval_lower)} ～ ${percent(out.interval_upper)}`,
    );
  }
  return parts.join(" · ") || "暂无可展示的数值输出";
}
/** 各指标保留独立分母和量纲，区间宽度是价格、Brier是评分。 */
function metricLines(metrics: Row = {}): string[] {
  const lines: string[] = [];
  if (metrics.mae != null) lines.push(`平均绝对误差 ${percent(metrics.mae)}`);
  if (metrics.zero_return_mae != null)
    lines.push(`零变化基线误差 ${percent(metrics.zero_return_mae)}`);
  if (metrics.brier != null) lines.push(`Brier ${number(metrics.brier)}`);
  if (metrics.direction_accuracy != null)
    lines.push(`方向准确率 ${percent(metrics.direction_accuracy)}`);
  if (metrics.interval_coverage != null)
    lines.push(
      `区间覆盖率 ${percent(metrics.interval_coverage)}（${metrics.interval_samples}条）`,
    );
  if (metrics.interval_mean_width != null)
    lines.push(`平均区间宽度 ${money(metrics.interval_mean_width)} 元/克`);
  return lines;
}
/** 修改筛选时先移除历史记录，防止旧证据跟随新资产或新目标。 */
function change(key: string, value: string) {
  const next: Row = {
    ...context.value,
    view: route.query.view,
    evidence: route.query.evidence,
    record: undefined,
    [key]: value,
  };
  if (key === "asset") {
    next.target = undefined;
    next.horizon = undefined;
    next.mode = "REAL";
  }
  if (key === "target") {
    next.horizon = undefined;
    next.mode = "REAL";
  }
  router.push({ query: next });
}
/** 历史选择写入URL，刷新及后退仍可定位同一冻结记录。 */
function selectRecord(value: string) {
  router.push({
    query: {
      ...context.value,
      view: "analysis",
      evidence: "frozen",
      record: value || undefined,
    },
  });
}
/** 分区和资料时间口径保留在同一资产URL中，不跳回旧研究模块。 */
function changeView(value: string) {
  router.push({ query: { ...route.query, ...context.value, view: value } });
}
/** 最新资讯独立标记，切回冻结依据仍保留用户选定的历史预测。 */
function changeEvidence(value: string) {
  router.push({
    query: {
      ...route.query,
      ...context.value,
      view: "analysis",
      evidence: value,
    },
  });
}
/** 目录失败直接报错，不使用本地假资产或演示结果兜底。 */
async function loadCatalog() {
  catalogBusy.value = true;
  catalogError.value = "";
  try {
    const result = await api("/forecast/assets");
    assets.value = result.assets;
    participants.value = result.participants || [];
    if (!assets.value.length)
      catalogError.value = "资产目录为空，请在系统管理核查数据配置。";
  } catch (e) {
    catalogError.value = message(e);
  } finally {
    catalogBusy.value = false;
  }
}
/** 异常只显示可读消息，不将接口失败转换为合法空结果。 */
function message(error: unknown): string {
  return error instanceof Error ? error.message : "读取失败，请重试";
}
/** 行情沿用各资产原生只读接口，任何失败都不会切换数据模式。 */
async function loadTrend(
  selectedAsset: Row,
  selectedMode: string,
): Promise<Row[]> {
  if (selectedAsset.asset_type === "FUND") {
    const data = await api(
      `/funds/${encodeURIComponent(selectedAsset.code)}/nav`,
    );
    return (data.observations || []).map((r: Row) => ({
      date: r.nav_date,
      value: r.unit_nav,
      source: r.provider,
      quality: r.quality_status,
    }));
  }
  if (selectedAsset.asset_type === "GOLD") {
    const data = await api("/gold/overview");
    return (data.history || []).map((r: Row) => ({
      date: r.date,
      value: r.price,
      source: r.source,
      quality: r.quality,
    }));
  }
  const end = new Date(),
    start = new Date(end);
  start.setFullYear(start.getFullYear() - 1);
  const date = (d: Date) =>
    d.toLocaleDateString("en-CA", { timeZone: "Asia/Shanghai" });
  const data = await api(
    "/market/bars?" +
      new URLSearchParams({
        symbol: selectedAsset.code,
        nature: selectedMode,
        start: date(start),
        end: date(end),
      }),
  );
  return data.map((r: Row) => ({
    date: r.trade_date,
    value: r.close,
    source: r.source,
    quality: r.quality_status,
  }));
}
/** 一次筛选读取同一上下文；快速切换时旧请求没有提交页面状态的资格。 */
async function load() {
  if (!asset.value || !contextKey.value) return;
  const ticket = ++generation,
    query = contextKey.value;
  busy.value = true;
  recordsError.value = "";
  trendError.value = "";
  evaluationError.value = "";
  newsError.value = "";
  records.value = [];
  observations.value = [];
  evaluation.value = {};
  pagination.value = {};
  latestNews.value = null;
  const results = await Promise.allSettled([
    api(`/forecast/records?${query}&limit=50&offset=0`),
    loadTrend(asset.value, mode.value),
    api(`/forecast/evaluations?${query}`),
    api(`/forecast/news?${query}`),
  ]);
  if (ticket !== generation) return;
  if (results[0].status === "fulfilled") {
    records.value = results[0].value.records;
    pagination.value = results[0].value.pagination || {};
  } else recordsError.value = message(results[0].reason);
  if (results[1].status === "fulfilled") observations.value = results[1].value;
  else trendError.value = message(results[1].reason);
  if (results[2].status === "fulfilled") evaluation.value = results[2].value;
  else evaluationError.value = message(results[2].reason);
  if (results[3].status === "fulfilled")
    latestNews.value = results[3].value.analysis || results[3].value;
  else newsError.value = message(results[3].reason);
  busy.value = false;
}
/** 分页只追加当前筛选记录，不隐藏服务端的样本截断信息。 */
async function loadMore() {
  const ticket = generation,
    query = contextKey.value;
  loadingMore.value = true;
  try {
    const data = await api(
      `/forecast/records?${query}&limit=50&offset=${records.value.length}`,
    );
    if (ticket !== generation) return;
    records.value.push(...data.records);
    pagination.value = data.pagination;
  } catch (e) {
    if (ticket === generation) recordsError.value = message(e);
  } finally {
    loadingMore.value = false;
  }
}
/** 详情始终从冻结记录接口读取，绝不使用最新新闻替换历史依据。 */
async function loadDetail() {
  const ticket = ++detailGeneration;
  detail.value = null;
  detailError.value = "";
  detailBusy.value = false;
  const key =
    selectedKey.value || (selected.value ? recordKey(selected.value) : "");
  if (!key || !contextKey.value) return;
  const separator = key.indexOf(":"),
    source = key.slice(0, separator),
    id = key.slice(separator + 1);
  if (
    separator < 1 ||
    !["fund", "gold", "forward", "legacy", "unified"].includes(source) ||
    !id
  ) {
    detailError.value = "历史记录链接无效，请返回最新预测。";
    return;
  }
  detailBusy.value = true;
  try {
    const data = await api(
      `/forecast/records/${source}/${encodeURIComponent(id)}?${contextKey.value}`,
    );
    if (ticket === detailGeneration) detail.value = data;
  } catch (e) {
    if (ticket === detailGeneration) detailError.value = message(e);
  } finally {
    if (ticket === detailGeneration) detailBusy.value = false;
  }
}
const chart = computed(() => ({
  tooltip: { trigger: "axis" },
  grid: { left: 64, right: 24, top: 30, bottom: 55 },
  xAxis: {
    type: "category",
    data: observations.value.map((r) => r.date),
    boundaryGap: false,
  },
  yAxis: {
    type: "value",
    scale: true,
    name:
      asset.value?.asset_type === "FUND"
        ? "单位净值"
        : asset.value?.asset_type === "GOLD"
          ? "元 / 克"
          : "收盘价 / 元",
  },
  dataZoom: [{ type: "inside" }, { type: "slider", bottom: 2, height: 18 }],
  series: [
    {
      type: "line",
      name: "已保存实际行情",
      showSymbol: observations.value.length === 1,
      connectNulls: false,
      data: observations.value.map((r) =>
        r.value == null ? null : Number(r.value),
      ),
    },
  ],
}));
watch(contextKey, () => {
  load();
  loadDetail();
});
watch(
  () =>
    `${selectedKey.value}|${selected.value ? recordKey(selected.value) : ""}`,
  loadDetail,
);
onMounted(loadCatalog);
</script>

<template>
  <section class="forecast-workspace">
    <el-alert
      v-if="catalogError"
      :title="catalogError"
      type="error"
      :closable="false"
      ><el-button @click="loadCatalog">重新读取资产</el-button></el-alert
    >
    <el-skeleton v-if="catalogBusy" :rows="3" animated />
    <template v-else-if="asset && target">
      <section class="panel forecast-context" aria-label="统一预测上下文">
        <div class="forecast-filters">
          <label
            >研究资产<select
              aria-label="研究资产"
              :value="asset.id"
              @change="
                change('asset', ($event.target as HTMLSelectElement).value)
              "
            >
              <option v-for="item in assets" :key="item.id" :value="item.id">
                {{ item.name }} · {{ item.code }}
              </option>
            </select></label
          >
          <label
            >预测目标<select
              aria-label="预测目标"
              :value="target.id"
              @change="
                change('target', ($event.target as HTMLSelectElement).value)
              "
            >
              <option
                v-for="item in asset.targets"
                :key="item.id"
                :value="item.id"
              >
                {{ item.label }}
              </option>
            </select></label
          >
          <label
            >预测期限<select
              aria-label="预测期限"
              :value="horizon"
              @change="
                change('horizon', ($event.target as HTMLSelectElement).value)
              "
            >
              <option
                v-for="value in target.horizons"
                :key="value"
                :value="value"
              >
                {{ value }} {{ label(target.horizon_unit) }}
              </option>
            </select></label
          >
          <label
            >数据模式<select
              aria-label="数据模式"
              :value="mode"
              @change="
                change('mode', ($event.target as HTMLSelectElement).value)
              "
            >
              <option v-for="value in modes" :key="value" :value="value">
                {{ value }} · {{ label(value) }}
              </option>
            </select></label
          >
        </div>
        <div class="forecast-context-note">
          <el-tag :type="mode === 'REAL' ? 'success' : 'warning'"
            >{{ mode }} · {{ label(mode) }}</el-tag
          ><span
            >{{ target.label }} · {{ horizon }}
            {{ label(target.horizon_unit) }}</span
          ><el-button :loading="busy" @click="load">刷新已保存数据</el-button>
        </div>
        <p class="small muted">
          各资产保持原始计价和期限口径；刷新只读取已保存记录，不发起模型调用。
        </p>
        <p v-if="targetDescription" class="small" aria-label="预测目标口径">
          {{ targetDescription }}
        </p>
      </section>
      <template v-if="!validating">
        <nav class="forecast-section-tabs" aria-label="资产研究内容">
          <el-button
            :type="view === 'overview' ? 'primary' : 'default'"
            @click="changeView('overview')"
            >走势与预测</el-button
          >
          <el-button
            :type="view === 'analysis' ? 'primary' : 'default'"
            @click="changeView('analysis')"
            >分析依据与最新资讯</el-button
          >
          <el-button
            :type="view === 'runs' ? 'primary' : 'default'"
            @click="changeView('runs')"
            >运行预测与批次</el-button
          >
        </nav>
        <ForecastExecution
          v-show="view === 'runs'"
          :selection="context"
          @refreshed="load"
        />
        <section v-show="view === 'overview'" class="panel">
          <div class="panel-head">
            <h2>实际走势</h2>
            <span class="small muted"
              >行情截止 {{ observations.at(-1)?.date || "尚无数据" }}</span
            >
          </div>
          <el-alert
            v-if="trendError"
            :title="trendError"
            type="error"
            :closable="false"
          />
          <el-skeleton v-else-if="busy" :rows="4" animated />
          <Chart
            v-else-if="observations.length"
            :option="chart"
            :height="280"
          />
          <el-empty v-else description="尚未取得该资产的行情数据" />
          <p class="small muted">
            图表读取当前已保存的实际行情；历史预测使用的当时输入请在下方冻结依据中核对。
          </p>
          <details v-if="observations.length" class="forecast-details">
            <summary>行情数据与来源</summary>
            <div class="forecast-table-wrap">
              <table class="forecast-table">
                <thead>
                  <tr>
                    <th>日期</th>
                    <th>实际值</th>
                    <th>来源</th>
                    <th>质量</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in observations.slice(-30).reverse()"
                    :key="row.date"
                  >
                    <td>{{ row.date }}</td>
                    <td>{{ row.value ?? "—" }}</td>
                    <td>{{ row.source || "原行情记录未注明" }}</td>
                    <td>{{ label(row.quality) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="small muted">
              明细展示最近30条；图表为已取得的实际行情，不是未来逐日预测路径。
            </p>
          </details>
        </section>
        <section v-show="view === 'overview'" class="panel">
          <div class="panel-head">
            <h2>各方案最近结果</h2>
            <router-link :to="{ path: '/validation', query: context }"
              >查看同目标验证 →</router-link
            >
          </div>
          <p class="small muted">
            每个方案保留自己的生成时间和输入截止；不同时间的最近结果不代表同批次对照。当前已读取
            {{ records.length }} 条记录，卡片展示此范围内各方案的最近结果。
          </p>
          <el-alert
            v-if="recordsError"
            :title="recordsError"
            type="error"
            :closable="false"
          />
          <el-skeleton v-else-if="busy" :rows="3" animated />
          <div v-else-if="latest.length" class="forecast-results">
            <article
              v-for="row in latest"
              :key="recordKey(row)"
              class="forecast-result"
            >
              <div class="panel-head">
                <h3>{{ label(row.scheme) }}</h3>
                <el-tag>{{ label(row.output_type) }}</el-tag>
              </div>
              <strong>{{ resultText(row) }}</strong>
              <p class="small">
                {{ label(row.status) }} · {{ label(row.role) }}
              </p>
              <p v-if="row.model_validation_status" class="small">
                模型资格：{{ label(row.model_validation_status) }}
              </p>
              <p v-if="row.probability_validation_status" class="small">
                概率核验：{{ label(row.probability_validation_status) }}
              </p>
              <p class="small muted">
                模型版本 {{ row.model_version || "原记录未注明" }}
              </p>
              <p class="small">
                生成 {{ time(row.generated_at) }}<br />输入截止
                {{ time(row.data_cutoff) }}
              </p>
              <p v-if="row.reason_codes?.length" class="small">
                {{ row.reason_codes.map(label).join("；") }}
              </p>
              <el-button
                text
                type="primary"
                @click="selectRecord(recordKey(row))"
                >查看冻结依据</el-button
              >
            </article>
          </div>
          <el-empty v-else description="尚无该资产、目标与期限的冻结预测" />
          <div class="forecast-participants">
            <p v-for="item in participants" :key="item.id">
              <strong>{{ item.name }}</strong> · {{ label(item.status) }} ·
              {{
                item.reason === label(item.status)
                  ? label(item.role)
                  : item.reason || label(item.role)
              }}
            </p>
          </div>
        </section>
        <section
          v-show="view !== 'runs'"
          class="panel"
          aria-label="冻结预测与依据"
        >
          <div class="panel-head">
            <h2>分析依据与预测历史</h2>
            <el-button :disabled="!selectedKey" @click="selectRecord('')"
              >返回最新</el-button
            >
          </div>
          <div class="toolbar evidence-time-controls" aria-label="依据时间口径">
            <el-button
              :type="evidenceMode === 'frozen' ? 'primary' : 'default'"
              @click="changeEvidence('frozen')"
              >本次冻结依据</el-button
            >
            <el-button
              :type="evidenceMode === 'latest' ? 'primary' : 'default'"
              @click="changeEvidence('latest')"
              >最新资讯</el-button
            >
          </div>
          <section v-if="evidenceMode === 'latest'" aria-label="最新资讯资料">
            <p class="note">
              这里是当前资产后来更新的资料，独立于所选历史预测，不会改写当时的输入和判断。
            </p>
            <el-alert
              v-if="newsError"
              :title="newsError"
              type="error"
              :closable="false"
            />
            <el-skeleton v-else-if="busy" :rows="3" animated />
            <ForecastEvidence v-else :analysis="latestNews" />
          </section>
          <template v-else>
            <label class="forecast-history-label"
              >选择预测记录<select
                aria-label="选择预测记录"
                :value="selectedKey"
                @change="
                  selectRecord(($event.target as HTMLSelectElement).value)
                "
              >
                <option value="">最近一条已保存预测</option>
                <option v-if="selectedKey && !selected" :value="selectedKey">
                  链接指定的历史记录
                </option>
                <option
                  v-for="row in records"
                  :key="recordKey(row)"
                  :value="recordKey(row)"
                >
                  {{ time(row.generated_at) }} · {{ label(row.scheme) }} ·
                  {{ row.id }}
                </option>
              </select></label
            >
            <el-button
              v-if="pagination.has_more"
              :loading="loadingMore"
              @click="loadMore"
              >加载更多历史记录</el-button
            >
            <el-alert
              v-if="detailError"
              :title="detailError"
              type="error"
              :closable="false"
            />
            <el-skeleton v-else-if="detailBusy" :rows="4" animated />
            <template v-else-if="detail">
              <div class="forecast-detail-summary">
                <h3>{{ label(detail.scheme) }} · {{ resultText(detail) }}</h3>
                <p>记录 {{ detail.id }}</p>
                <p>
                  生成 {{ time(detail.generated_at) }} · 输入截止
                  {{ time(detail.data_cutoff) }}
                </p>
                <p>
                  起点 {{ detail.anchor_date || "未记录" }} · 目标日期
                  {{ targetDateText(detail) }}
                </p>
                <p>
                  模型 {{ detail.model_version || "未记录" }} · 协议
                  {{ detail.target_version || "未记录" }}
                </p>
                <p>
                  依据状态：{{ label(detail.evidence_status) }} · 到期核验：{{
                    label(maturityStatus)
                  }}
                </p>
                <p v-if="detail.actual_return != null">
                  实际结果：{{ percent(detail.actual_return) }} · 实际终点
                  {{ detail.actual_target_date || "待核实" }}
                </p>
                <p v-if="detail.evaluation_eligible === false">
                  {{ detail.evaluation_reason }}
                </p>
                <p v-if="detail.explanation_status">
                  资讯关联：{{ label(detail.explanation_status) }}
                </p>
              </div>
              <p class="note">
                这里展示该记录生成时保存的输入与引用；后来的资讯不会替换本次依据。
              </p>
              <ForecastEvidence :analysis="frozenAnalysis" />
              <div class="small muted forecast-input-identity">
                <p v-if="detail.evidence?.snapshot_id">
                  输入快照 {{ detail.evidence.snapshot_id }}
                </p>
                <p v-if="detail.evidence?.record?.details?.data_snapshot_id">
                  数据快照 {{ detail.evidence.record.details.data_snapshot_id }}
                </p>
                <p
                  v-if="
                    detail.evidence?.record?.details?.input_revisions?.length
                  "
                >
                  本次引用
                  {{ detail.evidence.record.details.input_revisions.length }}
                  条历史观测修订；数据和模型版本随记录冻结。
                </p>
                <p
                  v-for="reason in detail.reason_codes || []"
                  :key="String(reason)"
                >
                  {{ label(reason) }}
                </p>
              </div>
            </template>
            <p v-else-if="!busy" class="muted">
              有冻结预测后，可在这里逐条核对历史依据。
            </p>
          </template>
        </section>
        <div class="toolbar forecast-tools">
          <router-link :to="{ path: '/reports', query: context }"
            >历史ETF日报</router-link
          ><router-link
            v-if="asset.asset_type === 'GOLD'"
            :to="{ path: '/gold', query: context }"
            >黄金情景与实物成本工具</router-link
          ><el-button text @click="changeEvidence('latest')"
            >查看当前资产最新资讯</el-button
          ><router-link :to="{ path: '/system', query: context }"
            >数据与任务管理</router-link
          >
        </div>
      </template>
      <template v-else>
        <section class="panel">
          <div class="panel-head">
            <h2>同目标方案比较</h2>
            <router-link
              :to="{
                path: '/forecast',
                query: { ...context, record: selectedKey || undefined },
              }"
              >返回预测与依据 →</router-link
            >
          </div>
          <p class="note">
            按相同资产、目标协议、期限和模式分组，模型版本与输出指标分别统计；选优只比较共同输出能力。未到期或不合格样本不计入准确度。
          </p>
          <el-alert
            v-if="evaluationError"
            :title="evaluationError"
            type="error"
            :closable="false"
          />
          <el-skeleton v-else-if="busy" :rows="4" animated />
          <template v-else>
            <section
              v-if="
                Array.isArray(evaluation.coverage) && evaluation.coverage.length
              "
              aria-label="统一预测执行覆盖"
            >
              <h3>统一预测执行覆盖</h3>
              <p class="small muted">
                分母为本组合实际创建的执行项，含重复批次；有效结果含复用，复用不增加独立预测样本。以下是执行覆盖，不是准确率；旧记录缺少执行分母时不推算。
              </p>
              <div class="forecast-table-wrap">
                <table class="forecast-table">
                  <thead>
                    <tr>
                      <th>预测个体</th>
                      <th>计划执行</th>
                      <th>已有有效结果</th>
                      <th>其中复用</th>
                      <th>弃权</th>
                      <th>失败</th>
                      <th>阻塞</th>
                      <th>等待 / 运行</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="item in evaluation.coverage" :key="item.scheme">
                      <td>{{ label(item.scheme) }}</td>
                      <td>{{ item.planned }}</td>
                      <td>{{ item.available }}</td>
                      <td>{{ item.reused }}</td>
                      <td>{{ item.abstained }}</td>
                      <td>{{ item.failed }}</td>
                      <td>{{ item.blocked }}</td>
                      <td>{{ item.pending }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>
            <p v-if="evaluation.recommendation_summary" class="note">
              {{
                typeof evaluation.recommendation_summary === "string"
                  ? evaluation.recommendation_summary
                  : evaluation.recommendation_summary.reason
              }}
            </p>
            <el-alert
              v-if="evaluation.truncated"
              title="评价查询存在截断，下列统计仅覆盖本次读取范围，不能代表完整历史。"
              type="warning"
              :closable="false"
            />
            <section
              v-for="(group, index) in evaluation.groups || []"
              :key="index"
              class="forecast-evaluation-group"
            >
              <h3>
                {{ label(group.output_type) }} · {{ group.target_version }}
              </h3>
              <section
                v-if="group.recommendation"
                class="forecast-recommendation"
                aria-label="分组推荐依据"
              >
                <strong>{{
                  recommendationLabel(group.recommendation.status)
                }}</strong>
                <p v-if="group.recommendation.scheme">
                  优先参考方案：{{ label(group.recommendation.scheme) }} ·
                  当前模型制品
                  {{ group.recommendation.model_version || "版本见评价记录" }}
                </p>
                <p class="small muted">
                  推荐评价固定训练与输入规则下的滚动预测方案；允许按相同规则更新模型制品，当前制品仅标识正在使用的版本。长期限积累足够不重叠样本需要持续观察。
                </p>
                <p v-if="group.recommendation.selection_policy" class="small">
                  规则指纹：{{ group.recommendation.selection_policy }}
                </p>
                <p
                  v-if="group.recommendation.model_versions_count != null"
                  class="small"
                >
                  本方案评价涉及
                  {{ group.recommendation.model_versions_count }}
                  个模型制品版本。
                </p>
                <p>{{ group.recommendation.reason }}</p>
                <p v-if="group.recommendation.window" class="small">
                  选优区间
                  {{ group.recommendation.window.selection_start || "未记录" }}
                  至
                  {{
                    group.recommendation.window.selection_end || "未记录"
                  }}；后续验证区间
                  {{ group.recommendation.window.validation_start || "未记录" }}
                  至
                  {{ group.recommendation.window.validation_end || "未记录" }}。
                </p>
                <p v-if="group.recommendation.metric" class="small">
                  选择指标 {{ label(group.recommendation.metric) }} · 规则版本
                  {{
                    group.recommendation.rule_version || "以本次评价说明为准"
                  }}
                </p>
                <p v-if="group.recommendation.samples != null" class="small">
                  评价样本
                  {{
                    group.recommendation.samples
                  }}；只使用已到期的历史证据，未来表现仍需持续验证。
                </p>
                <p
                  v-if="
                    group.recommendation.selection_samples != null ||
                    group.recommendation.validation_samples != null
                  "
                  class="small"
                >
                  选优样本
                  {{ group.recommendation.selection_samples ?? "未记录" }} ·
                  后续验证样本
                  {{ group.recommendation.validation_samples ?? "未记录" }}
                </p>
              </section>
              <el-alert
                v-if="group.comparisons_truncated"
                title="匹配对照仅显示本次查询允许的比较对，不能代表全部版本组合。"
                type="warning"
                :closable="false"
              />
              <p class="small">
                共同到期样本：{{
                  group.matched_samples ?? 0
                }}；各自样本和共同样本分别展示，不能用不同日期集合判断胜负。
              </p>
              <div class="forecast-table-wrap">
                <table class="forecast-table">
                  <thead>
                    <tr>
                      <th>方案 / 模型版本</th>
                      <th>已记录</th>
                      <th>有效到期</th>
                      <th>未纳入</th>
                      <th>重复机会</th>
                      <th>各自样本指标</th>
                      <th>匹配样本指标</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="item in group.individuals"
                      :key="`${item.scheme}:${item.model_version}`"
                    >
                      <td>
                        {{ label(item.scheme)
                        }}<small>{{
                          item.model_version || "未注明版本"
                        }}</small>
                      </td>
                      <td>{{ item.samples }}</td>
                      <td>{{ item.evaluated_samples }}</td>
                      <td>{{ item.excluded_samples }}</td>
                      <td>{{ item.duplicate_samples ?? 0 }}</td>
                      <td>
                        <div
                          v-for="line in metricLines(item.metrics)"
                          :key="line"
                        >
                          {{ line }}
                        </div>
                        <span v-if="!item.evaluated_samples">暂无到期评分</span>
                      </td>
                      <td>
                        <div
                          v-for="line in metricLines(item.matched_metrics)"
                          :key="line"
                        >
                          {{ line }}
                        </div>
                        <span v-if="!item.matched_samples">暂无共同评分</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="group.comparisons?.length" class="forecast-table-wrap">
                <h3>不同方案的两两匹配比较</h3>
                <p class="small muted">
                  逐对匹配共同目标、截止时点与实际结果，模型换代前后的版本分别比较。
                </p>
                <table class="forecast-table">
                  <thead>
                    <tr>
                      <th>方案一</th>
                      <th>方案二</th>
                      <th>共同样本</th>
                      <th>方案一指标</th>
                      <th>方案二指标</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr
                      v-for="(pair, pairIndex) in group.comparisons"
                      :key="pairIndex"
                    >
                      <td>
                        {{ label(pair.left.scheme)
                        }}<small>{{ pair.left.model_version }}</small>
                      </td>
                      <td>
                        {{ label(pair.right.scheme)
                        }}<small>{{ pair.right.model_version }}</small>
                      </td>
                      <td>{{ pair.matched_samples }}</td>
                      <td>
                        <div
                          v-for="line in metricLines(pair.left.metrics)"
                          :key="line"
                        >
                          {{ line }}
                        </div>
                      </td>
                      <td>
                        <div
                          v-for="line in metricLines(pair.right.metrics)"
                          :key="line"
                        >
                          {{ line }}
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p class="note">
                {{
                  group.reason
                    ? label(group.reason)
                    : "尚不能判断哪个方案更可靠"
                }}
              </p>
            </section>
            <el-empty
              v-if="!evaluation.groups?.length"
              description="尚无可评价记录；等待同目标预测与实际结果积累"
            />
            <p class="small muted">
              {{ evaluation.reason }} {{ evaluation.sample_rule }}
              推荐只描述已通过门槛的历史证据，不证明未来优势。重叠期限样本不等于相互独立的证据。
            </p>
          </template>
        </section>
        <section class="panel">
          <h2>深入验证工具</h2>
          <p class="small muted">
            以下保留原实验协议；交易收益不能替代预测准确度。
          </p>
          <div class="toolbar forecast-tools">
            <router-link :to="{ path: '/forward', query: context }"
              >前瞻实验</router-link
            ><router-link :to="{ path: '/backtests', query: context }"
              >策略回测</router-link
            ><router-link :to="{ path: '/paper', query: context }"
              >模拟交易</router-link
            ><router-link :to="{ path: '/rankings', query: context }"
              >旧协议模型比较</router-link
            >
          </div>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.forecast-section-tabs {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 8px;
  margin: 18px 0;
}
.evidence-time-controls {
  flex-wrap: wrap;
  margin: 14px 0;
}
.forecast-input-identity {
  margin-top: 18px;
  overflow-wrap: anywhere;
}
.forecast-recommendation {
  background: #f1f8f8;
  border-left: 3px solid #087f8c;
  padding: 14px;
  margin: 16px 0;
  line-height: 1.9;
  font-size: 13px;
}
.forecast-filters {
  display: grid;
  grid-template-columns: 2fr 2fr 1.4fr 1fr;
  gap: 14px;
}
.forecast-filters label,
.forecast-history-label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  min-width: 0;
}
select {
  width: 100%;
  min-width: 0;
  border: 1px solid #dce5ec;
  border-radius: 7px;
  padding: 10px 8px;
  background: white;
  color: #203447;
  font: inherit;
}
.forecast-context-note {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 18px 0 8px;
  font-size: 13px;
}
.forecast-results {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
  margin: 18px 0;
}
.forecast-result {
  border: 1px solid #dce5ec;
  border-radius: 8px;
  padding: 16px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.forecast-result strong {
  display: block;
  font-size: 17px;
  margin: 12px 0;
  line-height: 1.6;
}
.forecast-participants {
  border-top: 1px solid #edf2f6;
  padding-top: 12px;
  line-height: 1.8;
  font-size: 13px;
}
.forecast-details {
  margin-top: 12px;
}
.forecast-details summary {
  cursor: pointer;
  padding: 8px 0;
  color: #087f8c;
  font-size: 13px;
}
.forecast-table-wrap {
  overflow-x: auto;
  margin: 12px 0;
}
.forecast-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: 12px;
}
.forecast-table th,
.forecast-table td {
  padding: 12px 10px;
  border-bottom: 1px solid #e5ecf1;
  line-height: 1.8;
  min-width: 90px;
}
.forecast-table th {
  color: #748697;
  font-weight: 500;
  background: #f8fafc;
}
.forecast-table small {
  display: block;
  color: #748697;
  overflow-wrap: anywhere;
  max-width: 230px;
}
.forecast-detail-summary {
  font-size: 13px;
  line-height: 2;
  margin: 16px 0;
  overflow-wrap: anywhere;
}
.forecast-frozen-news {
  margin: 18px 0;
  line-height: 1.8;
}
.forecast-source {
  border-left: 2px solid #dce5ec;
  padding-left: 12px;
  margin: 12px 0;
  font-size: 13px;
}
.forecast-tools {
  margin-top: 15px;
  flex-wrap: wrap;
}
.forecast-tools a {
  color: #087f8c;
  font-size: 13px;
  padding: 8px 0;
}
.forecast-evaluation-group {
  margin: 24px 0;
}
.text-output {
  max-height: 420px;
  overflow: auto;
}
@media (max-width: 1100px) {
  .forecast-filters {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 600px) {
  .forecast-filters {
    grid-template-columns: 1fr;
  }
  .forecast-results {
    grid-template-columns: 1fr;
  }
  .panel-head {
    flex-wrap: wrap;
    gap: 10px;
  }
}
</style>
