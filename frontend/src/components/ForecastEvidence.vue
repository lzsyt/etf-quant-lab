<script setup lang="ts">
import { computed } from "vue";
import { time, type Row } from "../api";
import { safeLink, label as nativeLabel } from "../funds/client";

const props = defineProps<{ analysis?: Row | null }>();
const content = computed(() => props.analysis || {});
const effortNames: Row = {
  none: "关闭思考",
  low: "低",
  medium: "中",
  high: "高",
  xhigh: "超高",
  max: "最高",
};

/** 统一协议的角色与状态对用户明确说明，不能把弃权解释为完成预测。 */
function label(value: unknown): string {
  const labels: Row = {
    QUANT_INPUT_ONLY: "冻结行情与量化模型依据",
    INDEPENDENT_PREDICTION_EVIDENCE: "独立预测冻结依据",
    FROZEN_FORECAST_EVIDENCE: "本条预测的冻结依据",
    LATEST_INDEPENDENT_FORECAST: "最新独立预测记录",
    LATEST_LEGACY_EXPLANATION: "最新已保存资讯解释",
    ABSTAIN: "信息不足，未给出预测",
    BLOCKED: "条件未满足，未执行",
    FAILED: "执行失败",
    REUSED: "复用已有结果",
    PREDICTED: "已给出独立预测",
    AVAILABLE: "分析可用",
    UP: "上涨",
    DOWN: "下跌",
    FLAT: "持平",
    NO_QUALIFIED_NEWS: "没有符合来源和时点要求的资讯",
    DEEPSEEK_DISABLED_OR_UNCONFIGURED: "DeepSeek 未启用或尚未配置",
    GPT_DISABLED_OR_PROXY_UNCONFIGURED: "GPT 未启用或专用代理尚未配置",
    GPT_PROXY_REQUIRED: "GPT 专用代理不可用，未回退直连",
    GPT_PROXY_OR_API_FAILED: "GPT 代理或接口请求失败",
    GPT_DISAGREEMENT_DISABLED: "GPT 分歧分析已关闭，未发起调用",
    DISAGREEMENT_ANALYSIS: "同一截止的历史预测分歧分析",
    INSUFFICIENT_COMPARABLE_PREDICTIONS: "同一截止下不足两条可比较预测",
    SHARED_DAILY_BUDGET_EXCEEDED: "当日共享调用预算不足",
    GPT_DAILY_BUDGET_EXCEEDED: "GPT当日调用预算不足",
    DEEPSEEK_DAILY_BUDGET_EXCEEDED: "DeepSeek当日调用预算不足",
    BILLING_ALREADY_RESERVED_OR_UNKNOWN: "已有费用预留或费用待核实，未重复调用",
  };
  return labels[String(value)] || nativeLabel(value);
}

/** 证据里的文本与推断分开阅读，不把复杂对象直接输出为 JSON。 */
function text(value: unknown): string {
  if (typeof value === "string") return value;
  if (!value || typeof value !== "object") return "";
  const row = value as Row;
  return (
    row.text ||
    row.statement ||
    row.summary ||
    row.mechanism ||
    row.description ||
    row.reason ||
    row.claim ||
    row.factor ||
    row.title ||
    ""
  );
}

/** 原记录可能没有某类因素，缺失时保持空列表，不补造判断。 */
function lines(value: unknown): string[] {
  return (Array.isArray(value) ? value : value ? [value] : [])
    .map(text)
    .filter(Boolean);
}
const summary = computed(() => text(content.value.summary));
const positive = computed(() => lines(content.value.positive_factors));
const negative = computed(() =>
  lines(content.value.counter_factors || content.value.negative_factors),
);
const uncertainties = computed(() => lines(content.value.uncertainties));
const invalidation = computed(() =>
  lines(content.value.invalidation_conditions || content.value.invalidation),
);
const facts = computed(() => lines(content.value.facts));
const inferences = computed(() => lines(content.value.inferences));
const mechanisms = computed(() => lines(content.value.mechanisms));
const documents = computed<Row[]>(() =>
  Array.isArray(content.value.documents) ? content.value.documents : [],
);
const hasReading = computed(() =>
  Boolean(
    summary.value ||
    facts.value.length ||
    positive.value.length ||
    negative.value.length ||
    inferences.value.length ||
    mechanisms.value.length ||
    documents.value.length,
  ),
);
</script>

