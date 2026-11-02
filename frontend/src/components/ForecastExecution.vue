<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, time, type Row } from "../api";
import { label as nativeLabel } from "../funds/client";

const props = defineProps<{ selection?: Row }>();
const emit = defineEmits<{ refreshed: [] }>();
const route = useRoute(),
  router = useRouter();
const assets = ref<Row[]>([]),
  runs = ref<Row[]>([]);
const runtime = ref<Row>({});
const loading = ref(false),
  submitting = ref(false),
  evaluating = ref(false),
  preparing = ref(""),
  error = ref(""),
  notice = ref("");
const asset = computed(
  () =>
    assets.value.find((item) => item.id === route.query.asset) ||
    assets.value[0],
);
const target = computed<Row | undefined>(
  () =>
    asset.value?.targets.find((item: Row) => item.id === route.query.target) ||
    asset.value?.targets[0],
);
const horizon = computed(() =>
  target.value?.horizons.includes(Number(route.query.horizon))
    ? Number(route.query.horizon)
    : target.value?.horizons[0],
);
const modes = computed<string[]>(() => target.value?.data_modes || ["REAL"]);
const context = computed<Row>(
  () =>
    props.selection || {
      asset: asset.value?.id,
      target: target.value?.id,
      horizon: String(horizon.value),
      mode: modes.value.includes(String(route.query.mode))
        ? String(route.query.mode)
        : "REAL",
    },
);
const query = computed(() =>
  context.value.asset && context.value.target
    ? new URLSearchParams(context.value).toString()
    : "",
);
const runnable = computed(
  () =>
    query.value &&
    context.value.mode === "REAL" &&
    runtime.value.enabled !== false,
);
let ticket = 0,
  requestKey = "",
  requestContext = "",
  preparationKey = "",
  preparationContext = "",
  timer: ReturnType<typeof setInterval> | undefined;
const labels: Row = {
  QUEUED: "等待执行",
  RUNNING: "执行中",
  COMPLETED: "全部完成",
  PARTIAL: "部分完成",
  BLOCKED: "暂不可执行",
  SUCCEEDED: "已生成预测",
  REUSED: "复用相同输入结果",
  ABSTAIN: "信息不足，未给出预测",
  FAILED: "执行失败",
  FUND_LIGHTGBM: "基金 LightGBM",
  FUND_XGBOOST: "基金 XGBoost",
  CLOSE_LIGHTGBM: "ETF LightGBM",
  CLOSE_XGBOOST: "ETF XGBoost",
  GOLD_LIGHTGBM: "黄金 LightGBM",
  GOLD_XGBOOST: "黄金 XGBoost",
  LGBM_BASE: "LightGBM",
  XGB_BASE: "XGBoost",
  lightgbm: "LightGBM",
  xgboost: "XGBoost",
  DEEPSEEK: "DeepSeek",
  GPT: "GPT 独立预测",
  GPT_DISAGREEMENT: "GPT 分歧分析（历史任务）",
  GPT_DISAGREEMENT_DISABLED: "GPT 分歧分析已关闭，未发起调用",
  GPT_DISABLED_OR_PROXY_UNCONFIGURED: "GPT 未启用或专属代理未配置",
  INSUFFICIENT_COMPARABLE_PREDICTIONS: "相同截止的基础预测不足两条",
  GPT_PROXY_OR_API_FAILED: "GPT 代理或服务连接失败，未直连重试",
  MODEL_NOT_READY: "尚无可用量化模型，请先准备模型",
  NO_QUALIFIED_NEWS: "没有符合来源和时点要求的资讯",
  DEEPSEEK_DISABLED_OR_UNCONFIGURED: "DeepSeek 未启用或尚未配置",
  SHARED_DAILY_BUDGET_EXCEEDED: "当日共享调用预算不足",
  GPT_DAILY_BUDGET_EXCEEDED: "GPT当日调用预算不足",
  DEEPSEEK_DAILY_BUDGET_EXCEEDED: "DeepSeek当日调用预算不足",
  PREDICTION_LATE_FOR_TARGET: "已超过本目标的预测提交时限",
  BILLING_ALREADY_RESERVED_OR_UNKNOWN: "已有费用预留或费用待核实，未重复调用",
  ORIGINAL_PREDICTION_TIME_PRESERVED: "复用原预测，保留原生成时间",
  NAV_UNAVAILABLE: "暂无可用于预测的基金净值",
  LATEST_MARKET_UNAVAILABLE: "最新所需行情尚未齐备",
  FUND_NAV_OBSERVATIONS: "个有效净值观测",
  TRADING_DAYS: "个交易日",
  SGE_TRADING_DAYS: "个上金所交易日",
};

