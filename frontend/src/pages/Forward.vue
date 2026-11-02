<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, money, percent, time, type Row } from "../api";
import Chart from "../components/Chart.vue";
import DataTable from "../components/DataTable.vue";

const data = ref<Row>({});
const busy = ref(false);
const error = ref("");
const tab = ref("decisions");
const selected = ref<Row | null>(null);
const drawer = ref(false);
const strategies: Row = {
  LGBM_BASE: "A · LightGBM 基线",
  XGB_BASE: "B · XGBoost 基线",
  LGBM_MATCHED_CONTROL: "C · 门槛匹配对照",
  FUSION_EXPERIMENT: "D · 新闻融合实验",
};
const labels: Row = {
  AVAILABLE: "新闻有效",
  NO_RELEVANT_NEWS: "无相关新闻",
  INSUFFICIENT_DATA: "数据不足",
  STALE_NEWS: "新闻过期",
  MISSING: "缺失",
  FAILED: "失败",
  SUCCEEDED: "成功",
  RUNNING: "运行中",
  PENDING: "待执行",
  WAITING: "等待",
  SKIPPED: "已跳过",
  DISABLED: "已关闭",
  DEGRADED: "降级运行",
  BLOCKED: "受阻",
  FROZEN: "意图已冻结 · 待执行",
  NO_ORDER: "无订单",
  FILLED: "已模拟成交",
  REJECTED: "未成交",
  OK: "采集成功",
  EMPTY: "来源暂无条目",
  STALE: "来源长期未更新",
  ML: "ML",
  FUSION: "FUSION",
  ML_FALLBACK: "ML_FALLBACK",
  INSUFFICIENT_SAMPLES: "尚不足以评价",
  LOW_ACTIVITY: "交易样本较少",
  MARKET: "行情",
  NEWS: "新闻分析",
  TRAIN: "模型训练",
  TRAINING: "模型训练",
  PREDICT: "冻结预测",
  PREDICTION: "冻结预测",
  MATCH: "模拟撮合",
  MATCHING: "模拟撮合",
  SETTLEMENT: "撮合与账户记账",
  EVALUATION: "到期评估",
  EVALUATE: "到期评估",
  low: "低",
  medium: "中",
  high: "高",
  SUMMARY: "来源摘要",
  FULL_TEXT: "完整正文",
  EXCERPT: "正文节选",
  TITLE_ONLY: "仅标题",
  MIXED: "混合类型（逐条标注）",
  REAL: "真实数据",
  MOCK: "Mock 测试",
  HISTORICAL: "事后研究",
  DISPLAYABLE: "可展示冻结片段",
  SOURCE_NOT_APPROVED: "来源展示权限未核实",
  EVIDENCE_TEXT_MISSING: "冻结片段暂无可展示文本",
  UNKNOWN_EVIDENCE_ID: "证据 ID 不在冻结目录中",
  EVIDENCE_SNAPSHOT_UNVERIFIED: "证据版本或快照无法核对",
  EVIDENCE_SOURCE_MISSING: "证据来源不在冻结输入中",
};
const reasons: Row = {
  BELOW_THRESHOLD: "未达阈值",
  NEWS_RISK_VETO: "新闻高风险否决",
  HIGH_RISK_VETO: "新闻高风险否决",
  NO_RELEVANT_NEWS: "无相关新闻",
  API_FAILED: "API 失败",
  CALL_FAILED: "API 失败",
  INTERNAL_CALL_FAILED: "API 失败",
  INVALID_OUTPUT: "新闻输出非法",
  PROVIDER_DISABLED_OR_KEY_MISSING: "真实调用待验证：提供方关闭或配置缺失",
  DAILY_BUDGET_EXCEEDED: "当日授权预算不足",
  DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED:
    "当日调用保护：当前请求尚未验证",
  VERSION_VALIDATION_LIMIT: "新版验证次数已用尽",
  DAILY_VERSION_VALIDATION_LIMIT_CURRENT_REQUEST_NOT_VALIDATED:
    "当日新版验证次数已用尽：当前请求尚未验证",
  MODEL_MISSING: "模型缺失",
  MODEL_STALE: "模型过期",
  MODEL_MISSING_OR_STALE: "模型缺失或过期",
  INVALID_MODEL: "模型结果非法",
  INSUFFICIENT_CASH: "资金不足",
  BELOW_LOT_SIZE: "不足交易单位",
  T_PLUS_ONE_LOCKED: "T+1 锁定",
  T_PLUS_ONE_OR_VOLUME_LIMIT: "T+1 锁定或历史容量不足",
  DATA_ERROR: "数据异常",
  NO_FILL: "无法撮合",
  WAITING_EXECUTION: "尚未到执行时间",
  BEFORE_EXECUTION_TIME: "尚未到执行时间",
  NO_REBALANCE_NEEDED: "目标仓位无需调整",
  TARGET_UNCHANGED: "目标仓位无需调整",
  STALE_NEWS: "新闻过期",
  INSUFFICIENT_DATA: "新闻数据不足",
  STALE_SOURCE_FEED: "新闻源长期未更新",
  WAITING_EXECUTION_TIME: "尚未到执行时间",
  TARGET_POSITION_UNCHANGED: "目标仓位无需调整",
  BELOW_TRADING_UNIT: "不足交易单位",
  MISSING_TARGET_HOLD: "无有效目标，保持既有持仓",
  DATA_ANOMALY: "数据异常",
  PREVIOUS_LIQUIDITY_UNVERIFIED: "前日流动性证据不足，无法撮合",
  SUSPENDED_NO_MATCH: "停牌，无法撮合",
  PRICE_LIMIT_NO_FILL: "触及涨跌停，无法撮合",
  CORPORATE_ACTION_QUARANTINED: "分红或拆并影响未核实，已隔离",
  TRADING_RULE_NOT_VERIFIED_FOR_DATE: "该日期交易规则未核实",
  DRAWDOWN_LOCK: "回撤硬风控锁定",
  DAILY_LOSS_LIMIT: "日损硬风控暂停买入",
  FILLED_DAILY_APPROXIMATION: "按冻结规则完成日线近似模拟成交",
  REAL_CALL_PENDING: "真实调用待验证",
  NO_SUPPORTED_ETF_TOPIC: "未命中已支持的 ETF 相关主题",
  ADMINISTRATIVE_NEWS: "行政事务新闻，与本实验无关",
  MODEL_IDENTITY_MISMATCH: "模型标签、期限或版本身份不匹配",
  MODEL_PROBABILITY_INVALID: "模型概率非法",
};
const accounts = computed<Row[]>(() => data.value.accounts || []);
const decisions = computed<Row[]>(() => data.value.decisions || []);
const latestNews = computed<Row>(() => (data.value.news || [])[0] || {});
const sourceReports = computed<Row[]>(
  () =>
    (data.value.health || []).find((item: Row) => item.stage === "NEWS")
      ?.details?.collection?.sources || [],
);
const selectedNews = computed<Row>(
  () =>
    (data.value.news || []).find(
      (item: Row) => item.id === selected.value?.details?.analysis_id,
    ) || {},
);
const availableCurve = computed(() =>
  accounts.value.some((item) => item.curve?.length),
);
const comparisonRows = computed(() =>
  accounts.value.map((item) => ({ ...item, ...item.metrics })),
);
const pairedRows = computed<Row[]>(() =>
  Array.isArray(data.value.paired?.signal_differences)
    ? data.value.paired.signal_differences
    : [],
);
const fallbackReasons = computed(
  () =>
    Object.entries(data.value.paired?.fallback_reasons || {})
      .map(([code, count]) => `${reason(code)}：${count} 天`)
      .join("；") || "尚无降级记录",
);
const curve = computed(() => {
  const dates = [
    ...new Set(
      accounts.value.flatMap((item) =>
        (item.curve || []).map((point: Row) => point.trade_date),
      ),
    ),
  ].sort();
  return {
    tooltip: { trigger: "axis" },
    legend: { bottom: 0 },
    grid: { left: 65, right: 20, top: 20, bottom: 65 },
    xAxis: { type: "category", data: dates },
    yAxis: { type: "value", scale: true },
    series: accounts.value.map((account) => ({
      name: strategies[account.strategy] || account.strategy,
      type: "line",
      connectNulls: false,
      showSymbol: true,
      data: dates.map((date) => {
        const point = (account.curve || []).find(
          (item: Row) => item.trade_date === date,
        );
        return point?.equity == null || Number(account.initial_capital) <= 0
          ? null
          : Number(point.equity) / Number(account.initial_capital);
      }),
    })),
  };
});

