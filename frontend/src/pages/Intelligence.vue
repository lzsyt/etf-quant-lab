<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { api, time, type Row } from "../api";

const data = ref<Row>({});
const busy = ref(false);
const error = ref("");
const tab = ref("news");
const page = ref(1);
const selectedNews = ref<Row | null>(null);
const selectedAnalysis = ref<Row | null>(null);
const filters = reactive({
  source: "",
  symbol: "",
  published_from: "",
  published_to: "",
  category: "",
  relevance: "",
  importance: "",
});
const symbols: Row = {
  "510300": "510300 · 沪深300",
  "510500": "510500 · 中证500",
  "159915": "159915 · 创业板",
};
const categories: Row = {
  MACRO_ECONOMY: "宏观经济",
  MONETARY_POLICY: "货币政策",
  FISCAL_POLICY: "财政政策",
  CAPITAL_MARKET: "资本市场政策",
  INDUSTRY_POLICY: "行业政策",
  ETF_INDEX: "ETF / 指数",
  MARKET_RISK: "市场风险",
  UNRELATED: "无关新闻",
};
const labels: Row = {
  OK: "采集成功",
  EMPTY: "来源暂无条目",
  STALE: "来源数据过期",
  FAILED: "失败",
  PARTIAL: "部分采集失败",
  DISABLED: "已停用",
  NOT_CONNECTED: "未接入",
  NOT_ENABLED: "尚未启用",
  NOT_ADMITTED: "未接入 · 条件未满足",
  DISABLED_STALE_SOURCE: "陈旧源已停用",
  NOT_CONNECTED_PERMISSION_REQUIRED: "未接入 · 需许可",
  NOT_CONNECTED_PERMISSION_UNVERIFIED: "未接入 · 许可未核实",
  NEVER_COLLECTED: "尚未采集",
  AVAILABLE: "分析有效",
  SUCCEEDED: "成功",
  MISSING: "缺失",
  RUNNING: "运行中",
  NO_RELEVANT_NEWS: "无相关新闻",
  INSUFFICIENT_DATA: "信息不足",
  STALE_NEWS: "新闻过期",
  REAL_CALL_PENDING: "真实调用待验证",
  PROVIDER_DISABLED_OR_KEY_MISSING: "提供方关闭或配置缺失",
  INVALID_OUTPUT: "模型输出未通过校验",
  DAILY_BUDGET_EXCEEDED: "当日预算不足",
  DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED:
    "当日调用保护：当前请求尚未验证",
  VERSION_VALIDATION_LIMIT: "新版验证次数已用尽",
  DAILY_VERSION_VALIDATION_LIMIT_CURRENT_REQUEST_NOT_VALIDATED:
    "当日新版验证次数已用尽：当前请求尚未验证",
  STALE_SOURCE_FEED: "来源长期未更新",
  RELEVANT: "初筛相关",
  IRRELEVANT: "不相关",
  UNVERIFIED: "未核实",
  UNKNOWN: "未核实",
  SELECTED: "已选入",
  EXCLUDED: "已排除",
  REAL: "真实数据",
  MOCK: "Mock 测试",
  HISTORICAL: "事后研究",
  FORWARD_PAPER: "前瞻模拟",
  SUMMARY: "来源摘要",
  FULL_TEXT: "完整正文",
  EXCERPT: "正文节选",
  TITLE_ONLY: "仅标题",
  MIXED: "混合类型（逐条标注）",
  high: "高",
  medium: "中",
  low: "低",
  none: "无",
  unknown: "未知",
  positive: "正向",
  negative: "负向",
  mixed: "混合",
  neutral: "中性",
  CONFIRMED: "已确认估算",
  RESERVED: "已预留",
  UNCERTAIN: "费用待核实",
  OFFICIAL_PRIMARY_SOURCE: "官方原始来源",
  DIRECT_ETF_INDEX: "直接涉及ETF或指数",
  BROAD_MARKET_TOPIC: "涉及较广市场主题",
  ANNOUNCED_POLICY_OR_DATA: "已公布的政策或数据",
  RECENCY: "信息时效",
  DISPLAYABLE: "可展示冻结片段",
  SOURCE_NOT_APPROVED: "来源展示权限未核实",
  EVIDENCE_TEXT_MISSING: "冻结片段暂无可展示文本",
  UNKNOWN_EVIDENCE_ID: "证据 ID 不在冻结目录中",
  EVIDENCE_SNAPSHOT_UNVERIFIED: "证据版本或快照无法核对",
  EVIDENCE_SOURCE_MISSING: "证据来源不在冻结输入中",
};
const sources = computed<Row[]>(() => data.value.sources || []);
const news = computed<Row[]>(() => data.value.news || []);
const legacy = computed<Row[]>(() => data.value.legacy_analyses || []);
const latestCollectionFailure = computed<Row | null>(() => {
  const failure = data.value.collection_failure?.[0];
  if (!failure) return null;
  const failureTime = Date.parse(failure.created_at);
  const reported = sources.value.map((row) =>
    Date.parse(row.last_reported_at || row.last_attempt_at || ""),
  );
  const latestReport = Math.max(0, ...reported.filter(Number.isFinite));
  return Number.isFinite(failureTime) && failureTime > latestReport
    ? failure
    : null;
});
const analyses = computed<Row[]>(() =>
  (data.value.analyses || []).flatMap((record: Row) => {
    // 失败批次可能保留原始模型响应，只有成功校验的结构才能呈现逐ETF分数。
    const items =
      record.status === "SUCCEEDED" ? record.result?.result?.items || [] : [];
    return items.length
      ? items.map((item: Row) => ({ ...item, record }))
      : [{ symbol: null, status: record.status, record }];
  }),
);
const sourceOptions = computed<Row[]>(() =>
  sources.value.map((item) => ({
    value: item.source,
    label: item.name || item.source,
  })),
);