/** 所有执行结果保留真实状态，包括失败、弃权和复用。 */
function label(value: unknown): string {
  return labels[String(value)] || nativeLabel(value);
}
/** 管理入口按资产和目标选择，不再把七资产误送到旧 ETF 日终或训练。 */
function change(key: string, value: string) {
  const next: Row = { ...context.value, [key]: value, record: undefined };
  if (key === "asset") {
    next.target = undefined;
    next.horizon = undefined;
    next.mode = "REAL";
  }
  if (key === "target") {
    next.horizon = undefined;
    next.mode = "REAL";
  }
  router.replace({ query: next });
}
/** 获取已保存批次；读取或定时刷新不会触发付费能力。 */
async function load() {
  const current = ++ticket;
  if (!query.value || context.value.mode !== "REAL") {
    runs.value = [];
    runtime.value = {};
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await api(`/forecast/runs?${query.value}`);
    if (current === ticket) {
      runs.value = result.runs || [];
      runtime.value = result;
      error.value = "";
    }
  } catch (e) {
    if (current === ticket)
      error.value = e instanceof Error ? e.message : "批次读取失败";
  } finally {
    if (current === ticket) loading.value = false;
  }
}
/** 准备仅进入当前原生资产任务，不额外触发预测或付费分析；失败重试沿用身份。 */
async function prepare(action: "SYNC" | "TRAIN") {
  if (!runnable.value || preparing.value) return;
  preparing.value = action;
  error.value = "";
  notice.value = "";
  const submittedQuery = query.value;
  const keyContext = `${submittedQuery}:${action}`;
  if (!preparationKey || preparationContext !== keyContext) {
    preparationKey = crypto.randomUUID();
    preparationContext = keyContext;
  }
  try {
    const result = await api("/forecast/prepare", {
      ...context.value,
      horizon: Number(context.value.horizon),
      action,
      idempotency_key: preparationKey,
    });
    preparationKey = "";
    if (submittedQuery !== query.value) return;
    notice.value = `已提交${action === "SYNC" ? "行情更新" : "量化模型准备"}任务 ${result.task_id || result.id}，可在系统管理查看任务进度。`;
    emit("refreshed");
  } catch (e) {
    if (submittedQuery === query.value)
      error.value = e instanceof Error ? e.message : "准备任务提交失败";
  } finally {
    preparing.value = "";
  }
}
/** 显式运行沿用失败请求的幂等键；切换组合或成功后才开始新的请求身份。 */
async function createRun() {
  if (!runnable.value || submitting.value) return;
  submitting.value = true;
  error.value = "";
  notice.value = "";
  if (!requestKey || requestContext !== query.value) {
    requestKey = crypto.randomUUID();
    requestContext = query.value;
  }
  const submittedQuery = query.value;
  try {
    const result = await api("/forecast/runs", {
      ...context.value,
      horizon: Number(context.value.horizon),
      idempotency_key: requestKey,
    });
    requestKey = "";
    if (submittedQuery !== query.value) return;
    notice.value = `已提交预测批次 ${result.run_id || result.id || result.task_id}，请查看各个体的执行结果。`;
    await load();
  } catch (e) {
    if (submittedQuery === query.value)
      error.value = e instanceof Error ? e.message : "预测提交失败";
  } finally {
    submitting.value = false;
  }
}
/** 到期核验只生成真实结果和评分，不重新调用模型修改预测。 */
async function evaluate() {
  if (!runnable.value || evaluating.value) return;
  evaluating.value = true;
  error.value = "";
  const submittedQuery = query.value;
  try {
    const result = await api("/forecast/evaluate", {
      ...context.value,
      horizon: Number(context.value.horizon),
    });
    if (submittedQuery === query.value)
      notice.value = `已提交到期核验任务 ${result.task_id || result.id}。`;
  } catch (e) {
    if (submittedQuery === query.value)
      error.value = e instanceof Error ? e.message : "核验任务提交失败";
  } finally {
    evaluating.value = false;
  }
}
/** 对执行结果打开统一详情，保留资产目标与期限身份。 */
function resultLink(item: Row) {
  return {
    path: "/forecast",
    query: {
      ...context.value,
      asset: item.asset,
      target: item.target,
      horizon: String(item.horizon),
      record: `unified:${item.result_id}`,
      view: "analysis",
    },
  };
}
/** 全矩阵批次保留整批状态，明细只展示当前组合，防止把其他资产误读为本资产预测。 */
function runItems(run: Row): Row[] {
  return (run.items || []).filter(
    (item: Row) =>
      item.asset === context.value.asset &&
      item.target === context.value.target &&
      Number(item.horizon) === Number(context.value.horizon),
  );
}
watch(query, () => {
  runs.value = [];
  notice.value = "";
  error.value = "";
  load();
});
onMounted(async () => {
  if (!props.selection) {
    try {
      assets.value = (await api("/forecast/assets")).assets || [];
    } catch (e) {
      error.value = e instanceof Error ? e.message : "资产目录读取失败";
    }
  } else await load();
  timer = setInterval(async () => {
    if (
      !loading.value &&
      runs.value.some((run) => ["QUEUED", "RUNNING"].includes(run.status))
    ) {
      await load();
      if (!runs.value.some((run) => ["QUEUED", "RUNNING"].includes(run.status)))
        emit("refreshed");
    }
  }, 5000);
});
onBeforeUnmount(() => {
  ticket++;
  if (timer) clearInterval(timer);
});
</script>