/** 页面只读真实实验接口，接口失败保留错误提示，不填模拟收益。 */
async function load() {
  busy.value = true;
  error.value = "";
  try {
    data.value = await api("/forward");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "前瞻数据读取失败";
  } finally {
    busy.value = false;
  }
}

/** 融合及情绪使用无量纲数值，绝不格式化为概率百分比。 */
function score(value: unknown) {
  return value == null || value === "" || !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toFixed(6);
}

/** 优先显示后端业务解释，未知原因码仍保留以便追踪。 */
function reason(value: unknown) {
  if (value == null || value === "") return "—";
  const code = String(value);
  return (
    reasons[code] ||
    (code.startsWith("HTTP_") ? "API 失败（" + code + "）" : code)
  );
}

/** 缺失目标与明确零目标不同，前者保持持仓、禁止新增买入。 */
function target(value: unknown) {
  return value == null ? "无有效目标 · 保持持仓" : percent(value);
}

/** 执行结果逐 ETF 匹配；未执行时仅展示冻结计划的诊断，不能假定已成交。 */
function executionInfo(row: Row) {
  return (
    (row.execution?.diagnostics || []).find(
      (item: Row) => item.symbol === row.symbol,
    ) ||
    (row.plan?.diagnostics || []).find(
      (item: Row) => item.symbol === row.symbol,
    ) ||
    {}
  );
}

/** 只有真实模拟成交记录可显示市场成交时刻，计划和拒单都不能冒充成交。 */
function fillTime(row: Row) {
  return (row.execution?.fills || []).find(
    (item: Row) => item.symbol === row.symbol,
  )?.market_executed_at;
}

/** 展开同一冻结决策的时间、版本、输入和成交证据。 */
function inspect(row: Row) {
  selected.value = row;
  drawer.value = true;
}

/** 仅回查本次选入的冻结来源；归档全文发生输入截断时必须显示节选。 */
function inputNews(record: Row): Row[] {
  const ids = new Set(record.quote?.eligible_source_ids || []);
  const reductions = record.quote?.relevance?.content_reductions || [];
  return (record.input_snapshot?.news || [])
    .filter((item: Row) => ids.has(item.id))
    .map((item: Row) => ({
      ...item,
      content_type: reductions.some((entry: Row) => entry.id === item.id)
        ? "EXCERPT"
        : item.content_type || item.metadata?.content_type || "SUMMARY",
    }));
}