/** 只读取已保存的采集和分析记录；刷新与筛选不会采集新闻或调用付费模型。 */
async function load(reset = false) {
  if (busy.value) return;
  if (reset) page.value = 1;
  busy.value = true;
  error.value = "";
  try {
    const query = new URLSearchParams({
      limit: "100",
      offset: String((page.value - 1) * 100),
    });
    for (const [key, value] of Object.entries(filters))
      if (value) query.set(key, value);
    data.value = await api("/intelligence?" + query.toString());
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "财经情报读取失败";
  } finally {
    busy.value = false;
  }
}

/** 未知原因码保留原文，避免将未接入、空结果和失败都显示为成功。 */
function label(value: unknown) {
  return value == null || value === ""
    ? "—"
    : labels[String(value)] || String(value);
}

/** 展示情绪原始分数，不换算成概率；缺失值不补零。 */
function score(value: unknown, digits = 4) {
  return value == null || value === "" || !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toFixed(digits);
}

/** 只有HTTP(S)原始来源可以打开，模型输出和未知协议不会成为可执行链接。 */
function sourceUrl(value: unknown) {
  try {
    const url = new URL(String(value));
    return ["http:", "https:"].includes(url.protocol) ? url.href : undefined;
  } catch {
    return undefined;
  }
}

/** 输入和有效引用分别按已保存ID回查冻结快照，绝不以候选总量推断模型实际输入。 */
function snapshotNews(record: Row, sourceIds: string[]) {
  const ids = new Set(sourceIds);
  const input = record.input_snapshot?.news || [];
  const reductions = record.quote?.relevance?.content_reductions || [];
  return input
    .filter((row: Row) => ids.has(row.id))
    .map((row: Row) => {
      const reduction = reductions.find((entry: Row) => entry.id === row.id);
      return reduction
        ? {
            ...row,
            content_type: "EXCERPT",
            model_input_chars: reduction.input_chars,
          }
        : row;
    });
}

/** 实际选入清单与模型校验是否成功无关；失败批次也能追溯原始资料。 */
function inputNews(item: Row) {
  return snapshotNews(
    item.record,
    item.record.quote?.eligible_source_ids || [],
  );
}

/** 仅呈现通过结构校验的引用；不存在引用不等于没有向模型提供新闻。 */
function coveredNews(item: Row) {
  return snapshotNews(item.record, item.relevant_source_ids || []);
}

/** 新协议只解析证据ID；即使模型夹带facts也不得退回自由文本事实。 */
function usesEvidence(item: Row) {
  return (
    item.record.quote?.schema_version === "etf-symbol-evidence-v2.1.2" ||
    !!item.record.quote?.evidence_version ||
    item.evidence_ids !== undefined
  );
}

/** 旧版摘录仅在明确的旧结构版本中保留，未知版本不能获得事实展示权限。 */
function legacyFacts(item: Row): Row[] {
  return !usesEvidence(item) &&
    [
      "etf-symbol-events-v2",
      "etf-symbol-events-v2.1",
      "etf-symbol-events-v2.1.1",
    ].includes(item.record.quote?.schema_version)
    ? item.facts || []
    : [];
}

/** 目录是冻结输入，不代表模型已调用或引用；只使用后端许可的短展示片段。 */
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
      published_at: entry.published_at,
      fetched_at: entry.fetched_at,
      content_type:
        entry.content_type ||
        (["title", "summary"].includes(entry.field) ? "SUMMARY" : "EXCERPT"),
      context_required: entry.context_required,
      standalone: entry.standalone,
      truncated: entry.display_truncated === true,
      status:
        entry.display_status === "DISPLAYABLE" && !entry.display_text
          ? "EVIDENCE_TEXT_MISSING"
          : entry.display_status || "EVIDENCE_TEXT_MISSING",
      text:
        entry.display_status === "DISPLAYABLE" ? entry.display_text : undefined,
    };
  });
}

/** 用quote冻结目录解析引用，result只核对身份；原text或模型自造catalog从不作为显示文本。 */
function evidenceReferences(item: Row): Row[] {
  const quote = item.record.quote || {},
    result = item.record.result || {};
  const ids = Array.isArray(item.evidence_ids) ? item.evidence_ids : [];
  const verified =
    quote.evidence_version === "news-evidence-v1" &&
    !!quote.evidence_snapshot_id &&
    quote.evidence_snapshot_id === result.evidence_snapshot_id &&
    quote.evidence_version === result.evidence_version;
  const catalog = evidenceCatalog(item.record);
  return ids.map((id: string) =>
    !verified
      ? { id, status: "EVIDENCE_SNAPSHOT_UNVERIFIED" }
      : catalog.find((entry) => entry.id === id) || {
          id,
          status: "UNKNOWN_EVIDENCE_ID",
        },
  );
}

