<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { api, money, percent, time, quantAlgorithm, type Row } from "../api";
import Chart from "../components/Chart.vue";
import DataTable from "../components/DataTable.vue";

const data = ref<Row>({});
const busy = ref(false);
const error = ref("");
const tab = ref("outlook");
const selected = ref<Row | null>(null);
const evidenceBusy = ref(false);
const evidenceError = ref("");
const calculating = ref(false);
const costError = ref("");
const cost = ref<Row | null>(null);
const benchmarkManual = ref(false);
const benchmarkSourceDate = ref("");
const csvText = ref("");
const importBusy = ref(false);
const importError = ref("");
const importResult = ref<Row | null>(null);
const purchaseMode = ref("UNIT_PRICE");
const form = reactive({
  quantity: "",
  weight_basis: "GROSS",
  fineness: "0.9999",
  unit_purchase_price: "",
  total_purchase_cost: "",
  purchase_premium_per_gram: "0",
  premium_included: true,
  purchase_fee: "0",
  purchase_fee_included: false,
  benchmark_price: "",
  repurchase_deduction_per_gram: "0",
  repurchase_deduction_rate: "0",
  sale_fixed_fee: "0",
  channel: "",
  quote_kind: "USER_ASSUMPTION",
  quote_at: "",
});
const labels: Row = {
  OK: "正常",
  HEALTHY: "正常",
  AVAILABLE: "可用",
  EMPTY: "空数据",
  MISSING: "信息缺失",
  MISSING_DATA: "缺少有效数据",
  STALE: "数据过期",
  FAILED: "失败",
  PARTIAL: "部分覆盖",
  PARTIAL_COVERAGE: "部分覆盖",
  BLOCKED: "条件不足",
  DISABLED: "已停用",
  NOT_CONNECTED: "未接入",
  NOT_ENABLED: "未启用",
  NEVER_COLLECTED: "尚未采集",
  UNAVAILABLE: "暂不可用",
  INSUFFICIENT_DATA: "信息不足",
  INSUFFICIENT_SAMPLES: "样本不足",
  PENDING: "待验证",
  PENDING_MATURITY: "未到期 · 待验证",
  MATURED: "已到期",
  EVALUATED: "已到期评估",
  ESTIMATED: "预计日期 · 日历未确认",
  ESTIMATED_WEEKDAYS_NOT_CONFIRMED_SGE: "预计日期 · 上金所未来日历未确认",
  CONFIRMED: "交易日历已确认",
  VERIFIED_QUOTE: "已核实渠道报价",
  USER_REPORTED_QUOTE: "用户核实的渠道报价",
  USER_ASSUMPTION: "用户假设",
  CURRENT: "报价有效期内",
  FRESH: "报价有效期内",
  ASSUMPTION: "成本假设",
  QUEUED: "等待执行",
  WAITING_DATA: "等待数据或模型",
  RUNNING: "执行中",
  SUCCEEDED: "已完成",
  EXPERIMENTAL: "实验模型 · 尚无稳定优势",
  NO_STABLE_ADVANTAGE: "尚无稳定优势",
  EXPERIMENTAL_NO_STABLE_ADVANTAGE: "实验模型 · 尚无稳定优势",
  EXPERIMENTAL_OUTPERFORMANCE_NOT_PROVEN: "实验模型 · 优势仍待证明",
  CURRENT_VINTAGE_REPLAY_NOT_STRICT_PIT: "当前修订历史回放 · 非严格点时验证",
  REAL: "真实历史数据",
  HISTORICAL_REPLAY: "历史回放",
  LIVE_FORWARD: "真实前瞻",
  RETROSPECTIVE: "事后历史版本",
  DATE_ONLY: "仅日期精度",
  EXACT: "精确时间",
  UNKNOWN: "时间证据不足",
  UNKNOWN_HISTORICAL: "历史公开时间未核实",
  percent: "百分比（%）",
  index: "指数点",
  "USD/troy_oz": "美元/金衡盎司",
  CSV_UNVERIFIED: "CSV已保存 · 来源尚未核验",
  STATISTICAL_ASSOCIATION: "统计关联（不代表因果）",
  CAUSAL_HYPOTHESIS: "因果机制假设（待验证）",
  NO_QUALIFIED_GOLD_NEWS: "没有合格黄金证据",
  EXPLANATION_DISABLED: "解释功能未启用",
  ETF_PRIORITY_WINDOW: "等待ETF优先时段结束",
  ETF_PRIORITY_PENDING: "等待ETF日终任务完成",
  GOLD_WEEKLY_CALL_LIMIT: "本周黄金分析次数已用尽",
  SHARED_DAILY_BUDGET_EXCEEDED: "共享日预算不足",
  DEEPSEEK_DAILY_BUDGET_EXCEEDED: "DeepSeek当日调用预算不足",
  API_FAILED: "解释接口调用失败",
};
const seriesNames: Row = {
  SGE_AU9999_CLOSE: "上金所 Au99.99 日收盘",
  FRED_DEXCHUS: "USD/CNY（纽约午间汇率）",
  FRED_DFII10: "美国10年期实际国债收益率",
  FRED_DTWEXBGS: "美联储广义美元指数",
  WORLD_BANK_GOLD_MONTHLY: "国际黄金月均价（世界银行）",
  INTERNATIONAL_GOLD_USD: "国际美元黄金日价（待接入）",
};
const history = computed<Row[]>(() => data.value.history || []);
const predictions = computed<Row[]>(() => data.value.predictions || []);
const models = computed<Row[]>(() => data.value.models || []);
const sources = computed<Row[]>(() => data.value.sources || []);
const explanation = computed<Row | null>(() => {
  const news = data.value.news;
  const result = news?.result;
  const quote = news?.quote;
  // 失败或Mock响应即使带有模型文本也不当作真实有效解释；事实只能回查冻结目录。
  if (
    news?.status !== "SUCCEEDED" ||
    result?.status !== "SUCCEEDED" ||
    result?.nature !== "REAL" ||
    quote?.domain !== "GOLD_LONG_TERM" ||
    quote?.schema_version !== "gold-evidence-v1" ||
    !quote?.evidence_snapshot_id ||
    quote.evidence_snapshot_id !== result.evidence_snapshot_id
  )
    return null;
  return result.result || null;
});
const latestPrice = computed(() => history.value.at(-1)?.price);
// 相同期限的两个量化方案分别取最新记录，缺少 XGBoost 时不能借 LightGBM 填补。
const latest = computed(() =>
  [63, 126].flatMap((horizon) =>
    ["LightGBM", "XGBoost"].map((algorithm) => ({
      horizon,
      algorithm,
      prediction: [...predictions.value]
        .filter(
          (row) =>
            Number(row.horizon) === horizon &&
            quantAlgorithm(row) === algorithm,
        )
        .sort((a, b) =>
          String(b.generated_at).localeCompare(String(a.generated_at)),
        )[0],
    })),
  ),
);
const chart = computed(() => ({
  tooltip: {
    trigger: "axis",
    valueFormatter: (value: unknown) => `${money(value)} 元/克`,
  },
  grid: { left: 65, right: 20, top: 25, bottom: 65 },
  xAxis: {
    type: "category",
    data: history.value.map((row) => row.date),
    boundaryGap: false,
  },
  yAxis: { type: "value", scale: true, name: "CNY / 克" },
  dataZoom: [{ type: "inside" }, { type: "slider", bottom: 5, height: 20 }],
  series: [
    {
      name: "Au99.99 真实日收盘",
      type: "line",
      data: history.value.map((row) => Number(row.price)),
      showSymbol: false,
      lineStyle: { color: "#a8802f", width: 2 },
      itemStyle: { color: "#a8802f" },
    },
  ],
}));