/** 只有通过批次校验的引用可记为有效引用，失败或日保护记录不得推造分析结果。 */
function referenceCount(record: Row) {
  if (record.status !== "SUCCEEDED") return 0;
  return new Set(
    (record.result?.result?.items || []).flatMap(
      (item: Row) => item.relevant_source_ids || [],
    ),
  ).size;
}

/** 原始来源链接限制为HTTP(S)，其余文本只作为不可执行的审计信息展示。 */
function sourceUrl(value: unknown) {
  try {
    const url = new URL(String(value));
    return ["http:", "https:"].includes(url.protocol) ? url.href : undefined;
  } catch {
    return undefined;
  }
}
/** 仅报价、日保护或尚未调用均保留缺失状态，不使用展示层构造真实分析。 */
function waitingValidation(record: Row) {
  return (
    record.status === "MISSING" &&
    (String(record.error_code || "").startsWith("DAILY_") ||
      [
        "REAL_CALL_PENDING",
        "VERSION_VALIDATION_LIMIT",
        "PROVIDER_DISABLED_OR_KEY_MISSING",
      ].includes(record.error_code))
  );
}

/** 只有通过结构校验的批次才展示逐ETF推断，失败原始响应不成为有效结果。 */
function analysisItems(record: Row): Row[] {
  return record.status === "SUCCEEDED"
    ? record.result?.result?.items || []
    : [];
}

/** 新协议禁止回退模型facts，只有明确旧schema才保留旧摘录。 */
function legacyFacts(record: Row, item: Row): Row[] {
  return !record.quote?.evidence_version &&
    item.evidence_ids === undefined &&
    [
      "etf-symbol-events-v2",
      "etf-symbol-events-v2.1",
      "etf-symbol-events-v2.1.1",
    ].includes(record.quote?.schema_version)
    ? item.facts || []
    : [];
}

/** 冻结输入目录可用于核对报价，但其存在不代表模型已引用、分析成功或参与交易。 */
function evidenceCatalog(record: Row): Row[] {
  const quote = record.quote || {};
  if (
    quote.evidence_version !== "news-evidence-v1" ||
    !Array.isArray(quote.evidence_catalog)
  )
    return [];
  return quote.evidence_catalog.map((entry: Row) => {
    const id = entry.id || entry.evidence_id;
    const source = (record.input_snapshot?.news || []).find(
      (row: Row) => row.id === entry.source_id,
    );
    if (!source) return { id, status: "EVIDENCE_SOURCE_MISSING" };
    return {
      id,
      source,
      field: entry.field,
      content_type:
        entry.content_type ||
        (["title", "summary"].includes(entry.field) ? "SUMMARY" : "EXCERPT"),
      published_at: entry.published_at,
      fetched_at: entry.fetched_at,
      truncated: entry.display_truncated === true,
      context_required: entry.context_required,
      standalone: entry.standalone,
      status:
        entry.display_status === "DISPLAYABLE" && !entry.display_text
          ? "EVIDENCE_TEXT_MISSING"
          : entry.display_status || "EVIDENCE_TEXT_MISSING",
      text:
        entry.display_status === "DISPLAYABLE" ? entry.display_text : undefined,
    };
  });
}

/** 根据quote冻结目录解析ID，result仅核对快照身份，公开片段不取自模型text或facts。 */
function evidenceReferences(record: Row, item: Row): Row[] {
  const quote = record.quote || {},
    result = record.result || {};
  const ids = Array.isArray(item.evidence_ids) ? item.evidence_ids : [];
  const verified =
    quote.evidence_version === "news-evidence-v1" &&
    !!quote.evidence_snapshot_id &&
    quote.evidence_snapshot_id === result.evidence_snapshot_id &&
    quote.evidence_version === result.evidence_version;
  const catalog = evidenceCatalog(record);
  return ids.map((id: string) =>
    !verified
      ? { id, status: "EVIDENCE_SNAPSHOT_UNVERIFIED" }
      : catalog.find((entry) => entry.id === id) || {
          id,
          status: "UNKNOWN_EVIDENCE_ID",
        },
  );
}
onMounted(load);
</script>