/** 费用保护或未调用记录明确等待真实验证，不能把仅报价记录称为真实分析成功。 */
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

const budgetLabels: Row = {
  system_instructions: "系统指令",
  output_schema: "输出结构规则",
  task_fields: "任务字段",
  event_source_links: "事件与来源关联",
  news_metadata: "新闻元数据",
  news_title_summary: "标题与摘要",
  news_content: "正文或节选",
  json_framing: "JSON 结构",
  transport_reserve: "传输保守余量",
};

/** 按ETF逐条核实实际融合消费，不能把同批次其他ETF的使用当作本ETF已参与。 */
function usages(item: Row) {
  return (item.record.usage || []).filter(
    (usage: Row) => !item.symbol || usage.symbol === item.symbol,
  );
}

/** 后端已确认真实融合后再逐ETF核对，Mock、事后研究和回退引用都不能冒充真实参与。 */
function participated(item: Row) {
  if (
    item.record.status !== "SUCCEEDED" ||
    item.status !== "AVAILABLE" ||
    item.record.participated_in_fusion !== true ||
    item.record.nature !== "REAL"
  )
    return false;
  return usages(item).some(
    (usage: Row) =>
      usage.strategy === "FUSION_EXPERIMENT" &&
      usage.news_available === true &&
      usage.nature === "REAL" &&
      usage.evaluation_type === "FORWARD_PAPER" &&
      usage.mode === "FUSION",
  );
}

/** 信息来源名称优先采用已登记名称，历史来源不丢失原始编码。 */
function sourceName(value: unknown) {
  const source = value === "PBOC_OFFICIAL_DATE_PRECISION" ? "PBOC" : value;
  return (
    sources.value.find((item) => item.source === source)?.name || value || "—"
  );
}

/** 排序依据保留每项可解释分值，对旧字符串与当前结构化记录分别显示。 */
function importanceExplanation(metadata: Row = {}) {
  if (metadata.importance_reason) return metadata.importance_reason;
  return (
    (metadata.importance_reasons || [])
      .map((item: Row | string) =>
        typeof item === "string"
          ? item
          : `${label(item.reason)} ${Number(item.points) >= 0 ? "+" : ""}${count(item.points)}`,
      )
      .join("；") || "未记录排序依据"
  );
}

/** 数量为未知时显示横线，避免未接入来源呈现为一次成功的零条采集。 */
function count(value: unknown) {
  return value == null ? "—" : String(value);
}
onMounted(() => load());
</script>

