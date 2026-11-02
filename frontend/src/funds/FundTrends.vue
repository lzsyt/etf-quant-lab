<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api, number, percent, time, quantAlgorithm, type Row } from "../api";
import Chart from "../components/Chart.vue";
import { fundRead, fundWrite, label, type FundMode } from "./client";
const props = defineProps<{ code: string; horizon: number; mode: FundMode }>();
const emit = defineEmits<{ horizon: [number]; navLoaded: [Row] }>();
const route = useRoute();
const router = useRouter();
const loading = ref(true),
  error = ref(""),
  nav = ref<Row>({}),
  history = ref<Row>({}),
  selectedId = ref(""),
  view = ref("latest"),
  detail = ref<Row | null>(null);
const actual = ref(true),
  prediction = ref(true),
  interval = ref(false),
  taskBusy = ref(false),
  task = ref<Row | null>(null);
let taskKey = "",
  taskType = "";
let generation = 0;
let loadedKey = "";
const runs = computed<Row[]>(() => history.value.forecasts || []);
const selected = computed<Row | undefined>(
  () => runs.value.find((r) => r.id === selectedId.value) || runs.value[0],
);
/** 当前进度独立于冻结证据。 */
function timing(run?: Row): Row | undefined {
  return run ? history.value.current_timing?.[run.id] : undefined;
}
/** 参考日期明确标识估算，实际终点来自独立到期评价。 */
function targetText(run: Row, point: Row): string {
  if (point.actual_target_date)
    return `实际观测终点 ${point.actual_target_date}`;
  if (point.target_date) return point.target_date;
  const current = timing(run);
  if (current?.estimated_target_date)
    return `参考 ${current.estimated_target_date}（交易所日历估算）`;
  return current?.estimate_status === "CALENDAR_COVERAGE_INSUFFICIENT"
    ? "参考日期待后续官方日历覆盖"
    : "目标日期未确认";
}
/** 支持接口的显式点集合；单点旧形状也必须保留真实能力元数据。 */
function points(run?: Row): Row[] {
  if (!run) return [];
  return (run.points || [run]).map((point: Row) => ({
    ...point,
    ...(run.evaluation &&
    (!run.evaluation.horizon ||
      Number(run.evaluation.horizon) === Number(point.horizon))
      ? run.evaluation
      : {}),
  }));
}
const selectedPoints = computed(() =>
  points(selected.value).filter(
    (p) => Number(p.horizon || selected.value?.horizon) === props.horizon,
  ),
);
const intervalAvailable = computed(
  () =>
    selected.value?.capability === "RETURN_QUANTILES" &&
    selected.value?.calibration_status === "ACCEPTED",
);
const plottedObservations = computed<Row[]>(() => {
  const rows: Row[] = nav.value.observations || [];
  const run = selected.value;
  if (!run?.anchor_date) return rows;
  const before = rows
    .filter((n) => n.nav_date <= run.anchor_date)
    .slice(-props.horizon);
  if (view.value === "latest") return before;
  const target = selectedPoints.value
    .map((p) => p.target_date)
    .filter(Boolean)
    .sort()
    .at(-1);
  return [
    ...before,
    ...rows.filter(
      (n) => n.nav_date > run.anchor_date && (!target || n.nav_date <= target),
    ),
  ];
});
const dates = computed(() =>
  Array.from(
    new Set([
      ...plottedObservations.value.map((n: Row) => n.nav_date),
      ...selectedPoints.value.map((p) => p.target_date).filter(Boolean),
    ]),
  ).sort(),
);
const option = computed(() => {
  const observations: Row[] = plottedObservations.value;
  const values = new Map(
    observations.map((n) => [n.nav_date, Number(n.unit_nav)]),
  );
  const series: Row[] = [];
  if (actual.value)
    series.push({
      name: "实际单位净值",
      type: "line",
      showSymbol: false,
      connectNulls: false,
      data: dates.value.map((d) => values.get(d) ?? null),
      lineStyle: { width: 2 },
    });
  const run = selected.value;
  if (
    prediction.value &&
    run &&
    ["RETURN_POINT", "RETURN_QUANTILES", "MULTI_HORIZON_POINTS"].includes(
      run.capability,
    ) &&
    ["NAV_CHANGE", "FUND_NAV_CHANGE"].includes(run.target_basis)
  ) {
    const endpoint = new Map(
      selectedPoints.value
        .filter(
          (p) =>
            p.point_return != null &&
            p.target_date &&
            (p.anchor_value ?? run.anchor_value) != null,
        )
        .map((p) => [
          p.target_date,
          Number(p.anchor_value ?? run.anchor_value) *
            (1 + Number(p.point_return)),
        ]),
    );
    series.push({
      name: "预测终点（非每日路径）",
      type: "line",
      symbol: "diamond",
      symbolSize: 12,
      showAllSymbol: true,
      lineStyle: { opacity: 0 },
      connectNulls: false,
      data: dates.value.map((d) => endpoint.get(d) ?? null),
    });
    if (interval.value && intervalAvailable.value)
      for (const side of ["lower", "upper"]) {
        const bands = new Map(
          selectedPoints.value
            .filter((p) => p.quantiles?.[side] != null)
            .map((p) => [
              p.target_date,
              Number(p.anchor_value ?? run.anchor_value) *
                (1 + Number(p.quantiles[side])),
            ]),
        );
        series.push({
          name: side === "lower" ? "已校准区间下界" : "已校准区间上界",
          type: "line",
          symbol: "rect",
          symbolSize: 7,
          lineStyle: { opacity: 0 },
          data: dates.value.map((d) => bands.get(d) ?? null),
        });
      }
  }
  return {
    animation: false,
    tooltip: { trigger: "axis" },
    legend: { bottom: 0, type: "scroll" },
    grid: { left: 55, right: 20, top: 25, bottom: 65 },
    xAxis: { type: "category", data: dates.value, boundaryGap: false },
    yAxis: { type: "value", scale: true, name: "单位净值" },
    series,
  };
});
const evaluationText = computed(() => {
  const run = selected.value,
    p = selectedPoints.value[0];
  if (!run) return "尚未积累该基金、该窗口的有效预测。可以先查看真实净值。";
  if (run.capability === "DIRECTION_ONLY")
    return "当前模型只预测方向，不提供价格路径。上涨概率不是涨幅。";
  if (!p?.target_date && p?.actual_return == null) {
    const current = timing(run);
    return current
      ? `已积累 ${current.observed}/${current.required} 个后续有效净值观测，剩余 ${current.remaining} 个。参考日期按交易所日历估算，不是基金确认日；实际到期以净值观测为准。`
      : "未来目标日期尚未确认；达到所需有效净值观测数后进行核验。";
  }
  if (p.actual_return == null)
    return (
      label(p.evaluation_status || run.evaluation_status || "NOT_DUE") +
      "，不计入预测成绩。"
    );
  return `预测 ${percent(p.point_return)}，实际 ${percent(p.actual_return)}；终点相差 ${percent(Math.abs(Number(p.point_return) - Number(p.actual_return)))}。`;
});
/** 并行读取只读资源，基金与窗口切换后放弃旧请求结果。 */
async function load() {
  const ticket = ++generation;
  loading.value = true;
  error.value = "";
  const key = `${props.mode}:${props.code}:${props.horizon}`;
  if (loadedKey !== key) {
    nav.value = {};
    history.value = {};
    selectedId.value = "";
  }
  try {
    const [n, h] = await Promise.all([
      fundRead(`/funds/${props.code}/nav`, props.mode),
      fundRead(
        `/funds/${props.code}/forecasts?horizon=${props.horizon}`,
        props.mode,
      ),
    ]);
    if (ticket !== generation) return;
    nav.value = n;
    history.value = h;
    loadedKey = key;
    selectedId.value =
      h.forecasts?.find((run: Row) => run.id === route.query.forecast)?.id ||
      h.forecasts?.[0]?.id ||
      "";
    emit("navLoaded", n);
  } catch (e) {
    if (ticket === generation)
      error.value = e instanceof Error ? e.message : "读取失败";
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
/** 将用户选择的历史批次传给分析页，避免用最新新闻解释另一条旧预测。 */
function chooseRun() {
  router.replace({
    query: { ...route.query, forecast: selectedId.value || undefined },
  });
}
/** 批次详情只读取冻结记录，不会重训或更新历史预测。 */
async function showDetail(run: Row) {
  try {
    detail.value = await fundRead(`/forecasts/${run.id}`, props.mode);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "详情读取失败";
  }
}
/** 只有用户明确点击才创建后台任务，刷新和切页始终只读。 */
async function createTask(type: string) {
  if (taskBusy.value || props.mode !== "REAL") return;
  taskBusy.value = true;
  error.value = "";
  if (taskType !== type || !taskKey) {
    taskKey = crypto.randomUUID();
    taskType = type;
  }
  try {
    task.value = await fundWrite(
      `/funds/${props.code}/tasks`,
      { type, idempotency_key: taskKey },
      taskKey,
    );
    taskKey = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "任务提交失败";
  } finally {
    taskBusy.value = false;
  }
}
/** 读取任务进度后刷新当前基金，不创建新的后台任务。 */
async function readTask() {
  if (task.value?.id)
    try {
      task.value = await api(`/tasks/${task.value.id}`);
      await load();
    } catch (e) {
      error.value = e instanceof Error ? e.message : "任务读取失败";
    }
}
watch(view, (value) => {
  if (value === "latest") selectedId.value = runs.value[0]?.id || "";
});
watch(
  () => [props.code, props.horizon, props.mode],
  () => {
    task.value = null;
    taskKey = "";
    load();
  },
  { immediate: true },
);
</script>
<template>
  <el-alert v-if="error" type="error" :title="error" :closable="false"
    ><el-button @click="load">重试</el-button></el-alert
  >
  <el-skeleton v-if="loading" :rows="7" animated />
  <template v-else>
    <div class="toolbar">
      <el-radio-group v-model="view" aria-label="预测视图"
        ><el-radio-button value="history">历史对账</el-radio-button
        ><el-radio-button value="latest"
          >最新预测</el-radio-button
        ></el-radio-group
      >
      <el-radio-group
        :model-value="horizon"
        @update:model-value="$emit('horizon', Number($event))"
        aria-label="研究窗口"
        ><el-radio-button :value="20">20 日窗口</el-radio-button
        ><el-radio-button :value="60"
          >60 日窗口</el-radio-button
        ></el-radio-group
      >
    </div>
    <div class="grid-two">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>
              {{ view === "history" ? "核对当时的预测" : "实际走势与最新判断" }}
            </h2>
            <p>
              {{ label(nav.target_basis || "NAV_CHANGE") }} · 参考 ETF
              价格不代替基金净值
            </p>
          </div>
        </div>
        <div v-if="runs.length" class="toolbar">
          <label for="fund-run">预测批次</label
          ><select
            id="fund-run"
            v-model="selectedId"
            class="fund-select"
            @change="chooseRun"
          >
            <optgroup
              v-for="kind in ['FORWARD_VALIDATION', 'HISTORICAL_RESEARCH']"
              :key="kind"
              :label="label(kind)"
            >
              <option
                v-for="r in runs.filter((x) => x.evidence_type === kind)"
                :key="r.id"
                :value="r.id"
              >
                {{ time(r.generated_at) }} · {{ quantAlgorithm(r) }} ·
                {{ r.id }}
              </option>
            </optgroup>
          </select>
        </div>
        <div class="toolbar">
          <el-checkbox v-model="actual">实际走势</el-checkbox
          ><el-checkbox v-model="prediction" :disabled="!selected"
            >预测终点 / 方向</el-checkbox
          ><el-checkbox v-model="interval" :disabled="!intervalAvailable"
            >已验证区间</el-checkbox
          ><span v-if="!intervalAvailable" class="small muted"
            >没有已校准区间</span
          >
        </div>
        <Chart v-if="nav.observations?.length" :option="option" :height="340" />
        <div v-else class="empty">
          <strong>暂无真实净值序列</strong
          >数据加载后展示历史走势，不生成模拟曲线。
        </div>
        <div class="note">{{ evaluationText }}</div>
        <p
          v-if="
            selected?.target_basis &&
            !['NAV_CHANGE', 'FUND_NAV_CHANGE'].includes(selected.target_basis)
          "
          class="small"
        >
          预测采用
          {{
            label(selected.target_basis)
          }}，与单位净值不同，终点只在下表单独展示。
        </p>
        <div class="tag-row">
          <el-tag
            v-for="w in [
              ...(nav.warnings || []),
              ...(history.reason_codes || []),
            ]"
            :key="w"
            type="warning"
            >{{ label(w) }}</el-tag
          >
        </div>
        <details class="fund-details">
          <summary>图表数据表 · 键盘与触摸可读取</summary>
          <div class="fund-table-scroll" tabindex="0">
            <table class="fund-table">
              <caption>
                真实净值记录；未来日期没有实际值
              </caption>
              <thead>
                <tr>
                  <th>日期</th>
                  <th>单位净值</th>
                  <th>来源 / 修订</th>
                  <th>首次获取</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="n in nav.observations || []"
                  :key="`${n.nav_date}-${n.revision}`"
                >
                  <td>{{ n.nav_date }}</td>
                  <td>{{ n.unit_nav }}</td>
                  <td>{{ n.provider }} / {{ n.revision }}</td>
                  <td>{{ time(n.first_observed_at) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      </section>
      <aside>
        <section class="panel">
          <h2>这次预测能说明什么</h2>
          <div v-if="selected" class="fund-reading">
            <p>预测个体：基金 {{ quantAlgorithm(selected) }}</p>
            <p>
              {{ label(selected.capability) }} ·
              {{ label(selected.validation_status) }}
            </p>
            <p>生成：{{ time(selected.generated_at) }}</p>
            <p>输入截止：{{ time(selected.input_cutoff_at) }}</p>
            <p>
              锚点：{{ selected.anchor_date }} /
              {{ selected.anchor_value ?? "—" }}
            </p>
            <div v-for="p in selectedPoints" :key="p.horizon">
              <strong>{{
                p.point_return != null
                  ? percent(p.point_return)
                  : p.probability_up != null
                    ? `上涨概率 ${percent(p.probability_up)}`
                    : "缺少有效输出"
              }}</strong>
              <p>
                目标：{{ targetText(selected, p) }}
                ·
                {{
                  label(
                    timing(selected)?.status ||
                      p.evaluation_status ||
                      "NOT_DUE",
                  )
                }}
              </p>
            </div>
            <p>
              周期单位：{{
                label(selected.horizon_unit || "FUND_NAV_OBSERVATIONS")
              }}。只展示模型实际输出的时间点，不插值为每日预测。
            </p>
            <el-button @click="showDetail(selected)">查看冻结记录</el-button>
          </div>
          <div v-else class="empty">
            {{ label(history.status || "MODEL_NOT_READY") }}
          </div>
          <router-link
            :to="{ path: '/funds/evidence', query: route.query }"
            class="fund-link"
            >查看本次分析依据 →</router-link
          >
        </section>
      </aside>
    </div>
    <section class="panel">
      <h2>预测记录与验证</h2>
      <p class="small muted">
        历史回放与前瞻冻结分别标记；尚未到期的记录不计作零收益。
      </p>
      <div v-if="!runs.length" class="empty">
        尚未积累 {{ horizon }} 个有效净值观测窗口的预测记录
      </div>
      <div v-else class="fund-table-scroll" tabindex="0">
        <table class="fund-table">
          <thead>
            <tr>
              <th>生成时间</th>
              <th>预测个体</th>
              <th>目标日</th>
              <th>窗口 / 类型</th>
              <th>当时判断</th>
              <th>实际结果</th>
              <th>证据与状态</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="r in runs" :key="r.id"
              ><tr v-for="p in points(r)" :key="p.horizon">
                <td>{{ time(r.generated_at) }}</td>
                <td>基金 {{ quantAlgorithm(r) }}</td>
                <td>
                  {{ targetText(r, p) }}
                </td>
                <td>
                  {{ p.horizon || r.horizon }} 日 · {{ label(r.capability) }}
                </td>
                <td>
                  {{
                    p.point_return != null
                      ? percent(p.point_return)
                      : p.probability_up != null
                        ? `上涨概率 ${percent(p.probability_up)}`
                        : "—"
                  }}
                </td>
                <td>{{ percent(p.actual_return) }}</td>
                <td>
                  {{ label(r.evidence_type) }}<br />{{
                    label(
                      timing(r)?.status ||
                        p.evaluation_status ||
                        r.evaluation_status ||
                        "NOT_DUE",
                    )
                  }}
                </td>
                <td><el-button text @click="showDetail(r)">详情</el-button></td>
              </tr></template
            >
          </tbody>
        </table>
      </div>
      <details v-if="history.models?.length" class="fund-details">
        <summary>模型评估摘要与简单基准</summary>
        <pre class="text-output">{{
          JSON.stringify(history.models, null, 2)
        }}</pre>
      </details>
    </section>
    <section v-if="mode === 'REAL'" class="panel">
      <details>
        <summary>数据与研究任务（显式执行）</summary>
        <p class="fund-reading">
          这些操作通过后台队列执行。切换页面、基金或窗口不会训练或抓取；失败原因保留在任务记录中。
        </p>
        <div class="toolbar">
          <el-button :loading="taskBusy" @click="createTask('FUND_NAV')"
            >更新本基金净值</el-button
          ><el-button :disabled="taskBusy" @click="createTask('FUND_TRAIN')"
            >训练 20 / 60 日研究模型</el-button
          ><el-button :disabled="taskBusy" @click="createTask('FUND_FORECAST')"
            >生成冻结预测</el-button
          ><el-button :disabled="taskBusy" @click="createTask('FUND_NEWS')"
            >更新分析依据（按已配置预算）</el-button
          ><el-button :disabled="taskBusy" @click="createTask('FUND_EVALUATE')"
            >验证到期结果</el-button
          >
        </div>
        <p v-if="task" class="small">
          任务 {{ task.id }} · {{ label(task.status || "QUEUED") }}
          {{ task.error_message || "" }}
          <el-button text @click="readTask">读取进度</el-button>
        </p>
      </details>
    </section>
    <el-dialog
      :model-value="!!detail"
      @update:model-value="detail = null"
      title="原始冻结预测与到期验证"
      width="760px"
    >
      <p v-if="detail">预测个体：基金 {{ quantAlgorithm(detail) }}</p>
      <pre class="text-output">{{ JSON.stringify(detail, null, 2) }}</pre>
      <template #footer
        ><el-button @click="detail = null">关闭</el-button></template
      ></el-dialog
    >
  </template>
</template>
