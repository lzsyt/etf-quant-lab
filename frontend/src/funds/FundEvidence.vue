<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { time, percent, quantAlgorithm, type Row } from "../api";
import { fundRead, label, safeLink, type FundMode } from "./client";
const route = useRoute();
const props = defineProps<{ code: string; horizon: number; mode: FundMode }>();
const loading = ref(true),
  error = ref(""),
  summary = ref<Row>({}),
  latest = ref<Row>({}),
  source = ref("frozen"),
  filter = ref("ALL"),
  detail = ref<Row | null>(null);
let generation = 0;
const forecast = computed(() => summary.value.forecast);
const news = computed<Row>(() =>
  source.value === "latest"
    ? latest.value
    : forecast.value?.news_snapshot ||
      forecast.value?.news ||
      summary.value.news ||
      {},
);
const docs = computed<Row[]>(() =>
  (news.value.documents || []).filter(
    (d: Row) =>
      filter.value === "ALL" ||
      (filter.value === "POSITIVE"
        ? d.stance === "POSITIVE"
        : d.stance === "NEGATIVE" || d.risk_flags?.length),
  ),
);
/** 冻结依据与最新资料保持两条读取路径，页面筛选只改变本地展示。 */
async function load() {
  const ticket = ++generation;
  loading.value = true;
  error.value = "";
  summary.value = {};
  latest.value = {};
  try {
    const [s, n] = await Promise.all([
      fundRead(
        `/funds/${props.code}/research-summary?horizon=${props.horizon}`,
        props.mode,
      ),
      fundRead(`/funds/${props.code}/news`, props.mode),
    ]);
    if (ticket !== generation) return;
    if (route.query.forecast) {
      const frozen = await fundRead(
        `/forecasts/${encodeURIComponent(String(route.query.forecast))}`,
        props.mode,
      );
      if (ticket !== generation) return;
      if (
        frozen.fund_code !== props.code ||
        !(frozen.points || [frozen]).some(
          (p: Row) => Number(p.horizon) === props.horizon,
        )
      )
        throw new Error("所选冻结批次与当前基金或窗口不一致，请重新选择");
      summary.value = {
        ...s,
        forecast: frozen,
        forecast_id: frozen.id,
        news: frozen.news_snapshot || null,
        usage: frozen.usage_mode || "MODEL_ONLY",
      };
    } else summary.value = s;
    latest.value = n;
  } catch (e) {
    if (ticket === generation)
      error.value = e instanceof Error ? e.message : "依据读取失败";
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
watch(
  () => [props.code, props.horizon, props.mode, route.query.forecast],
  load,
  { immediate: true },
);
</script>
<template>
  <el-alert v-if="error" type="error" :title="error" :closable="false"
    ><el-button @click="load">重试</el-button></el-alert
  >
  <el-skeleton v-if="loading" :rows="8" animated />
  <template v-else>
    <div class="note">
      <strong>{{ label(summary.usage || "NO_VALID_SIGNAL") }}</strong>
      <p class="small">
        {{
          forecast
            ? `预测 ${forecast.id} · ${time(forecast.generated_at)} · ${horizon} 净值观察日`
            : "尚无可绑定的冻结预测，最新资料与研究判断分别展示。"
        }}
      </p>
      <p>暂无已验证融合收益预测；新闻评分不是上涨概率，也不是预期收益率。</p>
    </div>
    <div class="toolbar">
      <el-radio-group v-model="source" aria-label="新闻时间口径"
        ><el-radio-button value="frozen">本次冻结依据</el-radio-button
        ><el-radio-button value="latest"
          >最新新闻资料</el-radio-button
        ></el-radio-group
      >
    </div>
    <p v-if="source === 'latest'" class="note warning-note">
      这里是后来更新的资料，不能用于改写较早预测的解释。
    </p>
    <div class="grid-equal">
      <section class="panel">
        <h2>DS 新闻解读</h2>
        <div class="fund-reading">
          <strong>{{
            label(news.analysis_status || "NEWS_UNAVAILABLE")
          }}</strong>
          <p>{{ label(news.forecast_usage || "NOT_USED") }}</p>
          <p>倾向：{{ label(news.stance) }}</p>
          <p>
            新闻分数：{{ news.sentiment_score ?? "—（无有效分数）" }}
            <span class="small">{{
              news.score_scale
                ? JSON.stringify(news.score_scale)
                : "仅表达新闻解读，不代表概率或收益"
            }}</span>
          </p>
          <p>
            资料 {{ news.documents?.length || 0 }} 条 · 分析时间
            {{ time(news.analyzed_at || news.generated_at) }}
          </p>
          <p v-for="r in news.reason_codes || []" :key="r">{{ label(r) }}</p>
          <p v-for="u in news.uncertainties || []" :key="String(u)">
            {{ typeof u === "string" ? u : u.text }}
          </p>
        </div>
      </section>
      <section class="panel">
        <h2>历史数据模型判断</h2>
        <div v-if="forecast" class="fund-reading">
          <p>预测个体：基金 {{ quantAlgorithm(forecast) }}</p>
          <p>
            {{ forecast.model_version_id || forecast.model_id }} ·
            {{ horizon }} 净值观察日
          </p>
          <p>
            {{ label(forecast.capability) }} ·
            {{ label(forecast.validation_status) }}
          </p>
          <p>输入截止 {{ time(forecast.input_cutoff_at) }}</p>
          <p>研究目标 {{ label(forecast.target_basis) }}</p>
          <p v-for="p in forecast.points || [forecast]" :key="p.horizon">
            {{ p.horizon }} 日：{{
              p.point_return != null
                ? percent(p.point_return)
                : p.probability_up != null
                  ? `上涨概率 ${percent(p.probability_up)}`
                  : "暂无有效输出"
            }}
          </p>
          <details v-if="forecast.feature_importance">
            <summary>全局特征重要性（不是本次因果解释）</summary>
            <pre class="text-output">{{
              JSON.stringify(forecast.feature_importance, null, 2)
            }}</pre>
          </details>
        </div>
        <div v-else class="empty">尚无这个窗口的有效模型判断</div>
        <p class="small muted">
          资产类别决定解释方式；短债的低波动不等于“不值得投入”。
        </p>
      </section>
    </div>
    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>资料、原始事实与推断</h2>
          <p>发布时间、首次获取时间和实际参与情况逐条保留</p>
        </div>
        <el-radio-group v-model="filter" size="small" aria-label="新闻筛选"
          ><el-radio-button value="ALL">全部</el-radio-button
          ><el-radio-button value="POSITIVE">偏积极</el-radio-button
          ><el-radio-button value="RISK"
            >风险提示</el-radio-button
          ></el-radio-group
        >
      </div>
      <div v-if="!docs.length" class="empty">
        {{
          source === "frozen"
            ? "本次没有符合条件的冻结资料"
            : "当前没有符合筛选条件的资料"
        }}<br />资料不足不解释为“中性”。
      </div>
      <article v-for="d in docs" :key="d.id || d.document_id" class="news-card">
        <el-button text class="fund-news-title" @click="detail = d">{{
          d.title
        }}</el-button>
        <p>{{ d.excerpt || d.summary || "仅有标题，未取得可引用正文" }}</p>
        <div class="news-meta">
          <span>{{ d.source_name || d.source }}</span
          ><span>发布 {{ time(d.published_at) }}</span
          ><span
            >首次获取 {{ time(d.first_seen_at || d.first_observed_at) }}</span
          ><span>{{ d.content_type || "材料范围待确认" }}</span
          ><span>{{
            d.selected_for_analysis ? "已选入分析" : "仅作资料展示"
          }}</span>
        </div>
        <p>
          {{
            d.relevance_reason || d.relevance_basis || "相关性依据见原始材料"
          }}
        </p>
      </article>
      <details
        v-if="news.facts?.length || news.inferences?.length"
        class="fund-details"
      >
        <summary>完整分析引用</summary>
        <h3>原始事实</h3>
        <p v-for="f in news.facts || []" :key="f.text" class="fund-reading">
          {{ f.text }} · {{ (f.source_ids || []).join("、") }}
        </p>
        <h3>模型推断</h3>
        <p
          v-for="i in news.inferences || []"
          :key="i.text"
          class="fund-reading"
        >
          {{ i.text }}
        </p>
      </details>
    </section>
    <el-dialog
      :model-value="!!detail"
      @update:model-value="detail = null"
      title="资料详情：事实与推断"
      width="720px"
      ><template v-if="detail"
        ><h2>{{ detail.title }}</h2>
        <div class="fund-reading">
          <p>材料范围：{{ detail.content_type || "未知" }}</p>
          <h3>来源事实 / 有限摘要</h3>
          <p>
            {{ detail.excerpt || detail.summary || "仅提供标题，不补写全文" }}
          </p>
          <h3>DS 推断</h3>
          <p>{{ detail.inference || "没有经校验、与此材料绑定的模型推断" }}</p>
          <h3>影响路径与不确定性</h3>
          <p>
            {{
              detail.relevance_reason || "见实际分析引用；不自行补写影响路径"
            }}
          </p>
          <p>{{ detail.uncertainty || "资料本身不能证明未来价格或收益" }}</p>
          <p>资料 ID：{{ detail.id || detail.document_id }}</p>
          <p>
            发布时间：{{ time(detail.published_at) }}<br />首次获取：{{
              time(detail.first_seen_at)
            }}
          </p>
          <a
            v-if="safeLink(detail.source_url || detail.url)"
            :href="safeLink(detail.source_url || detail.url)"
            target="_blank"
            rel="noopener noreferrer"
            class="fund-link"
            >打开原始来源 ↗</a
          >
          <p v-else>无可安全打开的来源链接</p>
        </div></template
      ><template #footer
        ><el-button @click="detail = null">关闭</el-button></template
      ></el-dialog
    >
  </template>
</template>