<template>
  <section class="panel forecast-execution" aria-label="统一预测运行">
    <div class="panel-head">
      <h2>预测运行与批次</h2>
      <el-button :loading="loading" @click="load">刷新批次状态</el-button>
    </div>
    <div v-if="!selection" class="execution-selectors">
      <label
        >运行资产<select
          aria-label="运行资产"
          :value="asset?.id"
          @change="change('asset', ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="item in assets" :key="item.id" :value="item.id">
            {{ item.name }} · {{ item.code }}
          </option>
        </select></label
      >
      <label
        >运行目标<select
          aria-label="运行目标"
          :value="target?.id"
          @change="change('target', ($event.target as HTMLSelectElement).value)"
        >
          <option
            v-for="item in asset?.targets || []"
            :key="item.id"
            :value="item.id"
          >
            {{ item.label }}
          </option>
        </select></label
      >
      <label
        >运行期限<select
          aria-label="运行期限"
          :value="horizon"
          @change="
            change('horizon', ($event.target as HTMLSelectElement).value)
          "
        >
          <option
            v-for="item in target?.horizons || []"
            :key="item"
            :value="item"
          >
            {{ item }} {{ label(target?.horizon_unit) }}
          </option>
        </select></label
      >
      <label
        >运行数据模式<select
          aria-label="运行数据模式"
          :value="context.mode"
          @change="change('mode', ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="item in modes" :key="item" :value="item">
            {{ item === "REAL" ? "真实数据研究" : "演示数据（只读）" }}
          </option>
        </select></label
      >
    </div>
    <p class="small muted">
      对当前资产、目标和期限组织量化与 DeepSeek
      独立预测。真实大模型调用受已配置预算约束；信息不足会弃权，失败不会补成有效预测。刷新页面只读取已保存数据。
    </p>
    <p v-if="runtime.automatic_enabled != null" class="small">
      统一自动运行：{{
        runtime.automatic_enabled
          ? "已启用"
          : "尚未接管自动调度，可明确提交手动批次"
      }}。
    </p>
    <p v-if="runtime.enabled === false" class="note">
      统一预测执行当前未启用，请先在运行配置中核对状态。
    </p>
    <div class="toolbar execution-actions">
      <el-button
        :disabled="!runnable || !!preparing"
        :loading="preparing === 'SYNC'"
        @click="prepare('SYNC')"
        >更新当前资产行情</el-button
      >
      <el-button
        :disabled="!runnable || !!preparing"
        :loading="preparing === 'TRAIN'"
        @click="prepare('TRAIN')"
        >准备当前目标量化模型</el-button
      >
      <el-button
        type="primary"
        :disabled="!runnable"
        :loading="submitting"
        @click="createRun"
        >运行当前组合预测</el-button
      >
      <el-button :disabled="!runnable" :loading="evaluating" @click="evaluate"
        >核验当前组合到期结果</el-button
      >
    </div>
    <p class="small muted">
      行情更新与模型准备进入现有任务队列，不调用付费
      AI，也不直接产生预测。基金与黄金沿用原生训练，一次准备该资产两个期限；完成后可运行当前组合预测。
      <router-link :to="{ path: '/system', query: context }"
        >查看任务进度</router-link
      >
    </p>
    <p v-if="context.mode !== 'REAL'" class="note">
      演示模式只用于读取历史，不能发起真实预测。
    </p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <p
      v-if="context.mode === 'REAL' && !loading && !runs.length && !error"
      class="muted"
    >
      当前组合尚无统一运行批次。可以查看已有历史预测，或明确运行新批次。
    </p>
    <article v-for="run in runs" :key="run.id" class="forecast-run">
      <div class="panel-head">
        <strong>整批状态：{{ label(run.status) }}</strong
        ><span class="small"
          >创建 {{ time(run.created_at) }} · 信息截止
          {{ time(run.cutoff_at) }}</span
        >
      </div>
      <p class="small muted">
        批次 {{ run.id }} · 以下仅列当前资产、目标和期限的预测个体。
      </p>
      <p v-if="!runItems(run).length" class="small muted">
        此批次尚无当前组合的执行项。
      </p>
      <div class="run-table-wrap">
        <table>
          <thead>
            <tr>
              <th>预测个体</th>
              <th>状态</th>
              <th>原因与结果</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in runItems(run)" :key="item.id">
              <td>{{ label(item.scheme) }}</td>
              <td>{{ label(item.status) }}</td>
              <td>
                <span v-if="item.error_code || item.reason">{{
                  label(item.error_code || item.reason)
                }}</span
                ><router-link v-if="item.result_id" :to="resultLink(item)"
                  >查看本次预测与依据</router-link
                ><span
                  v-if="!item.result_id && !item.error_code && !item.reason"
                  >尚无有效结果</span
                >
              </td>
              <td>{{ time(item.updated_at) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </article>
  </section>
</template>

<style scoped>
.forecast-execution {
  line-height: 1.8;
}
.execution-selectors {
  display: grid;
  grid-template-columns: 2fr 2fr 1.3fr 1.2fr;
  gap: 16px;
  margin-bottom: 18px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  min-width: 0;
}
select {
  padding: 10px 8px;
  background: white;
  border: 1px solid #dce5ec;
  border-radius: 7px;
  min-width: 0;
  width: 100%;
  color: #203447;
}
.execution-actions {
  flex-wrap: wrap;
  margin: 16px 0;
}
.forecast-run {
  border-top: 1px solid #e5ecf1;
  padding-top: 14px;
  margin-top: 18px;
  overflow-wrap: anywhere;
}
.run-table-wrap {
  overflow-x: auto;
}
table {
  border-collapse: collapse;
  width: 100%;
  text-align: left;
  font-size: 12px;
}
th,
td {
  padding: 9px 10px;
  border-bottom: 1px solid #e5ecf1;
  min-width: 100px;
}
a {
  color: #087f8c;
  display: block;
}
@media (max-width: 700px) {
  .execution-selectors {
    grid-template-columns: 1fr;
  }
  .panel-head {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
