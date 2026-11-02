<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  api,
  guarded,
  names,
  statuses,
  percent,
  time,
  number,
  type Row,
} from "../api";
import DataTable from "../components/DataTable.vue";
const predictions = ref<Row[]>([]),
  analyses = ref<Row[]>([]),
  news = ref<Row[]>([]),
  models = ref<Row[]>([]),
  busy = ref(false),
  selected = ref<Row | null>(null),
  drawer = ref(false),
  strategy = ref("all"),
  horizon = ref(1);
const table = computed(() =>
  predictions.value
    .filter(
      (v) =>
        (strategy.value === "all" ||
          v.prediction.strategy_id === strategy.value) &&
        v.prediction.horizon === horizon.value,
    )
    .map((v) => ({
      ...v.prediction,
      actual: v.actual,
      execution: v.execution,
    })),
);
const latest = computed(() => analyses.value[0]);
const event = computed(() => latest.value?.result?.result);
/** 只读取已冻结字段；旧记录缺少的证据不由浏览器推算或补写。 */
function evidence(row: Row, key: string) {
  return row[key] ?? row.details?.[key];
}
/** 分数不是收益率，按照实际策略公式解释单位和取值。 */
function scoreMeaning(row: Row) {
  const semantics =
    evidence(row, "score_semantics") || row.details?.signal_semantics;
  if (row.strategy_id === "fusion")
    return "无量纲实验分数，范围[-1,1]；0.7 × (2 × ML上涨概率 − 1) + 0.3 × 事件情绪；不是预期收益率或校准概率";
  if (row.strategy_id === "llm")
    return "事件情绪分数，范围[-1,1]；不是上涨概率或预期收益率";
  return semantics || "历史记录未提供分数定义，请核对模型证据";
}
/** 原始分数保持原值，不乘100或添加百分号。 */
function rawScore(row: Row) {
  return number(evidence(row, "raw_score") ?? row.details?.score);
}
/** 对账未发生或被及时性规则排除时不显示错误的0%正确率。 */
function verdict(actual: Row | null) {
  if (!actual) return "未到期 / 待完整行情";
  if (actual.correct === true) return "方向正确";
  if (actual.correct === false) return "方向错误";
  return (
    actual.details?.reason ||
    actual.details?.evaluation_reason ||
    "不纳入方向统计"
  );
}
/** 打开当前冻结记录，使用唯一ID定位，不修改任何预测。 */
function inspect(row: Row) {
  selected.value = row;
  drawer.value = true;
}
async function load() {
  busy.value = true;
  await guarded(async () => {
    [predictions.value, analyses.value, news.value, models.value] =
      await Promise.all([
        api("/predictions?limit=1000"),
        api("/analyses"),
        api("/news"),
        api("/models"),
      ]);
  });
  busy.value = false;
}
onMounted(load);
</script>
<template>
  <div v-loading="busy">
    <div class="note">
      每条预测在生成时冻结，保留数据截止点、模型版本及来源。上涨概率仅适用于经过校准的机器学习模型；LLM情绪和固定权重融合均为策略分数。
    </div>
    <div class="note warning-note">
      融合分数 −0.012 表示轻微负向的无量纲信号，不代表预期收益
      −1.2%。行情日期、实际生成时间和未来目标交易日分别保存；周末生成仍记录真实周末时间。方向与买入阈值是不同判断，预测上涨不一定产生买单。
    </div>
    <section class="panel">
      <div class="panel-head">
        <h2>冻结预测与实际对账</h2>
        <el-button @click="load">刷新记录</el-button>
      </div>
      <div class="toolbar">
        <el-select v-model="strategy" aria-label="预测策略"
          ><el-option label="全部策略" value="all" /><el-option
            v-for="(name, id) in names"
            :key="id"
            :label="name"
            :value="id" /></el-select
        ><el-radio-group v-model="horizon"
          ><el-radio-button :value="1">未来1个交易日</el-radio-button
          ><el-radio-button :value="5"
            >未来5个交易日</el-radio-button
          ></el-radio-group
        >
      </div>
      <DataTable
        :rows="table"
        @row-click="inspect"
        :columns="[
          { key: 'symbol', label: 'ETF', width: 90 },
          {
            key: 'strategy_id',
            label: '模型/策略',
            format: (v) => names[v] || v,
            width: 150,
          },
          { key: 'signal_date', label: '行情截止日' },
          {
            key: 'target_date',
            label: '目标交易日',
            format: (v) => v || '历史未记录 / 日历未确认',
            width: 190,
          },
          { key: 'probability', label: '上涨概率', format: percent },
          { key: 'direction', label: '方向', format: (v) => statuses[v] || v },
          {
            key: 'raw_score',
            label: '原始分数（非收益率）',
            format: (_, row) => rawScore(row),
            width: 170,
          },
          { key: 'actual', label: '验证状态', format: verdict, width: 180 },
          {
            key: 'actual',
            label: '实际收益',
            format: (v) => (v ? percent(v.actual_return) : '未到期/待行情'),
          },
          {
            key: 'created_at',
            label: '实际生成时间（北京）',
            format: time,
            width: 190,
          },
          { key: 'model_version', label: '模型版本', width: 230 },
        ]"
        empty="尚未生成前瞻预测。可在系统管理启动日终流程。"
      />
      <p class="small muted" style="margin-top: 14px">
        点击任一预测查看唯一ID、输入截止、参数版本、分数解释及到期验证证据。旧记录缺失字段保持“历史未记录”。
      </p>
    </section>
    <div class="grid-equal">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>LLM财经事件分析</h2>
            <p>仅分析已采集资料，不假定API自带搜索</p>
          </div>
          <el-tag
            :type="latest?.status === 'SUCCEEDED' ? 'success' : 'warning'"
            >{{ statuses[latest?.status] || "尚未执行" }}</el-tag
          >
        </div>
        <template v-if="event"
          ><div class="tag-row">
            <el-tag>{{ event.market_sentiment }}</el-tag
            ><el-tag type="info"
              >情绪 {{ number(event.sentiment_score) }}</el-tag
            ><el-tag type="warning">风险 {{ event.risk_level }}</el-tag>
          </div>
          <p class="text-output">{{ event.analysis_summary }}</p>
          <h3 style="margin: 20px 0 8px">关键因素</h3>
          <ul class="text-output">
            <li v-for="factor in event.key_factors" :key="factor">
              {{ factor }}
            </li>
          </ul>
          <h3>风险因素</h3>
          <ul class="text-output">
            <li v-for="factor in event.risk_factors" :key="factor">
              {{ factor }}
            </li>
          </ul></template
        >
        <div v-else class="empty">
          <strong>当前分析缺失</strong
          >{{ latest?.error_code || "尚未运行新闻分析任务"
          }}<br />不会以虚构的财经分析替代。
        </div>
        <div class="info-row">
          <span>输入截止</span><strong>{{ time(latest?.input_cutoff) }}</strong>
        </div>
        <div class="info-row">
          <span>实际调用及资料证据</span
          ><strong>{{ latest?.id || "—" }}</strong>
        </div>
        <p class="small muted">
          权威来源不等于与ETF短期走势有关。新闻相关性、发布时间及首次获取时间必须满足研究口径，信息不足时不输出结论。
        </p>
        <div class="info-row">
          <span>提示词 / 模型</span
          ><strong
            >{{ latest?.prompt_version || "—" }} /
            {{ latest?.model_version || "—" }}</strong
          >
        </div>
      </section>
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>来源与新闻资料</h2>
            <p>仅有限正文摘要；点击标题核对原文</p>
          </div>
          <el-tag effect="plain">{{ news.length }}条</el-tag>
        </div>
        <div v-if="!news.length" class="empty">暂无合格新闻来源</div>
        <article
          v-for="item in news.slice(0, 6)"
          :key="item.id"
          class="news-card"
        >
          <a :href="item.source_url" target="_blank" rel="noopener noreferrer"
            >{{ item.title }} ↗</a
          >
          <p>{{ item.summary }}</p>
          <div class="news-meta">
            <span>{{ item.source_name }}</span
            ><span>发布 {{ time(item.published_at) }}</span
            ><span>获取 {{ time(item.fetched_at) }}</span>
          </div>
        </article>
      </section>
    </div>
    <section class="panel">
      <div class="panel-head">
        <h2>模型样本外概率表现</h2>
        <el-tag type="info" effect="plain">历史留出测试集 · 非实时收益</el-tag>
      </div>
      <DataTable
        :rows="models"
        :columns="[
          { key: 'symbol', label: 'ETF' },
          { key: 'algorithm', label: '算法' },
          { key: 'horizon', label: '交易日窗口' },
          {
            key: 'metrics',
            label: '准确率',
            format: (v) => percent(v?.test?.accuracy),
          },
          {
            key: 'metrics',
            label: 'Brier',
            format: (v) => number(v?.test?.brier_score),
          },
          { key: 'data_cutoff', label: '输入资料截止' },
          {
            key: 'fit_data_cutoff',
            label: '参数拟合截止',
            format: (v, row) =>
              v || row.training_range?.train?.last_label_date || '历史未记录',
            width: 150,
          },
          { key: 'feature_version', label: '特征版本' },
        ]"
      />
    </section>
    <el-drawer v-model="drawer" title="冻结预测证据" size="65%">
      <template v-if="selected">
        <div class="note">{{ scoreMeaning(selected) }}</div>
        <div class="info-row">
          <span>唯一预测ID</span
          ><strong class="break-anywhere">{{ selected.id }}</strong>
        </div>
        <div class="info-row">
          <span>ETF / 策略ID</span
          ><strong>{{ selected.symbol }} / {{ selected.strategy_id }}</strong>
        </div>
        <div class="info-row">
          <span>模型 / 版本</span
          ><strong class="break-anywhere"
            >{{ names[selected.strategy_id] || selected.strategy_id }} /
            {{ selected.model_version || "历史未记录" }}</strong
          >
        </div>
        <div class="info-row">
          <span>模型训练资料截止</span
          ><strong>{{
            evidence(selected, "training_data_cutoff") || "历史未记录"
          }}</strong>
        </div>
        <div class="info-row">
          <span>参数拟合 / 校准截止</span
          ><strong
            >{{ evidence(selected, "fit_data_cutoff") || "历史未记录" }} /
            {{
              evidence(selected, "calibration_data_cutoff") || "历史未记录"
            }}</strong
          >
        </div>
        <div class="info-row">
          <span>行情所属日期</span><strong>{{ selected.signal_date }}</strong>
        </div>
        <div class="info-row">
          <span>输入行情截止（北京）</span
          ><strong>{{ time(evidence(selected, "market_cutoff")) }}</strong>
        </div>
        <div class="info-row">
          <span>输入资料截止（北京）</span
          ><strong>{{ time(selected.input_cutoff) }}</strong>
        </div>
        <div class="info-row">
          <span>新闻截止（北京）</span
          ><strong>{{
            evidence(selected, "news_cutoff")
              ? time(evidence(selected, "news_cutoff"))
              : "未使用 / 历史未记录"
          }}</strong>
        </div>
        <div class="info-row">
          <span>实际生成时间（北京）</span
          ><strong>{{ time(selected.created_at) }}</strong>
        </div>
        <div class="info-row">
          <span>目标交易日 / 预测跨度</span
          ><strong
            >{{ selected.target_date || "历史未记录 / 日历未确认" }} /
            {{ selected.horizon }}个交易日</strong
          >
        </div>
        <div class="info-row">
          <span>上涨概率 / 方向</span
          ><strong
            >{{ percent(selected.probability) }} /
            {{ statuses[selected.direction] || selected.direction }}</strong
          >
        </div>
        <div class="info-row">
          <span>原始分数 / 目标仓位</span
          ><strong
            >{{ rawScore(selected) }} /
            {{ percent(selected.details?.target_weight) }}</strong
          >
        </div>
        <div class="info-row">
          <span>模型参数版本</span
          ><strong class="break-anywhere">{{
            evidence(selected, "model_parameter_version") || "历史未记录"
          }}</strong>
        </div>
        <div class="info-row">
          <span>输入快照ID</span
          ><strong class="break-anywhere">{{
            selected.details?.input_snapshot_id || "历史未记录"
          }}</strong>
        </div>
        <div class="info-row">
          <span>到期验证</span><strong>{{ verdict(selected.actual) }}</strong>
        </div>
        <div class="info-row">
          <span>实际价格收益 / Brier</span
          ><strong
            >{{ percent(selected.actual?.actual_return) }} /
            {{ number(selected.actual?.brier_score) }}</strong
          >
        </div>
        <div class="info-row">
          <span>策略信号是否触发</span
          ><strong>{{
            selected.execution?.linkage_recorded
              ? selected.execution.signal_triggered
                ? "已触发"
                : "未触发"
              : "历史未记录交易关联"
          }}</strong>
        </div>
        <div class="info-row">
          <span>关联模拟订单 / 成交笔数</span
          ><strong
            >{{
              selected.execution?.linkage_recorded
                ? selected.execution.order_count
                : "—"
            }}
            /
            {{
              selected.execution?.linkage_recorded
                ? selected.execution.fill_count
                : "—"
            }}</strong
          >
        </div>
        <div class="info-row">
          <span>关联已实现盈亏（元）</span
          ><strong>{{
            selected.execution?.linkage_recorded
              ? number(selected.execution.realized_pnl)
              : "—"
          }}</strong>
        </div>
        <p v-if="selected.execution?.pnl_semantics" class="small muted">
          {{ selected.execution.pnl_semantics }}
        </p>
        <el-collapse
          ><el-collapse-item title="原始冻结证据与交易关联">
            <pre class="text-output">{{
              JSON.stringify(selected, null, 2)
            }}</pre>
          </el-collapse-item></el-collapse
        >
      </template>
    </el-drawer>
  </div>
</template>
