<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { number, type Row } from "../api";
import { demoEnabled, fundRead, label, type FundMode } from "../funds/client";
import FundTrends from "../funds/FundTrends.vue";
import FundEvidence from "../funds/FundEvidence.vue";
const route = useRoute(),
  router = useRouter();
const funds = ref<Row[]>([]),
  error = ref(""),
  loading = ref(true);
const help = ref(false);
const mode = computed<FundMode>(() =>
  demoEnabled && route.query.mode === "DEMO" ? "DEMO" : "REAL",
);
const code = computed(() =>
  ["460300", "007169", "008701"].includes(String(route.query.fund))
    ? String(route.query.fund)
    : "460300",
);
const horizon = computed(() => (route.query.horizon === "60" ? 60 : 20));
const selected = computed(() => funds.value.find((f) => f.code === code.value));
let generation = 0;
/** 最新目录请求独占当前显示结果，旧响应不能覆盖新模式。 */
async function load() {
  const ticket = ++generation;
  loading.value = true;
  error.value = "";
  funds.value = [];
  try {
    const r = await fundRead("/funds", mode.value);
    if (ticket === generation) funds.value = r.funds;
  } catch (e) {
    if (ticket === generation)
      error.value = e instanceof Error ? e.message : "目录读取失败";
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
/** 基金、窗口和模式以 URL 表达，跨页、刷新及后退保持同一上下文。 */
function change(key: string, value: string) {
  router.replace({
    query: { ...route.query, [key]: value, forecast: undefined },
  });
}
/** 子页成功读取净值后同步目录日期，避免任务完成后卡片仍显示旧截止日。 */
function navLoaded(nav: Row) {
  const fund = funds.value.find((f) => f.code === nav.fund_code);
  const last = nav.observations?.at(-1);
  if (fund) {
    fund.latest_nav = last?.unit_nav ?? null;
    fund.nav_date = last?.nav_date ?? null;
    fund.nav_status = nav.status;
  }
}
watch(mode, load, { immediate: true });
</script>
<template>
  <section class="fund-workspace">
    <div class="toolbar" aria-label="原基金研究页面">
      <router-link :to="{ path: '/funds/trends', query: route.query }"
        >01 走势与预测</router-link
      >
      <router-link :to="{ path: '/funds/evidence', query: route.query }"
        >02 分析依据</router-link
      >
      <router-link
        :to="{
          path: '/forecast',
          query: { asset: code, horizon: String(horizon), mode },
        }"
        >统一预测工作台 →</router-link
      >
    </div>
    <div class="fund-context toolbar">
      <el-tag :type="mode === 'DEMO' ? 'warning' : 'success'">{{
        mode === "DEMO"
          ? "DEMO · 独立演示，仅供研究"
          : "REAL · 真实数据预测与分析"
      }}</el-tag>
      <span class="small"
        >数据截止：{{ selected?.nav_date || "尚未取得净值" }}</span
      >
      <span class="small">{{ label(selected?.nav_status || "MISSING") }}</span>
      <el-button
        v-if="demoEnabled"
        text
        @click="change('mode', mode === 'REAL' ? 'DEMO' : 'REAL')"
        >{{ mode === "REAL" ? "查看独立演示" : "返回真实数据" }}</el-button
      >
      <el-button text @click="help = true">投资词典</el-button>
    </div>
    <el-alert v-if="error" type="error" :closable="false" :title="error"
      ><el-button @click="load">重新读取目录</el-button></el-alert
    >
    <el-skeleton v-if="loading" :rows="2" animated />
    <div v-else class="fund-cards" role="group" aria-label="选择基金">
      <button
        v-for="f in funds"
        :key="f.code"
        class="fund-card"
        :class="{ selected: code === f.code }"
        :aria-pressed="code === f.code"
        @click="change('fund', f.code)"
      >
        <span class="fund-card-name">{{ f.name }}</span
        ><span class="small muted"
          >{{ f.code }} · {{ label(f.asset_class) }}</span
        >
        <strong>{{ f.latest_nav ?? "—" }}<small> 单位净值</small></strong
        ><span class="small"
          >{{ f.nav_date || "暂无已公布数据" }} ·
          {{ label(f.nav_status) }}</span
        >
      </button>
    </div>
    <template v-if="!loading && !error">
      <FundTrends
        v-if="route.meta.fundTab === 'trends'"
        :code="code"
        :horizon="horizon"
        :mode="mode"
        @horizon="change('horizon', String($event))"
        @nav-loaded="navLoaded"
      />
      <FundEvidence
        v-else-if="route.meta.fundTab === 'evidence'"
        :code="code"
        :horizon="horizon"
        :mode="mode"
      />
    </template>
    <el-dialog v-model="help" title="几个常用词" width="540px">
      <dl class="fund-definitions">
        <dt>单位净值</dt>
        <dd>每一份基金的价值，用于观察基金走势。</dd>
        <dt>上涨概率</dt>
        <dd>模型判断上涨的可能性，不是预计涨幅，更不是应该增加的投入比例。</dd>
        <dt>回测与前瞻验证</dt>
        <dd>回测重放历史；前瞻验证核对当时真正冻结的预测。两者分别统计。</dd>
        <dt>回撤</dt>
        <dd>剔除入金影响后，从先前高点回落的幅度，不代表未来最大损失。</dd>
      </dl>
      <template #footer
        ><el-button @click="help = false">关闭</el-button></template
      >
    </el-dialog>
  </section>
</template>