<template>
  <div class="note">
    新闻重要性用于输入排序和研究展示；情绪分数不是上涨概率。页面只读已保存记录，不触发新闻采集或付费分析。
  </div>
  <section class="panel">
    <div class="panel-head">
      <div>
        <h2>财经情报工作台</h2>
        <p>读取时间 {{ time(data.generated_at) }} · 北京时间</p>
      </div>
      <el-button :loading="busy" @click="load()">刷新记录</el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      data-testid="intelligence-error"
    />
    <p v-if="error && data.generated_at" class="small muted">
      读取失败，当前仍展示上次成功读取的记录；其时间见上方。
    </p>
    <el-alert
      v-if="latestCollectionFailure"
      type="warning"
      :closable="false"
      show-icon
      data-testid="intelligence-collection-failure"
      :title="
        '最近一次新闻采集失败（' +
        time(latestCollectionFailure.created_at) +
        '），下方为已有记录。'
      "
    />
    <el-tabs v-model="tab">
      <el-tab-pane label="财经新闻" name="news">
        <el-form
          class="intelligence-filters"
          label-position="top"
          @submit.prevent="load(true)"
        >
          <el-form-item label="新闻来源"
            ><el-select
              v-model="filters.source"
              clearable
              aria-label="新闻来源筛选"
              placeholder="全部来源"
              ><el-option
                v-for="item in sourceOptions"
                :key="item.value"
                :value="item.value"
                :label="item.label" /></el-select
          ></el-form-item>
          <el-form-item label="ETF"
            ><el-select
              v-model="filters.symbol"
              clearable
              aria-label="ETF筛选"
              placeholder="全部ETF"
              ><el-option
                v-for="(name, code) in symbols"
                :key="code"
                :value="code"
                :label="name" /></el-select
          ></el-form-item>
          <el-form-item label="发布起始日期"
            ><el-date-picker
              v-model="filters.published_from"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="北京时间起始日"
              aria-label="发布起始日期"
          /></el-form-item>
          <el-form-item label="发布截止日期"
            ><el-date-picker
              v-model="filters.published_to"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="北京时间截止日"
              aria-label="发布截止日期"
          /></el-form-item>
          <el-form-item label="新闻类别"
            ><el-select
              v-model="filters.category"
              clearable
              aria-label="新闻类别筛选"
              placeholder="全部类别"
              ><el-option
                v-for="(name, code) in categories"
                :key="code"
                :value="code"
                :label="name" /></el-select
          ></el-form-item>
          <el-form-item label="相关性"
            ><el-select
              v-model="filters.relevance"
              clearable
              aria-label="相关性筛选"
              placeholder="全部相关性"
              ><el-option value="RELEVANT" label="初筛相关" /><el-option
                value="IRRELEVANT"
                label="不相关" /><el-option
                value="UNVERIFIED"
                label="未核实" /></el-select
          ></el-form-item>
          <el-form-item label="重要性"
            ><el-select
              v-model="filters.importance"
              clearable
              aria-label="重要性筛选"
              placeholder="全部重要性"
              ><el-option value="high" label="高" /><el-option
                value="medium"
                label="中" /><el-option value="low" label="低" /></el-select
          ></el-form-item>
          <el-form-item label=" "
            ><el-button native-type="submit" type="primary" :loading="busy"
              >筛选新闻</el-button
            ></el-form-item
          >
        </el-form>
        <el-table
          :data="news"
          stripe
          v-loading="busy"
          @row-click="selectedNews = $event"
          empty-text="没有符合条件的已保存新闻；采集是否失败请查看来源监控。"
          data-testid="intelligence-news"
        >
          <el-table-column label="新闻标题" min-width="290"
            ><template #default="{ row }"
              ><span class="news-title">{{ row.title }}</span>
              <div class="small muted">
                {{ label(row.content_type || "SUMMARY") }} ·
                {{ label(row.nature) }}
              </div></template
            ></el-table-column
          >
          <el-table-column label="来源" min-width="150"
            ><template #default="{ row }">{{
              sourceName(row.source_name)
            }}</template></el-table-column
          >
          <el-table-column label="发布时间" min-width="180"
            ><template #default="{ row }">{{
              time(row.published_at)
            }}</template></el-table-column
          >
          <el-table-column label="类别 / ETF" min-width="180"
            ><template #default="{ row }"
              >{{
                categories[row.metadata?.category] ||
                row.metadata?.category ||
                "未分类"
              }}
              <div class="small muted">
                {{ row.metadata?.related_symbols?.join(" / ") || "未关联" }}
              </div></template
            ></el-table-column
          >
          <el-table-column label="相关性 / 重要性" min-width="180"
            ><template #default="{ row }"
              >{{ label(row.metadata?.relevance_status) }}
              <div class="small muted">
                重要性 {{ label(row.metadata?.importance_level) }} ·
                {{ score(row.metadata?.importance_score, 0) }}
              </div></template
            ></el-table-column
          >
        </el-table>
        <el-pagination
          v-if="data.news_total > 100"
          v-model:current-page="page"
          :total="data.news_total"
          :page-size="100"
          layout="prev, pager, next, total"
          class="pagination"
          @current-change="load()"
        />
        <p class="small muted">
          共
          {{ count(data.news_total) }}
          条符合筛选条件的新闻。点击新闻查看摘要、出处及筛选依据。
        </p>
      </el-tab-pane>
      <el-tab-pane label="来源监控" name="sources">
        <el-table
          :data="sources"
          stripe
          empty-text="尚无来源监控记录，不代表采集成功。"
          data-testid="intelligence-sources"
        >
          <el-table-column label="来源 / 状态" min-width="200"
            ><template #default="{ row }"
              ><strong>{{ row.name || row.source }}</strong>
              <div>{{ label(row.status) }}</div></template
            ></el-table-column
          >
          <el-table-column label="最近尝试 / 最近成功" min-width="190"
            ><template #default="{ row }"
              >{{ time(row.last_attempt_at) }}
              <div class="small muted">
                成功 {{ time(row.last_success_at) }}
              </div></template
            ></el-table-column
          >
          <el-table-column label="最近批次 / 有关条数" min-width="160"
            ><template #default="{ row }"
              >{{ count(row.count) }} /
              {{ count(row.relevant_count) }}</template
            ></el-table-column
          >
          <el-table-column label="来源更新时间 / 过期" min-width="190"
            ><template #default="{ row }"
              >{{ time(row.source_updated_at) }}
              <div>
                {{
                  row.stale === true
                    ? "已过期"
                    : row.stale === false
                      ? "未标记过期"
                      : "新鲜度未核实"
                }}
              </div></template
            ></el-table-column
          >
          <el-table-column label="失败或停用原因" min-width="260"
            ><template #default="{ row }"
              >{{ label(row.error_code) }}
              <div class="small muted">
                {{ row.reason || row.disabled_reason || "" }}
              </div></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="DeepSeek 分析" name="analyses">
        <p class="small muted">
          逐 ETF 展示已保存结果。Token 与费用按一次批量请求计，三只 ETF
          共用，不能逐行相加。
        </p>
        <el-table
          :data="analyses"
          stripe
          @row-click="selectedAnalysis = $event"
          empty-text="尚无逐 ETF 分析记录；没有分析不等于市场中性。"
          data-testid="intelligence-analyses"
        >
          <el-table-column label="ETF / 实际状态" min-width="195"
            ><template #default="{ row }"
              ><strong>{{
                symbols[row.symbol] || "批次 · 未返回逐ETF结果"
              }}</strong>
              <div>
                {{ label(row.status)
                }}<span v-if="row.record.error_code">
                  · {{ label(row.record.error_code) }}</span
                >
              </div>
              <div v-if="waitingValidation(row.record)" class="small muted">
                WAITING_REAL_VALIDATION · 待真实验证
              </div>
            </template></el-table-column
          >
          <el-table-column label="分析时间 / 数据性质" min-width="200"
            ><template #default="{ row }"
              >{{ time(row.record.created_at) }}
              <div class="small muted">
                {{ label(row.record.nature || row.record.result?.nature) }} ·
                {{ label(row.record.evaluation_type) }}
              </div></template
            ></el-table-column
          >
          <el-table-column label="情绪 / 风险" min-width="145"
            ><template #default="{ row }"
              >{{ score(row.sentiment_score) }}
              <div>风险 {{ label(row.risk_level) }}</div></template
            ></el-table-column
          >
          <el-table-column label="提示词 / 模型" min-width="220"
            ><template #default="{ row }"
              >{{
                row.record.quote?.prompt_version ||
                row.record.prompt_version ||
                "—"
              }}
              <div class="small muted">
                {{
                  row.record.result?.response_model ||
                  row.record.quote?.model ||
                  "—"
                }}
              </div></template
            ></el-table-column
          >
          <el-table-column label="选入 / 有效引用" min-width="145"
            ><template #default="{ row }">
              {{ count(row.record.quote?.eligible_source_ids?.length) }} /
              {{ (row.relevant_source_ids || []).length }}
              <div class="small muted">输入按批次；引用按 ETF</div>
            </template></el-table-column
          >
          <el-table-column label="批次 Token / 估算美元" min-width="190"
            ><template #default="{ row }"
              >入 {{ count(row.record.cost?.input_tokens) }} / 出
              {{ count(row.record.cost?.output_tokens) }}
              <div>
                {{ score(row.record.cost?.actual_cost, 8) }} USD
              </div></template
            ></el-table-column
          >
          <el-table-column label="实际参与融合" min-width="180"
            ><template #default="{ row }"
              >{{ participated(row) ? "已参与融合决策" : "未参与有效融合" }}
              <div class="small muted">
                关联 {{ usages(row).length }} 条冻结决策
              </div></template
            ></el-table-column
          >
        </el-table>
        <details class="legacy-records">
          <summary>
            旧版市场总分分析（{{ legacy.length }} 条，与逐 ETF 结果分开）
          </summary>
          <article v-for="item in legacy" :key="item.id" class="legacy-item">
            <strong
              >{{ time(item.created_at) }} · {{ label(item.status) }}</strong
            >
            <p>
              {{
                item.prompt_version ||
                item.result?.prompt_version ||
                "版本未记录"
              }}
              · {{ label(item.nature || item.result?.nature) }}
            </p>
            <p>
              {{
                item.result?.result?.analysis_summary ||
                item.error_code ||
                "旧版记录未提供分析摘要"
              }}
            </p>
          </article>
          <p v-if="!legacy.length" class="small muted">没有旧版分析记录。</p>
        </details>
      </el-tab-pane>
    </el-tabs>
  </section>

  <el-drawer
    :model-value="!!selectedNews"
    @close="selectedNews = null"
    title="新闻出处与筛选证据"
    size="680px"
    class="intelligence-drawer"
  >
    <template v-if="selectedNews">
      <h2>{{ selectedNews.title }}</h2>
      <div class="tag-row">
        <el-tag>{{ label(selectedNews.content_type || "SUMMARY") }}</el-tag
        ><el-tag type="info">{{ label(selectedNews.nature) }}</el-tag>
      </div>
      <p class="news-summary">
        {{ selectedNews.summary || "来源未提供可展示摘要。" }}
      </p>
      <a
        v-if="sourceUrl(selectedNews.source_url)"
        :href="sourceUrl(selectedNews.source_url)"
        target="_blank"
        rel="noopener noreferrer"
        >查看原始来源</a
      >
      <dl class="evidence-list">
        <dt>来源</dt>
        <dd>{{ sourceName(selectedNews.source_name) }}</dd>
        <dt>发布时间</dt>
        <dd>{{ time(selectedNews.published_at) }}</dd>
        <dt>抓取时间</dt>
        <dd>{{ time(selectedNews.fetched_at) }}</dd>
        <dt>时间核验</dt>
        <dd>
          {{
            selectedNews.publication_verified === true
              ? "来源时间已核验"
              : "时间未核验，不能作为严格前瞻信息"
          }}
        </dd>
        <dt>新闻类别</dt>
        <dd>
          {{
            categories[selectedNews.metadata?.category] ||
            selectedNews.metadata?.category ||
            "未分类"
          }}
        </dd>
        <dt>初筛结果</dt>
        <dd>
          {{ label(selectedNews.metadata?.relevance_status) }} ·
          {{ selectedNews.metadata?.relevance_reason || "未记录筛选理由" }}
        </dd>
        <dt>相关 ETF</dt>
        <dd>
          {{ selectedNews.metadata?.related_symbols?.join(" / ") || "未关联" }}
        </dd>
        <dt>重要性</dt>
        <dd>
          {{ label(selectedNews.metadata?.importance_level) }} ·
          {{ score(selectedNews.metadata?.importance_score, 0) }}
          <p>
            {{ importanceExplanation(selectedNews.metadata) }}
          </p>
        </dd>
        <dt>事件标识</dt>
        <dd>{{ selectedNews.metadata?.event_id || "未记录" }}</dd>
        <dt>筛选版本</dt>
        <dd>{{ selectedNews.metadata?.selection_version || "历史未记录" }}</dd>
        <dt>内容指纹</dt>
        <dd>
          {{
            selectedNews.content_sha256 || selectedNews.content_hash || "未记录"
          }}
        </dd>
      </dl>
      <p class="small muted">
        页面仅展示允许展示的摘要。初筛相关与重要性排序不代表短期价格预测有效。
      </p>
    </template>
  </el-drawer>

  <el-drawer
    :model-value="!!selectedAnalysis"
    @close="selectedAnalysis = null"
    title="逐 ETF 新闻分析证据"
    size="760px"
    class="intelligence-drawer"
  >
    <template v-if="selectedAnalysis">
      <h2>{{ symbols[selectedAnalysis.symbol] || "批次分析记录" }}</h2>
      <div class="tag-row">
        <el-tag>{{ label(selectedAnalysis.status) }}</el-tag
        ><el-tag type="info">{{
          label(
            selectedAnalysis.record.nature ||
              selectedAnalysis.record.result?.nature,
          )
        }}</el-tag
        ><el-tag type="warning">{{
          label(selectedAnalysis.record.evaluation_type)
        }}</el-tag>
      </div>
      <p v-if="waitingValidation(selectedAnalysis.record)" class="note">
        WAITING_REAL_VALIDATION ·
        待真实验证：当前记录尚无通过校验的真实分析，不填充有效情绪。
      </p>
      <dl class="evidence-list">
        <dt>实际生成 / 输入截止</dt>
        <dd>
          {{ time(selectedAnalysis.record.created_at) }} /
          {{ time(selectedAnalysis.record.input_cutoff) }}
        </dd>
        <dt>提示词 / 结构版本</dt>
        <dd>
          {{ selectedAnalysis.record.quote?.prompt_version || "—" }} /
          {{ selectedAnalysis.record.quote?.schema_version || "—" }}
        </dd>
        <dt>实际输入类型</dt>
        <dd>
          {{
            label(
              selectedAnalysis.record.quote?.input_content_type ||
                selectedAnalysis.record.result?.input_content_type,
            )
          }}
          <p class="small muted">
            每条新闻保留自己的内容类型，页面仅展示允许的摘要。
          </p>
        </dd>
        <dt>请求 / 响应模型</dt>
        <dd>
          {{ selectedAnalysis.record.quote?.model || "—" }} /
          {{ selectedAnalysis.record.result?.response_model || "未返回" }}
        </dd>
        <dt>配置版本标签</dt>
        <dd>
          {{ selectedAnalysis.record.quote?.model_version || "—" }}
          <p class="small muted">配置标签不等于供应商不可变权重版本。</p>
        </dd>
        <dt>请求指纹</dt>
        <dd>
          {{
            selectedAnalysis.record.quote?.request_fingerprint ||
            selectedAnalysis.record.request_fingerprint ||
            "该版本未记录"
          }}
        </dd>
        <template v-if="usesEvidence(selectedAnalysis)">
          <dt>冻结证据版本</dt>
          <dd>
            {{ selectedAnalysis.record.quote?.evidence_version || "未记录" }}
          </dd>
          <dt>冻结证据快照</dt>
          <dd>
            {{
              selectedAnalysis.record.quote?.evidence_snapshot_id || "未记录"
            }}
          </dd>
        </template>
        <dt>相关性 / 方向</dt>
        <dd>
          {{ label(selectedAnalysis.relevance_level) }} /
          {{ label(selectedAnalysis.direction) }}
        </dd>
        <dt>情绪 / 风险</dt>
        <dd>
          {{ score(selectedAnalysis.sentiment_score) }} /
          {{ label(selectedAnalysis.risk_level) }}
          <p class="small muted">无量纲情绪，不是上涨概率；缺失值不补零。</p>
        </dd>
        <dt>分析理由</dt>
        <dd>
          {{
            selectedAnalysis.rationale ||
            label(selectedAnalysis.record.error_code)
          }}
        </dd>
        <dt>推断（模型影响逻辑）</dt>
        <dd>
          {{ selectedAnalysis.impact_logic || "该版本未提供结构化影响逻辑" }}
        </dd>
        <dt>不确定性</dt>
        <dd>
          {{ selectedAnalysis.uncertainty || "该版本未提供结构化不确定性" }}
        </dd>
      </dl>
      <h3>
        {{
          usesEvidence(selectedAnalysis)
            ? "事实（冻结来源片段）"
            : "旧版事实摘录（按原协议保留）"
        }}
      </h3>
      <div
        v-if="usesEvidence(selectedAnalysis)"
        data-testid="analysis-evidence-references"
      >
        <article
          v-for="entry in evidenceReferences(selectedAnalysis)"
          :key="entry.id"
          class="fact-item"
        >
          <p class="small muted">证据 ID {{ entry.id }}</p>
          <p v-if="entry.text">{{ entry.text }}</p>
          <p v-else>{{ label(entry.status) }}；不使用模型生成文本补齐。</p>
          <p v-if="entry.source" class="small muted">
            {{ sourceName(entry.source.source_name) }} ·
            {{ entry.source.title }} · 字段 {{ entry.field }} ·
            {{ label(entry.content_type) }} <br />发布
            {{ time(entry.published_at) }} · 获取 {{ time(entry.fetched_at) }}
          </p>
          <p v-if="entry.truncated" class="small muted">
            仅展示冻结证据前 240 字，内部完整片段保持原样。
          </p>
          <p
            v-if="entry.context_required || entry.standalone === false"
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
          v-if="!evidenceReferences(selectedAnalysis).length"
          class="small muted"
        >
          本条没有经校验的冻结证据引用，不补造事实。
        </p>
        <p class="small muted">
          以上片段来自冻结来源；模型的影响推断和不确定性与事实分开记录。
        </p>
      </div>
      <div
        v-for="(fact, index) in legacyFacts(selectedAnalysis)"
        :key="index"
        class="fact-item"
      >
        <p>{{ fact.quote }}</p>
        <span class="small muted">来源 ID {{ fact.source_id }}</span>
      </div>
      <p
        v-if="
          !usesEvidence(selectedAnalysis) &&
          !legacyFacts(selectedAnalysis).length
        "
        class="small muted"
      >
        此记录没有结构化事实摘录，不补造历史证据。
      </p>
      <h3>通过校验的引用新闻</h3>
      <article
        v-for="item in coveredNews(selectedAnalysis)"
        :key="item.id"
        class="covered-news"
      >
        <strong>{{ item.title }}</strong>
        <p class="small muted">
          {{ sourceName(item.source_name) }} · {{ time(item.published_at) }} ·
          {{
            label(item.content_type || item.metadata?.content_type || "SUMMARY")
          }}
          · 重要性
          {{ label(item.metadata?.importance_level) }}
          <span v-if="item.model_input_chars != null">
            · 实际送模节选 {{ item.model_input_chars }} 字</span
          >
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
      <p v-if="!coveredNews(selectedAnalysis).length" class="small muted">
        没有通过校验的引用新闻；不代表本批次没有选入输入，不使用后来采集的新闻补齐。
      </p>
      <h3>本批次选入输入与原始出处</h3>
      <p class="small muted">
        清单来自冻结输入；选入不等于已成功调用或分析有效。页面仅展示存档标题与摘要，实际输入类型逐条注明。
      </p>
      <div data-testid="analysis-selected-input">
        <article
          v-for="item in inputNews(selectedAnalysis)"
          :key="item.id"
          class="covered-news"
        >
          <strong>{{ item.title }}</strong>
          <p class="small muted">
            {{ sourceName(item.source_name) }} · 发布时间
            {{ time(item.published_at) }} · 抓取时间 {{ time(item.fetched_at) }}
          </p>
          <p class="small muted">
            实际输入类型
            {{
              label(
                item.content_type || item.metadata?.content_type || "SUMMARY",
              )
            }}
            <span v-if="item.model_input_chars != null">
              · 实际送模节选 {{ item.model_input_chars }} 字</span
            >
            · 来源 ID {{ item.id }}
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
        <p v-if="!inputNews(selectedAnalysis).length" class="small muted">
          没有可关联的选入新闻快照；未记录的历史输入不作推断。
        </p>
      </div>
      <details
        v-if="usesEvidence(selectedAnalysis)"
        data-testid="analysis-evidence-catalog"
      >
        <summary>
          冻结输入证据目录（{{
            evidenceCatalog(selectedAnalysis.record).length
          }}
          项，尚非模型引用）
        </summary>
        <p class="small muted">
          这些是系统从冻结来源提取的输入片段；目录存在不代表已调用模型、分析成功或参与融合。
        </p>
        <article
          v-for="entry in evidenceCatalog(selectedAnalysis.record)"
          :key="entry.id"
          class="fact-item"
        >
          <strong>{{ entry.id }}</strong>
          <p v-if="entry.text">{{ entry.text }}</p>
          <p v-else>{{ label(entry.status) }}；不补造文本。</p>
          <p v-if="entry.source" class="small muted">
            {{ sourceName(entry.source.source_name) }} ·
            {{ entry.source.title }} · 字段 {{ entry.field }} ·
            {{ label(entry.content_type) }}
          </p>
          <p v-if="entry.truncated" class="small muted">
            仅展示冻结证据前 240 字。
          </p>
          <p
            v-if="entry.context_required || entry.standalone === false"
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
        <p
          v-if="!evidenceCatalog(selectedAnalysis.record).length"
          class="small muted"
        >
          没有可解析的冻结输入证据目录；缺失或未知版本不补造片段。
        </p>
      </details>
      <h3>输入筛选记录（整个批次）</h3>
      <p>
        候选
        {{ count(selectedAnalysis.record.quote?.relevance?.candidate_count) }}
        条 · 初筛相关
        {{ count(selectedAnalysis.record.quote?.relevance?.relevant_count) }} 条
        · 选入
        {{ count(selectedAnalysis.record.quote?.relevance?.accepted_count) }} 条
        · 排除
        {{ count(selectedAnalysis.record.quote?.relevance?.excluded_count) }} 条
      </p>
      <details
        v-if="selectedAnalysis.record.quote?.relevance?.rejected?.length"
      >
        <summary>查看排除原因</summary>
        <p
          v-for="(item, index) in selectedAnalysis.record.quote.relevance
            .rejected"
          :key="index"
          class="small excluded-reason"
        >
          {{ item.id || item.source_url || "候选项" }} ·
          {{ label(item.reason) }}
        </p>
      </details>
      <details
        v-if="selectedAnalysis.record.quote?.input_budget_breakdown"
        data-testid="analysis-input-budget"
      >
        <summary>查看输入预算分项</summary>
        <p>
          总预算
          {{
            count(
              selectedAnalysis.record.quote.input_budget_breakdown.total_bytes,
            )
          }}
          UTF-8 字节； 选入
          {{
            count(
              selectedAnalysis.record.quote.input_budget_breakdown
                .selected_articles ??
                selectedAnalysis.record.quote?.eligible_source_ids?.length,
            )
          }}
          篇； 去除重复摘要
          {{
            count(
              selectedAnalysis.record.quote.input_budget_breakdown
                .summary_omitted_count,
            )
          }}
          篇。
        </p>
        <p class="small muted">
          这是输入大小的保守预算，包含传输余量，不是供应商实际 Token
          用量；实际用量见调用费用。
        </p>
        <p
          v-for="(value, key) in selectedAnalysis.record.quote
            .input_budget_breakdown.components_bytes || {}"
          :key="key"
        >
          {{ budgetLabels[key] || key }}：{{ value }} 字节
        </p>
      </details>
      <h3>调用与结构校验</h3>
      <div data-testid="analysis-validation">
        <p>
          {{ label(selectedAnalysis.record.status) }} ·
          {{ label(selectedAnalysis.record.error_code) }}
        </p>
        <article
          v-for="(attempt, index) in selectedAnalysis.record.result
            ?.attempt_evidence || []"
          :key="index"
          class="fact-item"
        >
          <strong
            >第 {{ attempt.attempt ?? Number(index) + 1 }} 次请求 · HTTP
            {{ count(attempt.http_status) }}</strong
          >
          <p>
            {{ time(attempt.started_at) }} · {{ label(attempt.error_code) }}
          </p>
          <p
            v-for="(issue, issueIndex) in attempt.validation_errors || []"
            :key="issueIndex"
          >
            {{ issue.path || "响应" }} · {{ issue.code || "校验未通过" }}：{{
              issue.message
            }}
          </p>
          <p
            v-if="attempt.error_code && !attempt.validation_errors?.length"
            class="small muted"
          >
            该次请求未记录字段级校验明细。
          </p>
        </article>
        <p
          v-if="!selectedAnalysis.record.result?.attempt_evidence?.length"
          class="small muted"
        >
          该记录未保存 HTTP 尝试证据；不据此推定已调用成功。
        </p>
      </div>
      <h3>调用与费用证据（整个批次）</h3>
      <p>
        输入 {{ count(selectedAnalysis.record.cost?.input_tokens) }} Token /
        输出
        {{ count(selectedAnalysis.record.cost?.output_tokens) }} Token；估算
        {{ score(selectedAnalysis.record.cost?.actual_cost, 8) }} USD；{{
          label(selectedAnalysis.record.cost?.status)
        }}
      </p>
      <p class="small muted">
        预留
        {{ score(selectedAnalysis.record.cost?.reserved_cost, 8) }}
        USD。应用估算不等于供应商账单；同一批次费用不按 ETF 重复相加。
      </p>
      <h3>实际决策引用</h3>
      <p>
        {{
          participated(selectedAnalysis) ? "已参与融合决策" : "未参与有效融合"
        }}
      </p>
      <article
        v-for="item in usages(selectedAnalysis)"
        :key="item.decision_id"
        class="decision-use"
      >
        <strong>{{ item.strategy }} · {{ item.mode }}</strong>
        <p>{{ time(item.generated_at) }} · {{ item.symbol }}</p>
        <span class="small muted">{{ item.decision_id }}</span>
      </article>
      <p v-if="!usages(selectedAnalysis).length" class="small muted">
        没有冻结决策引用此分析；研究结果不能冒充已执行交易。
      </p>
    </template>
  </el-drawer>