/** 刷新只读取已保存结果；失败保留上次数据和原始日期，不生成新预测。 */
async function load() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    data.value = await api("/gold/overview");
    if (!form.benchmark_price && latestPrice.value != null) {
      form.benchmark_price = String(latestPrice.value);
      benchmarkSourceDate.value = data.value.as_of || "未记录日期";
      benchmarkManual.value = false;
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "黄金研究读取失败";
  } finally {
    busy.value = false;
  }
}

/** 未识别状态保持原值，避免把空数据、过期和失败混为正常。 */
function label(value: unknown) {
  return value == null || value === ""
    ? "—"
    : labels[String(value)] || String(value);
}

/** 期限固定为上金所交易日，不按自然月制造已确认到期日。 */
function horizonName(horizon: number) {
  return horizon === 63
    ? "约3个月（63交易日）"
    : horizon === 126
      ? "约6个月（126交易日）"
      : `${horizon}交易日`;
}

/** 独立估计冲突时先显示分歧，其余按已保存中位收益描述；缺失不补0或默认中性。 */
function direction(prediction: Row | undefined) {
  const value = prediction?.median_return;
  if (value == null || value === "" || !Number.isFinite(Number(value)))
    return "尚无有效判断";
  if (prediction && conflictingEstimates(prediction)) return "判断分歧";
  return Number(value) > 0
    ? "中位判断偏强"
    : Number(value) < 0
      ? "中位判断偏弱"
      : "中位判断持平";
}

/** 验证信息只关联同一个冻结模型ID，旧预测不能借用新模型的成绩。 */
function predictionModel(prediction: Row) {
  return models.value.find((model) => model.model_id === prediction.model_id);
}

/** 中位收益和方向概率来自独立估计，冲突时明确提示，不能冒充同一概率分布。 */
function conflictingEstimates(prediction: Row) {
  if (prediction.median_return == null || prediction.probability_up == null)
    return false;
  const median = Number(prediction.median_return);
  const probability = Number(prediction.probability_up);
  return (median < 0 && probability > 0.5) || (median > 0 && probability < 0.5);
}

/** 每个真实前瞻记录只展示追加的到期结果，历史回放另行标记。 */
function validation(row: Row) {
  if (row.nature === "HISTORICAL_REPLAY") return "历史回放 · 非真实前瞻";
  return row.actual
    ? `已到期 · 实际收益 ${percent(row.actual.actual_return)}`
    : "未到期 / 待完整行情 · 待验证";
}

/** 只允许用户打开公开HTTP(S)来源，拒绝其他可执行协议。 */
function sourceUrl(value: unknown) {
  try {
    const url = new URL(String(value));
    return ["https:", "http:"].includes(url.protocol) ? url.href : undefined;
  } catch {
    return undefined;
  }
}

/** 金额和克数始终以十进制字符串传给后端，不在浏览器执行资金结算。 */
async function calculate() {
  if (calculating.value) return;
  calculating.value = true;
  costError.value = "";
  cost.value = null;
  try {
    const { unit_purchase_price, total_purchase_cost, quote_at, ...common } =
      form;
    cost.value = await api("/gold/cost", {
      ...common,
      ...(purchaseMode.value === "UNIT_PRICE"
        ? { unit_purchase_price }
        : { total_purchase_cost }),
      quote_at: quote_at ? new Date(`${quote_at}+08:00`).toISOString() : null,
    });
  } catch (cause) {
    costError.value = cause instanceof Error ? cause.message : "成本测算失败";
  } finally {
    calculating.value = false;
  }
}

/** 精确ID读取完整冻结证据；迟到响应不能覆盖后来选中的记录。 */
async function inspect(row: Row) {
  selected.value = row;
  evidenceBusy.value = true;
  evidenceError.value = "";
  try {
    const detail = await api(`/gold/predictions/${encodeURIComponent(row.id)}`);
    if (selected.value?.id === row.id) selected.value = detail;
  } catch (cause) {
    if (selected.value?.id === row.id)
      evidenceError.value =
        cause instanceof Error ? cause.message : "完整冻结证据读取失败";
  } finally {
    if (selected.value?.id === row.id) evidenceBusy.value = false;
  }
}

/** 仅从同一快照的服务器目录按ID映射原文，未知ID或错配快照不回退模型自述。 */
function newsEvidence(ids: string[]) {
  const news = data.value.news;
  const snapshot = news?.quote?.evidence_snapshot_id;
  return (ids || []).map((id) => {
    const entry = (news?.quote?.evidence_catalog || []).find(
      (row: Row) => row.id === id && row.snapshot_id === snapshot,
    );
    return entry || { id, unavailable: true };
  });
}

/** 将统计指标显示为纯数值，保留缺失状态，避免显示NaN或虚构零分。 */
function metric(value: unknown, digits = 4) {
  return value == null || value === "" || !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toFixed(digits);
}

/** CSV只在本机选择文件后读取，模板不含示例行情，点击导入才发送业务写入。 */
async function selectCsv(event: Event) {
  csvText.value = "";
  importError.value = "";
  importResult.value = null;
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) {
    importError.value = "CSV文件不得超过5MB。";
    return;
  }
  csvText.value = await file.text();
}

/** 显式导入仍交由后端逐条校验，用户自报来源不自动获得严格点时资格。 */
async function importCsv() {
  if (!csvText.value || importBusy.value) return;
  importBusy.value = true;
  importError.value = "";
  importResult.value = null;
  try {
    importResult.value = await api("/gold/import", { csv: csvText.value });
    await load();
  } catch (cause) {
    importError.value = cause instanceof Error ? cause.message : "CSV导入失败";
  } finally {
    importBusy.value = false;
  }
}