<template>
  <div class="forecast-evidence" aria-label="可读分析依据">
    <div class="evidence-time">
      <span
        >分析状态：{{
          label(content.analysis_status || content.status || "MISSING")
        }}</span
      >
      <span
        >分析时间：{{ time(content.generated_at || content.analyzed_at) }}</span
      >
      <span
        >信息截止：{{
          time(
            content.input_cutoff ||
              content.input_cutoff_at ||
              content.cutoff_at,
          )
        }}</span
      >
    </div>
    <p v-if="content.source_label || content.source" class="small muted">
      来源：{{ content.source_label || content.source }}
    </p>
    <p v-if="content.model_settings" class="small muted">
      本次模型：{{ content.model_settings.model }} · 推理强度：{{
        effortNames[content.model_settings.reasoning_effort] ||
        content.model_settings.reasoning_effort
      }}
    </p>
    <p v-if="content.context" class="small muted">
      资料范围：{{ label(content.context) }}
    </p>
    <p v-if="content.execution_status" class="small">
      本次执行：{{ label(content.execution_status) }}
    </p>
    <p v-if="summary" class="analysis-summary">{{ summary }}</p>
    <section v-if="content.predictions?.length" aria-label="分歧分析所引用预测">
      <h4>本次比较的冻结预测</h4>
      <p class="small muted">仅比较这些记录；差异和可能原因不构成综合预测。</p>
      <ul>
        <li v-for="row in content.predictions" :key="row.id">
          {{ row.scheme }} · {{ row.id }} · 生成 {{ time(row.generated_at) }}
        </li>
      </ul>
    </section>
    <p v-if="content.stance" class="small">
      判断方向：{{ label(content.stance) }}
    </p>
    <p v-if="content.reason" class="note">{{ text(content.reason) }}</p>
    <p
      v-for="reason in content.reason_codes || []"
      :key="String(reason)"
      class="small muted"
    >
      {{ label(reason) }}
    </p>
    <p v-if="!hasReading" class="muted">
      该记录尚无可展示的文字分析或资讯证据。缺失信息不会被补写成预测结论。
    </p>
    <div v-if="positive.length || negative.length" class="factor-columns">
      <section v-if="positive.length">
        <h4>支持因素</h4>
        <ul>
          <li v-for="(item, i) in positive" :key="i">{{ item }}</li>
        </ul>
      </section>
      <section v-if="negative.length">
        <h4>反向因素</h4>
        <ul>
          <li v-for="(item, i) in negative" :key="i">{{ item }}</li>
        </ul>
      </section>
    </div>
    <section v-if="mechanisms.length">
      <h4>影响机制</h4>
      <ul>
        <li v-for="(item, i) in mechanisms" :key="i">{{ item }}</li>
      </ul>
    </section>
    <section v-if="facts.length">
      <h4>引用事实</h4>
      <ul>
        <li v-for="(item, i) in facts" :key="i">{{ item }}</li>
      </ul>
    </section>
    <section v-if="inferences.length">
      <h4>分析推断</h4>
      <ul>
        <li v-for="(item, i) in inferences" :key="i">{{ item }}</li>
      </ul>
    </section>
    <section v-if="uncertainties.length">
      <h4>不确定性</h4>
      <ul>
        <li v-for="(item, i) in uncertainties" :key="i">{{ item }}</li>
      </ul>
    </section>
    <section v-if="invalidation.length">
      <h4>判断失效条件</h4>
      <ul>
        <li v-for="(item, i) in invalidation" :key="i">{{ item }}</li>
      </ul>
    </section>
    <section v-if="documents.length">
      <h4>原始资料与引用</h4>
      <p v-if="content.evidence_ids?.length" class="small muted">
        本次明确引用的证据编号：{{
          content.evidence_ids.join("、")
        }}。下列资料目录可包含未被本次结论引用的输入材料。
      </p>
      <article
        v-for="(document, i) in documents"
        :key="document.id || i"
        class="evidence-document"
      >
        <a
          v-if="safeLink(document.url || document.source_url)"
          :href="safeLink(document.url || document.source_url)"
          target="_blank"
          rel="noopener noreferrer"
          >{{ document.title || "原始资料" }} ↗</a
        >
        <strong v-else>{{
          document.title || document.source_name || "冻结资料"
        }}</strong>
        <p class="small muted">
          {{
            document.provider ||
            document.source ||
            document.source_name ||
            "来源见原始资料"
          }}
          · 发布时间 {{ time(document.published_at || document.publish_time) }}
          <span v-if="document.first_observed_at">
            · 首次获得 {{ time(document.first_observed_at) }}</span
          >
        </p>
        <p>
          {{
            document.excerpt ||
            document.summary ||
            document.text ||
            "原记录没有保存可展示的正文摘录。"
          }}
        </p>
        <p v-if="document.evidence_id || document.id" class="small muted">
          证据编号 {{ document.evidence_id || document.id }}
        </p>
      </article>
    </section>
  </div>
</template>

<style scoped>
.forecast-evidence {
  line-height: 1.9;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.evidence-time {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  color: #748697;
  font-size: 12px;
}
.analysis-summary {
  margin: 16px 0;
  font-size: 15px;
  color: #203447;
}
h4 {
  margin: 16px 0 6px;
  font-size: 13px;
}
ul {
  margin: 4px 0;
  padding-left: 22px;
}
.factor-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.evidence-document {
  border-left: 2px solid #dce5ec;
  padding-left: 14px;
  margin: 16px 0;
}
a {
  color: #087f8c;
}
@media (max-width: 600px) {
  .factor-columns {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