</template>

<style scoped>
.intelligence-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 16px;
  margin: 16px 0;
}
.intelligence-filters :deep(.el-select),
.intelligence-filters :deep(.el-date-editor) {
  width: 100%;
}
.news-title {
  color: var(--primary, #2463eb);
  cursor: pointer;
}
.news-summary,
.covered-news p,
.fact-item p {
  white-space: pre-wrap;
  line-height: 1.7;
}
.evidence-list {
  display: grid;
  grid-template-columns: 135px minmax(0, 1fr);
  gap: 14px 18px;
  margin: 24px 0;
}
.evidence-list dt {
  color: #68788e;
}
.evidence-list dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.evidence-list p {
  margin: 5px 0;
}
.covered-news,
.fact-item,
.decision-use,
.legacy-item {
  padding: 14px 0;
  border-bottom: 1px solid #e5eaf1;
  overflow-wrap: anywhere;
}
.legacy-records {
  margin-top: 24px;
}
.legacy-records summary {
  cursor: pointer;
  padding: 12px 0;
}
h3 {
  margin-top: 26px;
}
@media (max-width: 800px) {
  .intelligence-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 480px) {
  .intelligence-filters {
    grid-template-columns: 1fr;
  }
  .evidence-list {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .evidence-list dd {
    margin-bottom: 12px;
  }
}
</style>
<style>
.intelligence-drawer {
  max-width: 100vw;
}
</style>