// 任何输入修改后清除旧计算结果，防止误把上一组条件当成当前测算。
watch(
  [form, purchaseMode],
  () => {
    cost.value = null;
  },
  { deep: true },
);

onMounted(load);
</script>

<template>
  <div v-loading="busy" class="gold-page">
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      :title="error"
      description="读取失败时保留上次结果和原始日期；页面刷新不会采集、训练或调用付费模型。"
      data-testid="gold-error"
    />
    <section class="panel gold-summary">
      <div class="panel-head">
        <div>
          <h2>{{ data.benchmark?.name || "上海黄金交易所 Au99.99" }}</h2>
          <p>
            国内黄金基准 · {{ data.benchmark?.price_field || "日收盘价" }} ·
            CNY/克
          </p>
        </div>
        <el-button :loading="busy" @click="load">刷新已保存结果</el-button>
      </div>
      <div class="gold-overview">
        <div>
          <div class="stat-label">最新基准价格</div>
          <div class="stat-value gold-price">
            {{ money(latestPrice) }} <span class="small">元/克</span>
          </div>
          <p class="small muted" data-testid="gold-data-asof">
            数据截至 {{ data.as_of || "尚无有效数据" }}
          </p>
        </div>
        <div>
          <div class="stat-label">基准数据状态</div>
          <el-tag
            :type="
              ['OK', 'AVAILABLE'].includes(data.status) ? 'success' : 'warning'
            "
            >{{ label(data.status || "EMPTY") }}</el-tag
          >
          <p class="small muted">实验研究 · 数值结果不等于渠道可成交价</p>
        </div>
        <div>
          <div class="stat-label">上次有效更新（北京）</div>
          <strong data-testid="gold-updated-at">{{
            time(data.updated_at)
          }}</strong>
          <p class="small muted">交易数据、模型与 ETF 实验独立保存</p>
        </div>
      </div>
    </section>
    <div class="note warning-note" data-testid="gold-boundary">
      预测对象为国内黄金基准的未来收益，不包含零售溢价和回购扣费。参考收盘价不代表能以该价格买入实物；历史研究与上线后的真实前瞻分别验证。
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="走势与预测" name="outlook">
        <div class="gold-predictions">
          <section
            v-for="item in latest"
            :key="`${item.horizon}-${item.algorithm}`"
            class="panel"
            :data-testid="`gold-prediction-${item.algorithm === 'XGBoost' ? 'xgboost-' : ''}${item.horizon}`"
          >
            <div class="panel-head">
              <h2>{{ horizonName(item.horizon) }} · {{ item.algorithm }}</h2>
              <el-tag type="info">真实前瞻</el-tag>
            </div>
            <template v-if="item.prediction">
              <h3 class="gold-direction">{{ direction(item.prediction) }}</h3>
              <div class="info-row">
                <span>基准价上涨概率 P(收益 &gt; 0)</span
                ><strong>{{
                  item.prediction.probability_up == null
                    ? "未提供 · 尚无合格概率"
                    : percent(item.prediction.probability_up)
                }}</strong>
              </div>
              <div class="info-row">
                <span>预测中位收益</span
                ><strong>{{ percent(item.prediction.median_return) }}</strong>
              </div>
              <p
                v-if="conflictingEstimates(item.prediction)"
                class="note warning-note"
                data-testid="gold-estimate-conflict"
              >
                中位收益与上涨概率来自不同估计，当前方向判断不一致，证据有限；两者不能视为同一概率分布。
              </p>
              <div class="info-row">
                <span>到期价格区间（CNY/克）</span
                ><strong>{{
                  item.prediction.interval_lower == null ||
                  item.prediction.interval_upper == null
                    ? "未提供 · 样本或验证不足"
                    : `${money(item.prediction.interval_lower)} ～ ${money(item.prediction.interval_upper)}`
                }}</strong>
              </div>
              <p class="small muted">
                名义覆盖率
                {{
                  percent(item.prediction.nominal_coverage)
                }}；实际样本外覆盖率见“历史验证”。这是到期区间，不是每日价格路径。
              </p>
              <p
                v-if="predictionModel(item.prediction)?.metrics?.final_test"
                class="note warning-note gold-space"
                data-testid="gold-interval-validation"
              >
                最终测试实际覆盖率
                {{
                  percent(
                    predictionModel(item.prediction)?.metrics?.final_test
                      ?.candidate?.interval_coverage,
                  )
                }}。
                <span
                  v-if="
                    predictionModel(item.prediction)?.metrics?.final_test
                      ?.candidate?.interval_coverage != null &&
                    Number(
                      predictionModel(item.prediction)?.metrics?.final_test
                        ?.candidate?.interval_coverage,
                    ) < Number(item.prediction.nominal_coverage)
                  "
                  >低于名义覆盖，区间证据偏弱。</span
                >
                <span
                  v-if="
                    predictionModel(item.prediction)?.metrics?.final_test
                      ?.candidate?.brier == null
                  "
                  >最终测试未取得合格概率验证，当前概率仍属实验输出。</span
                >
              </p>
              <div class="info-row">
                <span>参考价格 / 价格日期</span
                ><strong
                  >{{ money(item.prediction.reference_price) }} /
                  {{ item.prediction.reference_date }}</strong
                >
              </div>
              <div class="info-row">
                <span>目标日期</span
                ><strong
                  >{{ item.prediction.target_date || "未来交易日历未确认"
                  }}<small>{{
                    label(item.prediction.target_date_status)
                  }}</small></strong
                >
              </div>
              <p class="small muted">
                生成于 {{ time(item.prediction.generated_at) }}；{{
                  validation(item.prediction)
                }}。
              </p>
              <p class="note warning-note gold-evidence">
                {{
                  item.prediction.evidence_status
                    ? label(item.prediction.evidence_status)
                    : "实验模型 · 尚无稳定优势"
                }}
              </p>
              <el-button text type="primary" @click="inspect(item.prediction)"
                >查看本条冻结证据</el-button
              >
              <el-collapse
                v-if="item.prediction.algorithm_sources"
                class="gold-space"
              >
                <el-collapse-item
                  title="收益、概率与区间的算法来源"
                  :name="`algorithm-${item.horizon}-${item.algorithm}`"
                >
                  <p class="small">
                    中位收益：{{
                      item.prediction.algorithm_sources.median_return ||
                      "未记录"
                    }}
                  </p>
                  <p class="small">
                    上涨概率：{{
                      item.prediction.algorithm_sources.probability_up ||
                      "未记录"
                    }}
                  </p>
                  <p class="small">
                    价格区间：{{
                      item.prediction.algorithm_sources.interval || "未记录"
                    }}
                  </p>
                </el-collapse-item>
              </el-collapse>
            </template>
            <div v-else class="empty" data-testid="gold-prediction-empty">
              <strong>尚无有效冻结预测</strong
              >等待真实数据和独立模型达到门槛。缺失概率、区间和目标日期保持空缺。
            </div>
          </section>
        </div>
        <section class="panel" data-testid="gold-factors">
          <div class="panel-head"><h2>当前判断依赖什么</h2></div>
          <p class="small">
            首版数值模型只使用国内黄金过去21、63、126个交易日的涨跌幅、过去21和63个交易日的价格波动，以及当前价格相对63日均线的位置。利率、汇率和新闻暂未进入数值模型。
          </p>
          <p class="small muted gold-space">
            这些是模型输入，不代表已经证明的因果关系或逐项贡献。趋势反转、波动明显变化、数据来源失败或过期时，需要重新审视判断；历史区间实际覆盖偏低，说明价格区间仍不稳健。
          </p>
        </section>
        <section class="panel">
          <div class="panel-head">
            <div>
              <h2>真实历史价格</h2>
              <p>Au99.99 日收盘；不混入均价、定盘价、黄金ETF或期货。</p>
            </div>
          </div>
          <Chart v-if="history.length" :option="chart" :height="340" />
          <div v-else class="empty" data-testid="gold-history-empty">
            暂无已核验历史价格；不使用演示行情补图。
          </div>
          <p class="small muted">
            图中只有已取得的历史记录。预测仅提供到期分布，条件情景不与历史价格拼接。
          </p>
        </section>
        <section class="panel">
          <div class="panel-head"><h2>冻结预测与到期对账</h2></div>
          <DataTable
            :rows="predictions"
            :columns="[
              {
                key: 'algorithm',
                label: '预测个体',
                format: (_, row) => quantAlgorithm(row),
              },
              {
                key: 'horizon',
                label: '预测期限',
                width: 190,
                format: horizonName,
              },
              { key: 'reference_date', label: '参考价格日' },
              {
                key: 'generated_at',
                label: '生成时间（北京）',
                width: 180,
                format: time,
              },
              {
                key: 'target_date',
                label: '目标日',
                format: (v, row) =>
                  `${v || '未确认'} · ${label(row.target_date_status)}`,
                width: 230,
              },
              { key: 'probability_up', label: '基准上涨概率', format: percent },
              {
                key: 'actual',
                label: '验证状态',
                width: 250,
                format: (_, row) => validation(row),
              },
            ]"
            empty="尚无真实前瞻冻结记录"
            @row-click="inspect"
          />
          <p class="small muted">
            点击记录回查当时的输入与版本；到期后追加评估，原预测保持不变。
          </p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="宏观与12个月情景" name="scenarios">
        <div class="note warning-note">
          以下为12个月条件情景，不是统计预测区间，也不附加未经验证的情景概率或目标价。利率、汇率及需求条件可以同时变化。
        </div>
        <section class="panel">
          <div class="panel-head"><h2>最新已保存宏观观测</h2></div>
          <DataTable
            :rows="data.macro || []"
            :columns="[
              {
                key: 'series_id',
                label: '独立序列',
                width: 210,
                format: (v) => seriesNames[v] || v,
              },
              { key: 'date', label: '观察日期', width: 140 },
              {
                key: 'value',
                label: '原单位数值',
                format: (v) => metric(v, 4),
                width: 150,
              },
              { key: 'unit', label: '单位', width: 120, format: label },
              {
                key: 'available_at',
                label: '公开可用时间（北京）',
                width: 200,
                format: (v) => (v ? time(v) : '无法证明'),
              },
              {
                key: 'availability_precision',
                label: '时间精度',
                width: 140,
                format: label,
              },
            ]"
            empty="尚无已接通的辅助观测；数值模型不会填造宏观数据。"
          />
          <p class="small muted">
            每个序列保留自身日期、单位及修订；世界银行国际金价为月均值，不能与当日汇率相乘冒充同一时点的报价。这里的最新观测不代表已满足当时可用条件或已用于模型。
          </p>
        </section>
        <section
          v-for="scenario in data.scenarios || []"
          :key="scenario.name"
          class="panel"
        >
          <div class="panel-head">
            <h2>{{ scenario.name }}</h2>
            <el-tag type="info">定性条件情景</el-tag>
          </div>
          <div class="info-row">
            <span>条件假设</span
            ><strong>{{
              Array.isArray(scenario.assumptions)
                ? scenario.assumptions.join("；")
                : scenario.assumptions
            }}</strong>
          </div>
          <div class="info-row">
            <span>可能影响路径</span><strong>{{ scenario.mechanism }}</strong>
          </div>
          <div class="info-row">
            <span>反向因素与失效条件</span
            ><strong>{{
              Array.isArray(scenario.invalidation)
                ? scenario.invalidation.join("；")
                : scenario.invalidation
            }}</strong>
          </div>
        </section>
        <div v-if="!data.scenarios?.length" class="panel empty">
          尚无已保存条件情景，不生成假设目标价或概率。
        </div>
        <section class="panel">
          <div class="panel-head">
            <h2>黄金事实与解释</h2>
            <el-tag type="warning">{{
              label(
                data.news?.display_status === "STALE"
                  ? "STALE"
                  : explanation?.status || data.news?.status || "UNAVAILABLE",
              )
            }}</el-tag>
          </div>
          <p class="small" data-testid="gold-news-state">
            {{
              data.news?.reason ||
              "解释暂不可用 / 信息不足。数值模型按自身数据状态独立运行。"
            }}
          </p>
          <p v-if="data.news?.error_code" class="small gold-space">
            当前状态原因：{{ label(data.news.error_code) }}
          </p>
          <template v-if="explanation">
            <p class="small gold-space" data-testid="gold-news-summary">
              {{ explanation.summary }}
            </p>
            <p class="small muted">
              生成于 {{ time(data.news.result.generated_at) }}；有效至
              {{
                time(data.news.result.valid_until)
              }}。解释反映当时条件，不保证消息尚未计价。
            </p>
            <article
              v-for="(mechanism, index) in explanation.mechanisms || []"
              :key="index"
              class="gold-news-mechanism"
              data-testid="gold-news-mechanism"
            >
              <el-tag type="info">{{ label(mechanism.kind) }}</el-tag>
              <div class="info-row">
                <span>可能影响机制</span
                ><strong>{{ mechanism.mechanism }}</strong>
              </div>
              <div class="info-row">
                <span>反向因素</span
                ><strong>{{ mechanism.counter_factor }}</strong>
              </div>
              <div class="info-row">
                <span>失效条件</span
                ><strong>{{ mechanism.invalidation }}</strong>
              </div>
              <div class="info-row">
                <span>不确定性</span
                ><strong>{{ mechanism.uncertainty }}</strong>
              </div>
              <div
                v-for="evidence in newsEvidence(mechanism.evidence_ids)"
                :key="evidence.id"
                class="gold-news-evidence"
              >
                <p v-if="evidence.unavailable" class="small muted">
                  证据无法在当前冻结目录回查，未展示模型自行编写的事实。
                </p>
                <template v-else>
                  <p class="small muted">
                    事实依据 ·
                    {{
                      evidence.content_type === "SUMMARY"
                        ? "来源摘要"
                        : "原文节选"
                    }}（最多240字）
                  </p>
                  <blockquote>
                    {{ String(evidence.text || "").slice(0, 240)
                    }}{{ String(evidence.text || "").length > 240 ? "…" : "" }}
                  </blockquote>
                  <p class="small muted">
                    公开时间 {{ time(evidence.published_at) }}；抓取时间
                    {{ time(evidence.fetched_at) }}
                  </p>
                  <a
                    v-if="sourceUrl(evidence.source_url)"
                    :href="sourceUrl(evidence.source_url)"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="gold-link"
                    >查看这条证据的来源</a
                  >
                </template>
              </div>
            </article>
          </template>
          <p class="small muted">
            新闻仅用于证据整理和解释，不改变数值模型概率，不使用ETF的70/30融合；今天重读的历史新闻不计入历史实时信号。
          </p>
        </section>
        <section class="panel">
          <h2>宏观变量的时间边界</h2>
          <p class="small gold-space">
            国际美元金价 × USD/CNY ÷ 31.1034768
            仅为“国际金价人民币折算参考”，不等于国内可成交价。国内基准已经包含汇率影响，不再重复叠加。
          </p>
          <p class="small muted">
            宏观数据按公开可用时间向后生效；无法证明历史可用时间和修订版本的资料不作为严格点时数据。各来源覆盖、更新时间和缺口见“数据来源与健康”。
          </p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="实物成本" name="cost">
        <section class="panel">
          <div class="panel-head">
            <div>
              <h2>实物黄金成本与盈亏测算</h2>
              <p>情景计算器，不创建持仓或交易。请填写拟买入或实际报价。</p>
            </div>
          </div>
          <el-form
            label-position="top"
            :disabled="calculating"
            @submit.prevent="calculate"
          >
            <div class="form-grid gold-form-grid">
              <el-form-item label="克数"
                ><el-input
                  v-model="form.quantity"
                  inputmode="decimal"
                  aria-label="克数"
                  placeholder="请输入，不预设持仓"
              /></el-form-item>
              <el-form-item label="输入克数的计重口径"
                ><el-select v-model="form.weight_basis" aria-label="计重口径"
                  ><el-option
                    label="金条毛重（含其他金属）"
                    value="GROSS" /><el-option
                    label="纯金重量"
                    value="FINE" /></el-select
              ></el-form-item>
              <el-form-item label="成色（0～1，如0.9999）"
                ><el-input
                  v-model="form.fineness"
                  :disabled="form.weight_basis === 'FINE'"
                  inputmode="decimal"
                  aria-label="成色"
              /></el-form-item>
              <el-form-item label="买入成本填写方式"
                ><el-select v-model="purchaseMode" aria-label="买入成本填写方式"
                  ><el-option
                    label="每输入克实际成交价"
                    value="UNIT_PRICE" /><el-option
                    label="全包买入总成本"
                    value="TOTAL_COST" /></el-select
              ></el-form-item>
              <el-form-item
                v-if="purchaseMode === 'UNIT_PRICE'"
                label="实际买入价（元/输入克）"
                ><el-input
                  v-model="form.unit_purchase_price"
                  inputmode="decimal"
                  aria-label="实际买入价"
                  placeholder="实际成交或假设价格"
              /></el-form-item>
              <el-form-item v-else label="全包买入总成本（元）"
                ><el-input
                  v-model="form.total_purchase_cost"
                  inputmode="decimal"
                  aria-label="全包买入总成本"
              /></el-form-item>
              <el-form-item label="测算基准价格（元/克）"
                ><el-input
                  v-model="form.benchmark_price"
                  @input="benchmarkManual = true"
                  inputmode="decimal"
                  aria-label="测算基准价格"
              /></el-form-item>
              <el-form-item label="买入溢价（元/输入克）"
                ><el-input
                  v-model="form.purchase_premium_per_gram"
                  inputmode="decimal"
                  aria-label="买入溢价"
                /><el-checkbox v-model="form.premium_included"
                  >已包含在上述买价/总成本内</el-checkbox
                ></el-form-item
              >
              <el-form-item label="买入费用（元）"
                ><el-input
                  v-model="form.purchase_fee"
                  inputmode="decimal"
                  aria-label="买入费用"
                /><el-checkbox v-model="form.purchase_fee_included"
                  >已包含在上述买价/总成本内</el-checkbox
                ></el-form-item
              >
              <el-form-item label="回购渠道"
                ><el-input
                  v-model="form.channel"
                  aria-label="回购渠道"
                  placeholder="银行、商家或假设渠道"
              /></el-form-item>
              <el-form-item label="回购比例扣减（0～小于1）"
                ><el-input
                  v-model="form.repurchase_deduction_rate"
                  inputmode="decimal"
                  aria-label="回购比例扣减"
                  placeholder="例如0.01表示1%"
              /></el-form-item>
              <el-form-item label="回购每克扣减（元/等价基准克）"
                ><el-input
                  v-model="form.repurchase_deduction_per_gram"
                  inputmode="decimal"
                  aria-label="回购每克扣减"
              /></el-form-item>
              <el-form-item label="卖出固定费用（元）"
                ><el-input
                  v-model="form.sale_fixed_fee"
                  inputmode="decimal"
                  aria-label="卖出固定费用"
              /></el-form-item>
              <el-form-item label="报价依据"
                ><el-select v-model="form.quote_kind" aria-label="报价依据"
                  ><el-option
                    label="用户手工假设"
                    value="USER_ASSUMPTION" /><el-option
                    label="用户已核实的真实渠道报价"
                    value="VERIFIED_QUOTE" /></el-select
              ></el-form-item>
              <el-form-item label="报价时间（北京时间）"
                ><el-date-picker
                  v-model="form.quote_at"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  aria-label="报价时间"
                  placeholder="真实报价必须填写"
              /></el-form-item>
            </div>
            <p class="note">
              费用顺序：先将重量按成色折算为99.99%基准等价克数，回购单价先扣比例、再扣每克金额，最后减固定费用。买价按输入克计价；已含费用不重复加收。全包总成本模式不允许追加未包含费用。
            </p>
            <p class="small muted">
              {{
                benchmarkManual
                  ? "当前测算基准为用户手工输入"
                  : `表单基准初始化自${benchmarkSourceDate || "尚未取得行情"}`
              }}，不是银行零售买入价或实际回购价；回购规则请与渠道核实。
            </p>
            <el-button
              native-type="submit"
              type="primary"
              :loading="calculating"
              class="gold-space"
              >计算成本与保本价</el-button
            >
          </el-form>
        </section>
        <el-alert
          v-if="costError"
          type="error"
          :closable="false"
          :title="costError"
          data-testid="gold-cost-error"
        />
        <section v-if="cost" class="panel" data-testid="gold-cost-result">
          <div class="panel-head">
            <h2>本次情景测算</h2>
            <el-tag :type="cost.quote_status === 'STALE' ? 'danger' : 'info'">{{
              label(cost.quote_status)
            }}</el-tag>
          </div>
          <div class="gold-result-grid">
            <div>
              <p class="stat-label">实际成本 / 输入克</p>
              <strong class="stat-value">{{
                money(cost.actual_cost_per_gram)
              }}</strong>
              <p class="small muted">元/输入克</p>
            </div>
            <div>
              <p class="stat-label">保本基准价</p>
              <strong class="stat-value gold-price">{{
                money(cost.break_even_benchmark_price)
              }}</strong>
              <p class="small muted">CNY/99.99%基准克</p>
            </div>
            <div>
              <p class="stat-label">估计回购净额</p>
              <strong class="stat-value">{{
                money(cost.repurchase_net)
              }}</strong>
              <p class="small muted">元</p>
            </div>
          </div>
          <div class="info-row">
            <span>总买入成本 / 等价基准重量</span
            ><strong
              >{{ money(cost.total_purchase_cost) }} 元 /
              {{ metric(cost.benchmark_equivalent_grams) }} 克</strong
            >
          </div>
          <div class="info-row">
            <span>净盈亏 / 净收益率</span
            ><strong
              >{{ money(cost.net_profit) }} 元 /
              {{ percent(cost.net_return) }}</strong
            >
          </div>
          <div v-if="cost.quote_age_hours != null" class="info-row">
            <span>报价年龄</span
            ><strong>{{ metric(cost.quote_age_hours, 1) }} 小时</strong>
          </div>
          <p v-if="cost.quote_status === 'STALE'" class="note warning-note">
            报价已过期，以上仅保留旧报价条件下的估计；需重新向渠道核实。
          </p>
          <div
            v-for="(assumption, index) in cost.assumptions || []"
            :key="index"
            class="small muted"
          >
            {{ assumption }}
          </div>
          <DataTable
            v-if="cost.forecast_ranges?.length"
            :rows="cost.forecast_ranges"
            :columns="[
              {
                key: 'horizon',
                label: '到期期限',
                width: 190,
                format: horizonName,
              },
              {
                key: 'lower_net_profit',
                label: '区间下端净盈亏（元）',
                width: 190,
                format: money,
              },
              {
                key: 'upper_net_profit',
                label: '区间上端净盈亏（元）',
                width: 190,
                format: money,
              },
              {
                key: 'lower_net_return',
                label: '区间下端净收益',
                width: 150,
                format: percent,
              },
              {
                key: 'upper_net_return',
                label: '区间上端净收益',
                width: 150,
                format: percent,
              },
            ]"
          />
          <p class="note gold-space">
            未来净收益范围沿用当前回购价差、比例扣减与费用，属于“成本不变”假设。基准上涨概率不等于实物扣费后盈利概率；没有对应分布时不显示盈利概率。
          </p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="历史验证" name="validation">
        <div class="note warning-note" data-testid="gold-validation-boundary">
          历史样本外回放与上线后真实前瞻分开。63/126日标签高度重叠，日线条数不是独立实验次数；未到期预测仍待验证。
        </div>
        <section
          v-for="model in models"
          :key="model.model_id"
          class="panel"
          data-testid="gold-model"
        >
          <div class="panel-head">
            <h2>
              {{ horizonName(model.horizon) }} · {{ quantAlgorithm(model) }} ·
              历史样本外验证
            </h2>
            <el-tag type="warning">{{
              label(model.metrics?.advantage_status || "EXPERIMENTAL")
            }}</el-tag>
          </div>
          <p class="small muted">
            {{
              label(model.metrics?.pit_status)
            }}。最终测试段未参与特征或参数选择；差结果也保留。
          </p>
          <div class="info-row">
            <span>真实覆盖 / 历史年数</span
            ><strong
              >{{ model.metrics?.coverage?.start || "—" }} ～
              {{ model.metrics?.coverage?.end || "—" }} /
              {{ metric(model.metrics?.coverage?.years, 1) }} 年</strong
            >
          </div>
          <div class="info-row">
            <span>观测数 / 成熟标签 / 近似独立窗口</span
            ><strong
              >{{ model.metrics?.coverage?.observations ?? "—" }} /
              {{ model.metrics?.coverage?.matured_samples ?? "—" }} /
              {{
                model.metrics?.coverage?.approx_independent_windows ?? "—"
              }}</strong
            >
          </div>
          <div class="info-row">
            <span>最终折训练 / 校准 / 测试样本数</span
            ><strong
              >{{ model.metrics?.final_test?.train_samples ?? "—" }} /
              {{ model.metrics?.final_test?.calibration_samples ?? "—" }} /
              {{ model.metrics?.final_test?.test_samples ?? "—" }}</strong
            >
          </div>
          <div class="info-row">
            <span>最终测试区间</span
            ><strong
              >{{ model.metrics?.final_test?.test_start || "未记录" }} ～
              {{ model.metrics?.final_test?.test_end || "未记录" }}</strong
            >
          </div>
          <div class="info-row">
            <span>最终测试 Brier / 常数概率基线（越低越好）</span
            ><strong
              >{{ metric(model.metrics?.final_test?.candidate?.brier) }} /
              {{ metric(model.metrics?.final_test?.baseline?.brier) }}</strong
            >
          </div>
          <div class="info-row">
            <span>最终测试方向准确率 / 多数类基线</span
            ><strong
              >{{ percent(model.metrics?.final_test?.candidate?.accuracy) }} /
              {{
                percent(model.metrics?.final_test?.baseline?.accuracy)
              }}</strong
            >
          </div>
          <div class="info-row">
            <span>区间名义覆盖 / 最终测试实际覆盖</span
            ><strong
              >{{ percent(model.metrics?.nominal_coverage) }} /
              {{
                percent(model.metrics?.final_test?.candidate?.interval_coverage)
              }}</strong
            >
          </div>
          <div class="info-row">
            <span>平均区间宽度（收益率）</span
            ><strong>{{
              percent(
                model.metrics?.final_test?.candidate?.interval_width_return,
              )
            }}</strong>
          </div>
          <div class="info-row">
            <span>非重叠测试窗口数 / Brier模型与基线</span
            ><strong
              >{{ model.metrics?.final_test?.nonoverlap?.samples ?? "—" }} /
              {{
                metric(model.metrics?.final_test?.nonoverlap?.candidate_brier)
              }}
              与
              {{
                metric(model.metrics?.final_test?.nonoverlap?.baseline_brier)
              }}</strong
            >
          </div>
          <p
            v-for="(warning, index) in model.metrics?.warnings || []"
            :key="index"
            class="small muted gold-space"
          >
            {{ warning }}
          </p>
          <DataTable
            v-if="model.metrics?.rolling?.length"
            :rows="model.metrics.rolling"
            :columns="[
              { key: 'fold', label: '滚动折' },
              { key: 'test_start', label: '测试开始', width: 140 },
              { key: 'test_end', label: '测试结束', width: 140 },
              { key: 'test_samples', label: '测试样本' },
              {
                key: 'candidate',
                label: '模型Brier',
                format: (v) => metric(v?.brier),
              },
              {
                key: 'baseline',
                label: '基线Brier',
                format: (v) => metric(v?.brier),
              },
              {
                key: 'nonoverlap',
                label: '非重叠窗口',
                format: (v) => String(v?.samples ?? '—'),
              },
            ]"
          />
          <el-collapse class="gold-space"
            ><el-collapse-item
              title="滚动分段、模型与数据边界"
              :name="model.model_id"
            >
              <pre class="gold-evidence-json">{{
                JSON.stringify(
                  {
                    model_id: model.model_id,
                    metadata: model.metadata,
                    metrics: model.metrics,
                  },
                  null,
                  2,
                )
              }}</pre>
            </el-collapse-item></el-collapse
          >
        </section>
        <div
          v-if="!models.length"
          class="panel empty"
          data-testid="gold-model-empty"
        >
          <strong>尚无可展示的样本外报告</strong
          >等待真实历史覆盖和成熟标签达到训练门槛，不用模拟成绩替代。
        </div>
        <section class="panel">
          <h2>如何阅读验证结果</h2>
          <p class="small gold-space">
            上涨概率表示 P(基准价未来收益 &gt;
            0)。Brier和准确率须与只用训练期估计的基线比较，区间须同时看真实覆盖率与宽度。样本不足、没有稳定优势和差结果保留展示。
          </p>
          <p class="small muted">
            历史收益标签为 P目标 / P参考 −
            1。模型验证不等于交易收益验证；没有真实渠道历史溢价时，实物成本只作假设敏感性分析。
          </p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="数据来源与健康" name="sources">
        <section class="panel">
          <div class="panel-head"><h2>真实覆盖与来源状态</h2></div>
          <DataTable
            :rows="data.coverage || []"
            :columns="[
              { key: 'series_id', label: '独立序列', width: 220 },
              { key: 'count', label: '已保存观测数' },
              { key: 'first_date', label: '最早观察日期', width: 150 },
              { key: 'last_date', label: '最新观察日期', width: 150 },
            ]"
            empty="尚无真实数据覆盖"
          />
        </section>
        <section
          v-for="source in sources"
          :key="source.series_id"
          class="panel"
          data-testid="gold-source"
        >
          <div class="panel-head">
            <h2>{{ source.name || source.series_id }}</h2>
            <el-tag
              :type="
                ['OK', 'HEALTHY'].includes(source.status)
                  ? 'success'
                  : 'warning'
              "
              >{{ label(source.status) }}</el-tag
            >
          </div>
          <p class="small">
            {{
              source.reason ||
              source.message ||
              source.error ||
              "无额外状态说明"
            }}
          </p>
          <div class="info-row">
            <span>序列 / 单位</span
            ><strong
              >{{ source.series_id }} / {{ source.unit || "未记录" }}</strong
            >
          </div>
          <div class="info-row">
            <span>最近有效数据日期</span
            ><strong>{{
              source.last_date ||
              source.last_observation_date ||
              source.latest_observation_date ||
              "尚无有效数据"
            }}</strong>
          </div>
          <div class="info-row">
            <span>最近尝试 / 成功抓取（北京）</span
            ><strong
              >{{ time(source.last_attempt_at) }} /
              {{ time(source.last_success_at) }}</strong
            >
          </div>
          <a
            v-if="sourceUrl(source.source_url)"
            :href="sourceUrl(source.source_url)"
            target="_blank"
            rel="noopener noreferrer"
            class="gold-link"
            >查看原始来源</a
          >
          <el-collapse class="gold-space"
            ><el-collapse-item
              title="来源、修订与可用时点说明"
              :name="source.series_id"
            >
              <pre class="gold-evidence-json">{{
                JSON.stringify(source, null, 2)
              }}</pre>
            </el-collapse-item></el-collapse
          >
        </section>
        <div v-if="!sources.length" class="panel empty">
          来源尚未完成首次探查；未连接不等于空行情。
        </div>
        <section class="panel">
          <div class="panel-head">
            <h2>可追溯CSV导入</h2>
            <a
              href="/api/gold/import/template"
              download="gold-observations-template.csv"
              class="gold-link"
              >下载空白导入模板</a
            >
          </div>
          <p class="small">
            用于自动采集条件不足时保存有来源的历史资料。最多10000条、5MB；填写来源、单位、观察日期、抓取时间及修订。无法证明公开可用时间时留空并标记UNKNOWN。
          </p>
          <p class="small muted gold-space">
            手工CSV标记为未核验，不自动进入真实模型训练。模板不含示例数据；导入不触发训练、预测或付费分析。
          </p>
          <div class="toolbar gold-space">
            <input
              type="file"
              accept=".csv,text/csv"
              aria-label="选择黄金CSV"
              @change="selectCsv"
            /><el-button
              :disabled="!csvText"
              :loading="importBusy"
              @click="importCsv"
              >校验并导入所选CSV</el-button
            >
          </div>
          <el-alert
            v-if="importError"
            type="error"
            :title="importError"
            :closable="false"
          />
          <p v-if="importResult" class="note">
            导入 {{ importResult.imported }} 条，已有记录
            {{ importResult.duplicates }} 条；状态
            {{ label(importResult.status) }}。
          </p>
        </section>
        <section class="panel">
          <div class="panel-head">
            <h2>运行状态</h2>
            <el-tag :type="data.health?.enabled ? 'success' : 'info'">{{
              data.health?.enabled ? "模块已启用" : "模块未启用"
            }}</el-tag>
          </div>
          <p class="small">
            {{
              data.health?.scheduling_enabled
                ? "自动调度已启用"
                : "自动调度未启用"
            }}。黄金任务沿用持久队列，ETF日终优先；网页浏览和刷新不触发采集、训练或付费调用。
          </p>
          <div class="info-row">
            <span>调度规则</span
            ><strong>{{
              typeof data.health?.schedule === "string"
                ? data.health.schedule
                : "每日增量采集；每周正式预测；每月按固定规则重训（实际配置见详情）"
            }}</strong>
          </div>
          <DataTable
            :rows="data.health?.tasks || []"
            :columns="[
              { key: 'task_type', label: '黄金任务', width: 170 },
              { key: 'status', label: '状态', format: label },
              {
                key: 'updated_at',
                label: '最近状态时间（北京）',
                width: 190,
                format: time,
              },
              { key: 'attempts', label: '尝试次数' },
            ]"
            empty="尚无黄金持久任务"
          />
          <el-collapse class="gold-space"
            ><el-collapse-item title="调度与资源配置" name="schedule">
              <pre class="gold-evidence-json">{{
                JSON.stringify(data.health || {}, null, 2)
              }}</pre>
            </el-collapse-item></el-collapse
          >
        </section>
        <p class="note warning-note">
          今天首次导入历史行情，不代表今天就是历史首次公开时间。无法证明当时版本和发布时间的字段会保留限制，不默认满足严格点时回放。
        </p>
      </el-tab-pane>
    </el-tabs>
    <el-drawer
      :model-value="!!selected"
      title="黄金冻结预测证据"
      size="min(680px, 100vw)"
      @close="selected = null"
    >
      <template v-if="selected">
        <el-alert
          v-if="evidenceError"
          type="error"
          :closable="false"
          :title="evidenceError"
          description="当前仅保留总览摘要，完整输入证据读取尚未成功。"
        />
        <p v-if="evidenceBusy" class="small muted">
          正在读取这条预测的完整冻结快照…
        </p>
        <h3>
          {{ horizonName(selected.horizon) }} · {{ quantAlgorithm(selected) }}
        </h3>
        <div class="info-row">
          <span>记录ID</span><strong>{{ selected.id }}</strong>
        </div>
        <div class="info-row">
          <span>参考价格 / 日期</span
          ><strong
            >{{ money(selected.reference_price) }} CNY/克 /
            {{ selected.reference_date }}</strong
          >
        </div>
        <div class="info-row">
          <span>数据截止</span><strong>{{ time(selected.data_cutoff) }}</strong>
        </div>
        <div class="info-row">
          <span>生成时间（北京）</span
          ><strong>{{ time(selected.generated_at) }}</strong>
        </div>
        <div class="info-row">
          <span>目标日期 / 日历</span
          ><strong
            >{{ selected.target_date || "未确认" }} /
            {{ label(selected.target_date_status) }}</strong
          >
        </div>
        <div class="info-row">
          <span>模型工件</span><strong>{{ selected.model_id }}</strong>
        </div>
        <div class="info-row">
          <span>标签版本</span><strong>{{ selected.label_version }}</strong>
        </div>
        <p class="note gold-space">
          {{
            validation(selected)
          }}。预测依据当时数据与模型冻结，不能在事后已知的参考收盘价真实买入。
        </p>
        <el-collapse
          ><el-collapse-item title="完整版本、输入快照与追加评估" name="frozen">
            <pre class="gold-evidence-json">{{
              JSON.stringify(selected, null, 2)
            }}</pre>
          </el-collapse-item></el-collapse
        >
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.gold-page {
  min-width: 0;
}
.gold-summary .panel-head {
  align-items: flex-start;
}
.gold-overview,
.gold-result-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
}
.gold-overview .small,
.gold-result-grid .small {
  margin-top: 10px;
}
.gold-price {
  color: #997526;
}
.gold-predictions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.gold-direction {
  color: #8b6a25;
  font-size: 19px;
  margin-bottom: 10px;
}
.gold-evidence {
  margin-top: 14px;
  margin-bottom: 8px;
}
.gold-space {
  margin-top: 18px;
}
.gold-link {
  color: #087f8c;
  font-size: 12px;
  text-decoration: underline;
}
.gold-evidence-json {
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.gold-news-mechanism {
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid #e5ecf1;
}
.gold-news-evidence {
  margin-top: 14px;
  padding: 12px;
  background: #f7f9fb;
  border-radius: 7px;
}
.gold-news-evidence blockquote {
  margin: 10px 0;
  padding-left: 12px;
  border-left: 2px solid #b0c8ce;
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.info-row strong {
  text-align: right;
  overflow-wrap: anywhere;
  max-width: 72%;
}
.info-row small {
  display: block;
  font-size: 11px;
  color: #8996a1;
  font-weight: 400;
}
.gold-form-grid .el-checkbox {
  white-space: normal;
  height: auto;
  line-height: 1.6;
  margin-top: 8px;
}
.gold-form-grid :deep(.el-checkbox__label) {
  white-space: normal;
  font-size: 11px;
}
.gold-form-grid .el-date-editor {
  width: 100%;
}
.gold-form-grid .el-form-item {
  min-width: 0;
}
@media (max-width: 1100px) {
  .gold-overview {
    grid-template-columns: 1fr 1fr;
  }
  .gold-predictions {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
@media (max-width: 700px) {
  .gold-overview,
  .gold-result-grid {
    grid-template-columns: 1fr;
    gap: 20px;
  }
  .gold-summary .panel-head {
    flex-direction: column;
  }
  .gold-form-grid {
    grid-template-columns: 1fr;
  }
  .gold-page :deep(.el-tabs__nav-wrap) {
    max-width: 100%;
  }
  .info-row {
    gap: 12px;
  }
  .info-row strong {
    max-width: 62%;
  }
}
</style>