<template>
  <div v-loading="busy">
    <div class="toolbar">
      <el-tag effect="plain">日线近似模拟 · 仅 h=1</el-tag>
      <el-tag :type="data.enabled ? 'success' : 'info'">{{
        data.enabled ? "新实验已启用" : "新实验未启用"
      }}</el-tag>
      <el-button @click="load" :disabled="busy">刷新</el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <div class="note">
      预测目标为“下一开盘到再下一开盘的价格上涨概率”，不是扣费后盈利概率。融合分数不是概率。
      无开盘盘口数据，模拟成交不能保证真实可成交；费率为模拟假设，公司行动覆盖未完全核实，账户总回报验证受限。
    </div>
    <div
      v-if="!data.experiment && !busy && !error"
      class="empty"
      data-testid="forward-empty"
    >
      尚无新批次前瞻实验。没有预测、成交或净值数据，不使用历史回测或演示曲线填充。
    </div>
    <template v-if="data.experiment">
      <section class="panel">
        <div class="form-grid">
          <div class="info-row">
            <span>实验批次</span><strong>{{ data.experiment.id }}</strong>
          </div>
          <div class="info-row">
            <span>共同起始交易日</span
            ><strong>{{ data.experiment.start_date || "待确认" }}</strong>
          </div>
          <div class="info-row">
            <span>策略版本</span
            ><strong>{{ data.experiment.strategy_version }}</strong>
          </div>
          <div class="info-row">
            <span>下一次日终计划</span
            ><strong>{{ time(data.next_schedule) }}</strong>
          </div>
        </div>
        <p class="small muted">
          每账户初始虚拟资金 100000 元；总目标仓位上限 40%，单 ETF 上限
          20%。旧策略和历史回测保留在原页面，与本批次分开统计。
        </p>
      </section>
      <el-tabs v-model="tab" aria-label="前瞻实验分区">
        <el-tab-pane name="decisions" label="今日决策">
          <section class="panel">
            <div class="panel-head">
              <h2>冻结决策与执行状态</h2>
              <span class="small muted"
                >点击行查看完整追踪 · 时间均显示北京时间</span
              >
            </div>
            <el-table
              :data="decisions"
              stripe
              empty-text="尚无冻结决策，等待合法的后续交易时段"
              @row-click="inspect"
              data-testid="forward-decisions"
            >
              <el-table-column label="策略 / ETF" min-width="175"
                ><template #default="{ row }"
                  ><strong>{{
                    strategies[row.details?.strategy] || row.details?.strategy
                  }}</strong>
                  <div>{{ row.symbol }}</div></template
                ></el-table-column
              >
              <el-table-column label="行情 / 执行 / 到期" min-width="150"
                ><template #default="{ row }"
                  ><div>行情 {{ row.signal_date }}</div>
                  <div>执行 {{ row.entry_date }}</div>
                  <div>到期 {{ row.evaluation_date }}</div></template
                ></el-table-column
              >
              <el-table-column label="ML 原始 / 校准概率" min-width="155"
                ><template #default="{ row }"
                  ><div>
                    原始 {{ percent(row.details?.ml?.raw_probability) }}
                  </div>
                  <div>
                    校准 {{ percent(row.details?.ml?.probability) }}
                  </div></template
                ></el-table-column
              >
              <el-table-column label="新闻 / 情绪 / 风险" min-width="150"
                ><template #default="{ row }"
                  ><div>
                    {{
                      labels[row.details?.news?.status] ||
                      reason(row.details?.news_reason)
                    }}
                  </div>
                  <div>
                    S {{ score(row.details?.news?.sentiment_score) }} · 风险
                    {{ labels[row.details?.news?.risk_level] || "—" }}
                  </div></template
                ></el-table-column
              >
              <el-table-column label="模式 / 融合分数" min-width="150"
                ><template #default="{ row }"
                  ><div>{{ row.details?.effective_mode || "—" }}</div>
                  <div>分数 {{ score(row.details?.score) }}</div></template
                ></el-table-column
              >
              <el-table-column label="目标 / 当前仓位" min-width="170"
                ><template #default="{ row }"
                  ><div>{{ target(row.details?.target_weight) }}</div>
                  <div>
                    当前 {{ percent(row.details?.current_weight) }}
                  </div></template
                ></el-table-column
              >
              <el-table-column label="执行 / 未交易解释" min-width="220"
                ><template #default="{ row }"
                  ><div>
                    {{
                      row.details?.reason || reason(row.details?.reason_code)
                    }}
                  </div>
                  <div v-if="row.details?.fallback_reason" class="small muted">
                    降级：{{ reason(row.details.fallback_reason) }}
                  </div>
                  <div v-if="executionInfo(row).status">
                    {{
                      labels[executionInfo(row).status] ||
                      executionInfo(row).status
                    }}：
                    {{ reason(executionInfo(row).reason) }}
                  </div></template
                ></el-table-column
              >
            </el-table>
          </section>
          <section class="panel break-anywhere" aria-label="新闻覆盖与真实输入">
            <div class="panel-head">
              <h2>新闻覆盖与真实输入</h2>
              <el-tag effect="plain">{{
                labels[latestNews.quote?.input_content_type] || "输入类型未记录"
              }}</el-tag>
            </div>
            <div class="toolbar">
              <span
                >候选
                {{ latestNews.quote?.relevance?.candidate_count ?? "—" }}
                条</span
              >
              <span
                >选入
                {{ latestNews.quote?.relevance?.accepted_count ?? "—" }}
                条</span
              >
              <span
                >排除
                {{ latestNews.quote?.relevance?.excluded_count ?? "—" }}
                条</span
              >
              <span
                >{{ labels[latestNews.status] || "尚无分析记录" }} ·
                {{ reason(latestNews.error_code) }}</span
              >
              <span>通过校验的引用 {{ referenceCount(latestNews) }} 条</span>
              <span v-if="waitingValidation(latestNews)"
                >WAITING_REAL_VALIDATION · 待真实验证</span
              >
            </div>
            <p class="small muted" data-testid="forward-latest-analysis">
              最新分析记录 {{ latestNews.id || "—" }} ·
              {{ time(latestNews.created_at) }} ·
              {{
                labels[latestNews.result?.nature] ||
                latestNews.result?.nature ||
                "性质未记录"
              }}； 提示词 / 结构 {{ latestNews.quote?.prompt_version || "—" }} /
              {{ latestNews.quote?.schema_version || "—" }}。
              此处最新状态不会改写上方历史冻结决策，实际是否融合以各决策的运行模式为准。
            </p>
            <p v-if="latestNews.quote?.evidence_version" class="small muted">
              冻结证据版本 {{ latestNews.quote.evidence_version }} · 快照
              {{ latestNews.quote.evidence_snapshot_id || "未记录" }}
            </p>
            <p class="small muted">
              DeepSeek 不可用时 C、D 使用有记录的 ML
              降级规则，独立机器学习流程仍可运行；有效高风险不能借降级放行。
            </p>
            <el-collapse>
              <el-collapse-item title="查看采集来源状态" name="news-sources">
                <DataTable
                  :rows="sourceReports"
                  :columns="[
                    { key: 'source', label: '来源', width: 160 },
                    {
                      key: 'status',
                      label: '采集状态',
                      format: (v) => labels[v] || v,
                      width: 180,
                    },
                    { key: 'candidate_count', label: '来源候选数' },
                    { key: 'count', label: '采集通过数' },
                    {
                      key: 'error_code',
                      label: '原因',
                      format: reason,
                      width: 200,
                    },
                    {
                      key: 'source_updated_at',
                      label: '来源自报更新时间',
                      format: time,
                      width: 190,
                    },
                  ]"
                />
              </el-collapse-item>
              <el-collapse-item
                title="查看排除原因与选入新闻摘要"
                name="news-input"
              >
                <DataTable
                  :rows="latestNews.quote?.relevance?.rejected || []"
                  :columns="[
                    { key: 'id', label: '来源 ID', width: 230 },
                    {
                      key: 'reason',
                      label: '排除原因',
                      format: reason,
                      width: 220,
                    },
                  ]"
                />
                <p class="small muted">
                  清单来自冻结输入；选入不等于分析成功。页面仅展示标题与存档摘要，正文不对外展示。
                </p>
                <article
                  v-for="item in inputNews(latestNews)"
                  :key="item.id"
                  class="forward-news-item"
                >
                  <strong>{{ item.title }}</strong>
                  <p>
                    {{ item.source_name }} ·
                    {{ labels[item.content_type] || item.content_type }} ·
                    发布时间 {{ time(item.published_at) }} · 抓取时间
                    {{ time(item.fetched_at) }}
                  </p>
                  <p>{{ item.summary || "没有可展示摘要" }}</p>
                  <a
                    v-if="sourceUrl(item.source_url)"
                    :href="sourceUrl(item.source_url)"
                    target="_blank"
                    rel="noopener noreferrer"
                    >原始来源</a
                  >
                </article>
                <p v-if="!inputNews(latestNews).length">
                  没有可关联的选入新闻快照；未记录的历史输入不作推断。
                </p>
              </el-collapse-item>
              <el-collapse-item
                title="查看输入预算与结构校验"
                name="news-validation"
              >
                <p>
                  请求指纹：{{
                    latestNews.quote?.request_fingerprint ||
                    latestNews.request_fingerprint ||
                    "该版本未记录"
                  }}
                </p>
                <p v-if="latestNews.quote?.input_budget_breakdown">
                  输入保守预算
                  {{
                    latestNews.quote.input_budget_breakdown.total_bytes ?? "—"
                  }}
                  UTF-8 字节；选入
                  {{
                    latestNews.quote.input_budget_breakdown.selected_articles ??
                    latestNews.quote?.eligible_source_ids?.length ??
                    "—"
                  }}
                  篇。 字节预算不是供应商实际 Token 用量。
                </p>
                <p v-else>该版本未记录输入预算分项。</p>
                <p>
                  实际输入 / 输出 Token：{{
                    latestNews.result?.input_tokens ?? "—"
                  }}
                  / {{ latestNews.result?.output_tokens ?? "—" }}；应用估算
                  {{ latestNews.result?.cost_usd ?? "—" }} USD。
                </p>
                <article
                  v-for="(attempt, index) in latestNews.result
                    ?.attempt_evidence || []"
                  :key="index"
                  class="forward-news-item"
                >
                  <strong
                    >第 {{ attempt.attempt ?? Number(index) + 1 }} 次请求 · HTTP
                    {{ attempt.http_status ?? "—" }}</strong
                  >
                  <p>{{ reason(attempt.error_code) }}</p>
                  <p
                    v-for="(issue, issueIndex) in attempt.validation_errors ||
                    []"
                    :key="issueIndex"
                  >
                    {{ issue.path || "响应" }} ·
                    {{ issue.code || "校验未通过" }}：{{ issue.message }}
                  </p>
                  <p
                    v-if="
                      attempt.error_code && !attempt.validation_errors?.length
                    "
                  >
                    该次请求未记录字段级校验明细。
                  </p>
                </article>
                <router-link to="/intelligence"
                  >查看逐 ETF 分析证据与完整预算分项</router-link
                >
              </el-collapse-item>
              <el-collapse-item
                title="查看冻结事实与模型推断"
                name="news-evidence"
              >
                <details
                  v-if="latestNews.quote?.evidence_version"
                  data-testid="forward-evidence-catalog"
                >
                  <summary>
                    冻结输入证据目录（{{ evidenceCatalog(latestNews).length }}
                    项，尚非模型引用）
                  </summary>
                  <p class="small muted">
                    这些是系统提取的冻结输入片段；目录存在不代表已调用模型、分析成功或参与融合。
                  </p>
                  <article
                    v-for="entry in evidenceCatalog(latestNews)"
                    :key="entry.id"
                    class="forward-news-item"
                  >
                    <strong>{{ entry.id }}</strong>
                    <p v-if="entry.text">{{ entry.text }}</p>
                    <p v-else>
                      {{ labels[entry.status] || entry.status }}；不补造文本。
                    </p>
                    <p v-if="entry.source" class="small muted">
                      {{ entry.source.source_name }} ·
                      {{ entry.source.title }} · 字段 {{ entry.field }} ·
                      {{ labels[entry.content_type] || entry.content_type }}
                    </p>
                    <p v-if="entry.truncated" class="small muted">
                      仅展示冻结证据前 240 字。
                    </p>
                    <p
                      v-if="
                        entry.context_required || entry.standalone === false
                      "
                      class="small muted"
                    >
                      需要上下文，不能单独解读。
                    </p>
                    <a
                      v-if="sourceUrl(entry.source?.source_url)"
                      :href="sourceUrl(entry.source.source_url)"
                      target="_blank"
                      rel="noopener noreferrer"
                      >证据原始来源</a
                    >
                  </article>
                  <p v-if="!evidenceCatalog(latestNews).length">
                    没有可解析的冻结目录，缺失或未知版本不补造片段。
                  </p>
                </details>
                <div data-testid="forward-evidence-references">
                  <article
                    v-for="item in analysisItems(latestNews)"
                    :key="item.symbol"
                    class="forward-news-item"
                  >
                    <strong
                      >{{ item.symbol }} ·
                      {{ labels[item.status] || item.status }} · S
                      {{ score(item.sentiment_score) }} · 风险
                      {{ labels[item.risk_level] || "—" }}</strong
                    >
                    <h3>事实（冻结来源片段）</h3>
                    <article
                      v-for="entry in evidenceReferences(latestNews, item)"
                      :key="entry.id"
                      class="forward-news-item"
                    >
                      <p class="small muted">证据 ID {{ entry.id }}</p>
                      <p v-if="entry.text">{{ entry.text }}</p>
                      <p v-else>
                        {{
                          labels[entry.status] || entry.status
                        }}；不使用模型生成文本补齐。
                      </p>
                      <p v-if="entry.source" class="small muted">
                        {{ entry.source.source_name }} ·
                        {{ entry.source.title }} · 字段 {{ entry.field }} ·
                        {{ labels[entry.content_type] || entry.content_type }} ·
                        发布 {{ time(entry.published_at) }} · 获取
                        {{ time(entry.fetched_at) }}
                      </p>
                      <p v-if="entry.truncated" class="small muted">
                        仅展示冻结证据前 240 字，内部完整片段保持原样。
                      </p>
                      <p
                        v-if="
                          entry.context_required || entry.standalone === false
                        "
                        class="small muted"
                      >
                        该片段需要上下文，不能单独解读。
                      </p>
                      <a
                        v-if="sourceUrl(entry.source?.source_url)"
                        :href="sourceUrl(entry.source.source_url)"
                        target="_blank"
                        rel="noopener noreferrer"
                        >证据原始来源</a
                      >
                    </article>
                    <p
                      v-for="(fact, index) in legacyFacts(latestNews, item)"
                      :key="index"
                    >
                      旧版事实摘录：{{ fact.quote }}
                    </p>
                    <p
                      v-if="
                        !evidenceReferences(latestNews, item).length &&
                        !legacyFacts(latestNews, item).length
                      "
                    >
                      本条没有经校验的冻结证据引用，不补造事实。
                    </p>
                    <h3>推断（模型影响逻辑）</h3>
                    <p>{{ item.impact_logic || "该版本未记录" }}</p>
                    <h3>不确定性</h3>
                    <p>{{ item.uncertainty || "该版本未记录" }}</p>
                  </article>
                  <p v-if="!analysisItems(latestNews).length">
                    尚无经校验的逐 ETF 事实与推断；{{
                      waitingValidation(latestNews)
                        ? "WAITING_REAL_VALIDATION · 待真实验证"
                        : "保留实际失败或缺失状态"
                    }}，不填充情绪分数。
                  </p>
                </div>
              </el-collapse-item>
            </el-collapse>
          </section>
        </el-tab-pane>
        <el-tab-pane name="comparison" label="策略对比">
          <section class="panel">
            <div class="panel-head">
              <h2>同批次真实前瞻模拟</h2>
              <router-link to="/backtests">查看独立历史回测</router-link>
            </div>
            <p data-testid="forward-sample-warning" class="note">
              样本较少时尚不足以评价策略有效性；零成交不能证明风控优秀，测试通过不代表策略有效。
            </p>
            <DataTable
              :rows="comparisonRows"
              :columns="[
                {
                  key: 'strategy',
                  label: '策略',
                  format: (v) => strategies[v] || v,
                  width: 210,
                },
                { key: 'total_return', label: '累计收益', format: percent },
                { key: 'max_drawdown', label: '最大回撤', format: percent },
                { key: 'transaction_cost', label: '总交易成本', format: money },
                { key: 'commissions', label: '其中佣金', format: money },
                {
                  key: 'slippage_cost',
                  label: '其中滑点与价差',
                  format: money,
                  width: 160,
                },
                { key: 'turnover', label: '双边换手率', format: percent },
                { key: 'average_position', label: '平均仓位', format: percent },
                { key: 'fill_count', label: '成交笔数' },
                { key: 'effective_days', label: '有效交易日' },
                {
                  key: 'evaluation_status',
                  label: '评价状态',
                  format: (v) => labels[v] || v || '尚不足以评价',
                  width: 170,
                },
                { key: 'id', label: '独立账户 ID', width: 230 },
              ]"
            />
            <p class="small muted">
              总交易成本包含佣金和成交价相对开盘价的滑点、价差偏差；滑点与价差已计入成交价，价差不再重复叠加。
            </p>
            <Chart v-if="availableCurve" :option="curve" :height="320" />
            <div v-else class="empty" data-testid="forward-curve-empty">
              尚无真实前瞻净值，等待首个有效交易日记账；不填充演示收益曲线。
            </div>
            <p class="small muted">
              A 对 B 比较行情模型；A 对 C 观察门槛和新闻可用性分支；C 对 D
              观察新闻情绪与风险的增量。历史回测、预测评价和账户净值分别记录。
            </p>
          </section>
          <section class="panel" aria-label="C D 配对增量证据">
            <div class="panel-head">
              <h2>C / D 新闻增量证据</h2>
              <span class="small muted">同一天三个 ETF 不视作三个独立日期</span>
            </div>
            <div class="form-grid">
              <div class="info-row">
                <span>整个前瞻区间独立日期</span
                ><strong>{{ data.paired?.independent_dates ?? "—" }}</strong>
              </div>
              <div class="info-row">
                <span>新闻有效日期</span
                ><strong>{{ data.paired?.valid_news_dates ?? "—" }}</strong>
              </div>
              <div class="info-row">
                <span>有效新闻配对信号记录</span
                ><strong>{{ pairedRows.length }}</strong>
              </div>
              <div class="info-row">
                <span>新闻改变买入决定</span
                ><strong>{{ data.paired?.buy_decision_changes ?? "—" }}</strong>
              </div>
              <div class="info-row">
                <span>新闻改变减仓决定</span
                ><strong>{{
                  data.paired?.reduction_decision_changes ?? "—"
                }}</strong>
              </div>
              <div class="info-row">
                <span>风险否决次数</span
                ><strong>{{ data.paired?.risk_vetoes ?? "—" }}</strong>
              </div>
              <div class="info-row">
                <span>降级日期数</span
                ><strong>{{ data.paired?.fallback_days ?? "—" }}</strong>
              </div>
            </div>
            <div class="small muted">降级原因：{{ fallbackReasons }}</div>
            <DataTable
              :rows="pairedRows"
              :columns="[
                { key: 'date', label: '信号日期' },
                { key: 'symbol', label: 'ETF' },
                {
                  key: 'control',
                  label: 'C 分数',
                  format: (v) => score(v?.score),
                },
                {
                  key: 'fusion',
                  label: 'D 分数',
                  format: (v) => score(v?.score),
                },
                {
                  key: 'control',
                  label: 'C 资格 / 目标',
                  format: (v) =>
                    (v?.eligible ? '合格' : '不合格') +
                    ' / ' +
                    target(v?.target_weight),
                  width: 200,
                },
                {
                  key: 'fusion',
                  label: 'D 资格 / 目标',
                  format: (v) =>
                    (v?.eligible ? '合格' : '不合格') +
                    ' / ' +
                    target(v?.target_weight),
                  width: 200,
                },
              ]"
            />
            <p class="small muted">
              自适应融合保持关闭；没有到期样本不代表训练完成。
            </p>
          </section>
        </el-tab-pane>
        <el-tab-pane name="health" label="运行健康">
          <section class="panel">
            <div class="panel-head">
              <h2>依赖任务与最近状态</h2>
              <span class="small muted"
                >DeepSeek 失败与整个系统停止分开显示</span
              >
            </div>
            <DataTable
              :rows="data.health || []"
              :columns="[
                {
                  key: 'stage',
                  label: '环节',
                  format: (v) => labels[v] || v,
                  width: 150,
                },
                { key: 'status', label: '状态', format: (v) => labels[v] || v },
                {
                  key: 'last_success_at',
                  label: '最近成功时间',
                  format: time,
                  width: 190,
                },
                {
                  key: 'last_attempt_at',
                  label: '最近尝试时间',
                  format: time,
                  width: 190,
                },
                {
                  key: 'reason',
                  label: '失败或等待原因',
                  format: reason,
                  width: 230,
                },
                {
                  key: 'next_scheduled_at',
                  label: '下一计划时间',
                  format: time,
                  width: 190,
                },
                { key: 'trace_id', label: '追踪 ID', width: 230 },
              ]"
            />
            <p class="small muted">
              行情完整后先记账与评估，再检查模型、分析新闻、冻结下一交易日意图。HTTP
              200 仍需业务状态成功；不回填错过的开盘。
            </p>
          </section>
          <section class="panel">
            <div class="panel-head"><h2>新标签模型证据</h2></div>
            <DataTable
              :rows="data.models || []"
              :columns="[
                { key: 'id', label: '模型 ID', width: 300 },
                { key: 'symbol', label: 'ETF' },
                { key: 'algorithm', label: '算法' },
                { key: 'label_version', label: '标签版本', width: 220 },
                { key: 'protocol_version', label: '交易协议', width: 230 },
                {
                  key: 'created_at',
                  label: '工件生成时间',
                  format: time,
                  width: 190,
                },
                {
                  key: 'available_at',
                  label: '工件实际可用时间',
                  format: time,
                  width: 190,
                },
              ]"
            />
          </section>
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-drawer
      v-model="drawer"
      title="真实决策完整追踪"
      size="min(900px, 96vw)"
    >
      <template v-if="selected">
        <h3>
          {{
            strategies[selected.details?.strategy] || selected.details?.strategy
          }}
          · {{ selected.symbol }}
        </h3>
        <div class="info-row">
          <span>决策 ID</span><strong>{{ selected.id }}</strong>
        </div>
        <div class="info-row">
          <span>行情截止日期</span><strong>{{ selected.signal_date }}</strong>
        </div>
        <div class="info-row">
          <span>真实生成时刻</span
          ><strong>{{ time(selected.generated_at) }}</strong>
        </div>
        <div class="info-row">
          <span>拟执行日 / 评价到期日</span
          ><strong
            >{{ selected.entry_date }} / {{ selected.evaluation_date }}</strong
          >
        </div>
        <div class="info-row">
          <span>模型版本</span
          ><strong>{{ selected.details?.ml?.model_id || "无有效模型" }}</strong>
        </div>
        <div class="info-row">
          <span>标签 / 策略版本</span
          ><strong
            >{{ selected.details?.label_version }} /
            {{ selected.details?.strategy_version }}</strong
          >
        </div>
        <div class="info-row">
          <span>交易协议</span
          ><strong>{{ selected.details?.protocol_version }}</strong>
        </div>
        <div class="info-row">
          <span>ML 原始 / 校准概率</span
          ><strong
            >{{ percent(selected.details?.ml?.raw_probability) }} /
            {{ percent(selected.details?.ml?.probability) }}</strong
          >
        </div>
        <div class="info-row">
          <span>新闻状态 / 风险</span
          ><strong
            >{{
              labels[selected.details?.news?.status] ||
              reason(selected.details?.news_reason)
            }}
            / {{ labels[selected.details?.news?.risk_level] || "—" }}</strong
          >
        </div>
        <div class="info-row">
          <span>情绪分数 S / 融合分数</span
          ><strong
            >{{ score(selected.details?.news?.sentiment_score) }} /
            {{ score(selected.details?.score) }}</strong
          >
        </div>
        <div class="info-row">
          <span>公式</span
          ><strong>{{ selected.details?.formula || "—" }}</strong>
        </div>
        <div class="info-row">
          <span>实际运行模式</span
          ><strong>{{ selected.details?.effective_mode || "—" }}</strong>
        </div>
        <div class="info-row">
          <span>目标 / 当前仓位</span
          ><strong
            >{{ target(selected.details?.target_weight) }} /
            {{ percent(selected.details?.current_weight) }}</strong
          >
        </div>
        <p class="note">
          {{ selected.details?.reason || reason(selected.details?.reason_code)
          }}<span v-if="selected.details?.fallback_reason"
            >；降级：{{ reason(selected.details.fallback_reason) }}</span
          >
        </p>
        <p class="small muted" v-if="executionInfo(selected).status">
          订单 / 执行状态：{{
            labels[executionInfo(selected).status] ||
            executionInfo(selected).status
          }}；
          {{ reason(executionInfo(selected).reason) }}
        </p>
        <p class="small muted" data-testid="forward-execution-times">
          市场成交时刻：{{ time(fillTime(selected)) }}；账户实际记账时刻：{{
            time(selected.execution?.snapshot?.booked_at)
          }}。日线近似模拟，不是实时成交。
        </p>
        <p class="small muted">
          供应商响应模型：{{
            selectedNews.result?.response_model || "未取得"
          }}；请求关联 ID：{{
            selectedNews.result?.provider_request_id ||
            "供应商未返回或尚未调用"
          }}。系统模型版本标签不代表不可变权重版本。
        </p>
        <p class="small muted">
          提示词 / 结构：{{ selectedNews.quote?.prompt_version || "—" }} /
          {{ selectedNews.quote?.schema_version || "—" }}；输入 / 输出 Token：{{
            selectedNews.result?.input_tokens ?? "—"
          }}
          / {{ selectedNews.result?.output_tokens ?? "—" }}；应用估算费用
          USD：{{ selectedNews.result?.cost_usd ?? "—" }}
        </p>
        <p
          v-if="selectedNews.quote?.evidence_version"
          class="small muted break-anywhere"
        >
          该决策关联证据版本 {{ selectedNews.quote.evidence_version }} ·
          冻结快照 {{ selectedNews.quote.evidence_snapshot_id || "未记录" }}
        </p>
        <el-collapse>
          <el-collapse-item title="冻结意图、执行与到期结果" name="evidence">
            <pre class="forward-text">{{
              JSON.stringify(
                {
                  plan: selected.plan,
                  execution: selected.execution,
                  actual: selected.actual,
                  trace_id: selected.details?.trace_id,
                },
                null,
                2,
              )
            }}</pre>
          </el-collapse-item>
          <el-collapse-item title="该决策关联的分析输入摘要" name="news">
            <p>
              关联分析 ID：{{
                selected.details?.analysis_id || "未关联"
              }}；该决策冻结模式 {{ selected.details?.effective_mode || "—" }}。
            </p>
            <p class="small muted">
              关联分析不等于实际参与融合。以下仅回查该决策当时的分析记录，不用最新分析替换。
            </p>
            <article
              v-for="item in inputNews(selectedNews)"
              :key="item.id"
              class="forward-news-item"
            >
              <strong>{{ item.title }}</strong>
              <p>
                {{ item.source_name }} ·
                {{ labels[item.content_type] || item.content_type }} ·
                {{ time(item.published_at) }}
              </p>
              <p>{{ item.summary || "没有可展示摘要" }}</p>
              <a
                v-if="sourceUrl(item.source_url)"
                :href="sourceUrl(item.source_url)"
                target="_blank"
                rel="noopener noreferrer"
                >原始来源</a
              >
            </article>
            <p v-if="!inputNews(selectedNews).length">
              没有可关联的选入新闻快照；未记录的历史输入不作推断。
            </p>
          </el-collapse-item>
        </el-collapse>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.forward-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
  line-height: 1.7;
}
.info-row strong {
  overflow-wrap: anywhere;
  text-align: right;
}
.forward-news-item {
  padding: 12px 0;
  border-bottom: 1px solid #e5eaf1;
  overflow-wrap: anywhere;
}
</style>
