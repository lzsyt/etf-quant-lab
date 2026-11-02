<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  api,
  guarded,
  money,
  names,
  number,
  percent,
  statuses,
  time,
  type Row,
} from "../api";
import DataTable from "../components/DataTable.vue";

const reports = ref<Row[]>([]),
  selected = ref(""),
  detail = ref<Row>({}),
  busy = ref(false);
const report = computed<Row>(() => detail.value.report || {});
const predictions = computed<Row[]>(() => report.value.predictions || []);
const accounts = computed<Row[]>(() => report.value.accounts || []);
const trades = computed<Row[]>(() => report.value.trades || []);
const news = computed<Row[]>(() =>
  Array.isArray(report.value.news) ? report.value.news : [],
);
const anomalies = computed<Row[]>(() =>
  (report.value.anomalies || []).map((item: Row | string) =>
    typeof item === "string" ? { message: item } : item,
  ),
);
const previousAccuracy = computed(() => {
  const value = report.value.previous_matured_accuracy;
  return value && typeof value === "object" ? value.accuracy : value;
});

/** 根据归档ID读取历史版本，切换时不继续显示上一个版本的内容。 */
async function inspect() {
  detail.value = {};
  if (!selected.value) return;
  const reportId = selected.value;
  busy.value = true;
  await guarded(async () => {
    const response = await api("/reports/" + encodeURIComponent(reportId));
    if (selected.value === reportId) detail.value = response;
  });
  if (selected.value === reportId) busy.value = false;
}
/** 报告只读取服务端冻结版本，页面不运行分析或触发额外模型费用。 */
async function load() {
  busy.value = true;
  await guarded(async () => {
    reports.value = await api("/reports");
    if (!reports.value.some((item) => item.id === selected.value))
      selected.value = reports.value[0]?.id || "";
    await inspect();
  });
  busy.value = false;
}
/** 只展示已保存的新闻分析摘要；没有证据时明确显示信息不足。 */
function newsSummary(item: Row) {
  return (
    item.analysis_summary ||
    item.summary ||
    item.result?.result?.analysis_summary ||
    item.result?.analysis_summary ||
    "信息不足，未形成有效财经分析。"
  );
}
/** API费用保留微小费用精度，未知值不能显示为0美元。 */
function cost(value: unknown) {
  return value == null || !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toFixed(6);
}
onMounted(load);
</script>
<template>
  <div class="note">
    每个交易日的研究报告由已保存行情、冻结预测、模拟账本和任务记录生成。历史版本保留；不使用随机数据，也不补造未来样本。
  </div>
  <section class="panel">
    <div class="panel-head">
      <h2>每日研究报告与历史版本</h2>
      <el-button @click="load">刷新报告</el-button>
    </div>
    <div class="toolbar">
      <el-select
        v-model="selected"
        @change="inspect"
        placeholder="选择交易日和报告版本"
        aria-label="研究日报历史版本"
        class="result-select"
        ><el-option
          v-for="item in reports"
          :key="item.id"
          :value="item.id"
          :label="
            item.report_date +
            ' · 版本 ' +
            item.version +
            ' · ' +
            time(item.created_at)
          "
      /></el-select>
    </div>
    <div
      v-if="!reports.length && !busy"
      class="empty"
      data-testid="daily-report-empty"
    >
      <strong>尚无已保存的交易日报告</strong
      >等待有效交易日日终流程生成。样本不足，无法得出可靠结论。
    </div>
    <div v-else-if="detail.id" class="tag-row">
      <el-tag>{{ detail.report_date }}</el-tag
      ><el-tag type="info">历史版本 {{ detail.version }}</el-tag
      ><el-tag type="warning">仅虚拟资金研究</el-tag
      ><span class="small muted"
        >真实生成时间 {{ time(report.generated_at || detail.created_at) }}</span
      >
    </div>
  </section>
  <div v-if="detail.id" v-loading="busy">
    <div class="stats">
      <div class="stat">
        <div class="stat-label">当日分析ETF数量</div>
        <div class="stat-value">{{ report.etf_count ?? "—" }}</div>
        <div class="stat-note">按报告保存的实际分析范围</div>
      </div>
      <div class="stat">
        <div class="stat-label">最新行情截止日期</div>
        <div class="stat-value report-date">
          {{ report.market_cutoff || "—" }}
        </div>
        <div class="stat-note">行情日期与报告生成日期独立</div>
      </div>
      <div class="stat">
        <div class="stat-label">累计有效到期预测样本（1日）</div>
        <div class="stat-value">
          {{ report.valid_prediction_samples ?? "—" }}
        </div>
        <div class="stat-note">仅符合验证条件的1日冻结预测</div>
      </div>
      <div class="stat">
        <div class="stat-label">当日API费用占用 / USD</div>
        <div class="stat-value report-date">{{ cost(report.cost_usd) }}</div>
        <div class="stat-note">以保存的费用口径为准</div>
      </div>
    </div>
    <section class="panel">
      <div class="panel-head"><h2>研究结论与样本边界</h2></div>
      <p class="text-output">
        {{ report.conclusion || "样本不足，无法得出可靠结论。" }}
      </p>
      <div class="info-row">
        <span>昨日到期预测方向准确率（1日）</span
        ><strong>{{ percent(previousAccuracy) }}</strong>
      </div>
      <p class="small muted">
        无到期样本时显示“—”；准确率不等于可盈利，历史回测成绩不加入本报告的前瞻样本。
      </p>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>各ETF冻结预测</h2></div>
      <DataTable
        :rows="predictions"
        :columns="[
          { key: 'symbol', label: 'ETF代码' },
          {
            key: 'strategy_id',
            label: '模型 / 策略',
            format: (v) => names[v] || v,
            width: 170,
          },
          { key: 'signal_date', label: '行情日' },
          {
            key: 'horizon',
            label: '预测跨度',
            format: (v) => (v == null ? '历史未记录' : v + ' 个交易日'),
            width: 120,
          },
          {
            key: 'target_date',
            label: '目标交易日',
            format: (v) => v || '未记录 / 日历未确认',
            width: 180,
          },
          { key: 'direction', label: '方向', format: (v) => statuses[v] || v },
          { key: 'probability', label: '上涨概率', format: percent },
          {
            key: 'raw_score',
            label: '无量纲分数',
            format: (v, row) => number(v ?? row.details?.score),
          },
          {
            key: 'created_at',
            label: '实际生成时间',
            format: time,
            width: 185,
          },
        ]"
        empty="该报告没有可用预测，不生成替代结果。"
      />
    </section>
    <section class="panel">
      <div class="panel-head">
        <h2>DeepSeek财经分析摘要</h2>
        <el-tag type="info">新闻是研究特征，不是自动买入指令</el-tag>
      </div>
      <article
        v-for="(item, index) in news"
        :key="item.id || index"
        class="news-card"
      >
        <div class="tag-row">
          <el-tag :type="item.status === 'SUCCEEDED' ? 'success' : 'warning'">{{
            statuses[item.status] || item.status || "已保存资料"
          }}</el-tag
          ><span class="small muted">{{
            item.model_version || item.source_name || ""
          }}</span>
        </div>
        <p class="text-output">{{ newsSummary(item) }}</p>
        <div class="news-meta">
          <span v-if="item.input_cutoff"
            >输入截止 {{ time(item.input_cutoff) }}</span
          ><span v-if="item.prompt_version"
            >提示词 {{ item.prompt_version }}</span
          ><span v-if="item.error_code">{{ item.error_code }}</span>
        </div>
      </article>
      <div v-if="!news.length" class="empty">
        信息不足，没有本报告可用的已保存财经分析。
      </div>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>当日模拟成交</h2></div>
      <DataTable
        :rows="trades"
        :columns="[
          {
            key: 'account_id',
            label: '虚拟账户',
            format: (v) => names[v] || v,
            width: 170,
          },
          { key: 'trade_date', label: '成交日期' },
          { key: 'symbol', label: 'ETF' },
          {
            key: 'side',
            label: '方向',
            format: (v) => (v === 'BUY' ? '买入' : v === 'SELL' ? '卖出' : v),
          },
          { key: 'quantity', label: '份额' },
          { key: 'price', label: '估计成交价' },
          { key: 'fee', label: '费用（元）', format: money },
          {
            key: 'realized_pnl',
            label: '已实现盈亏',
            format: (v, row) => money(v ?? row.details?.realized_pnl),
          },
        ]"
        empty="本报告未记录当日模拟成交，具体原因见模拟交易运行诊断。"
      />
    </section>
    <section class="panel">
      <div class="panel-head"><h2>模拟账户与基准</h2></div>
      <DataTable
        :rows="accounts"
        :columns="[
          {
            key: 'strategy_id',
            label: '策略',
            format: (v, row) =>
              names[v || row.account_id || row.id] ||
              v ||
              row.account_id ||
              row.id,
            width: 175,
          },
          { key: 'equity', label: '虚拟总资产', format: money },
          {
            key: 'last_settlement_date',
            label: '最近结算日',
            format: (v) => v || '尚未结算 / 历史未记录',
            width: 190,
          },
          { key: 'total_return', label: '累计收益', format: percent },
          { key: 'max_drawdown', label: '最大回撤', format: percent },
          {
            key: 'benchmark_return',
            label: '约束买入持有收益',
            format: percent,
            width: 175,
          },
          {
            key: 'excess_return',
            label: '相对基准超额',
            format: percent,
            width: 150,
          },
        ]"
        empty="尚无该交易日可核对的账户净值。"
      />
      <p class="small muted" style="margin-top: 12px">
        风险约束买入持有按同一仓位限制运行，不是全仓买入持有。区间不一致或记录不足时不输出超额结论。
      </p>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>异常任务与潜在风险</h2></div>
      <DataTable
        :rows="anomalies"
        :columns="[
          { key: 'task_type', label: '任务 / 类型', width: 140 },
          { key: 'status', label: '状态', format: (v) => statuses[v] || v },
          {
            key: 'message',
            label: '异常或风险',
            format: (v, row) =>
              v || row.error_code || row.reason || '查看记录详情',
            width: 300,
          },
          { key: 'business_key', label: '唯一业务标识', width: 260 },
        ]"
        empty="此报告未记录异常；这不等于所有未来运行风险已被排除。"
      />
    </section>
  </div>
</template>
