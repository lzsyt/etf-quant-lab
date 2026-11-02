import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import App from "./App.vue";
import "./style.css";

const routes = [
  { path: "/", redirect: "/forecast" },
  ...[
    [
      "forecast",
      "预测工作台",
      "选择同一个目标，查看各方案的预测、依据与历史。",
    ],
    [
      "validation",
      "预测验证",
      "按资产、目标和期限比较，到期事实与预测逐一核对。",
    ],
  ].map(([path, title, subtitle]) => ({
    path: `/${path}`,
    component: () => import("./pages/ForecastWorkspace.vue"),
    meta: { title, subtitle, forecastTab: path },
  })),
  // 旧书签回到预测页，不再提供账本功能。
  { path: "/funds/ledger", redirect: "/funds/trends" },
  ...[
    ["trends", "走势与预测", "看懂真实走势，也看懂预测的边界。"],
    ["evidence", "分析依据", "分清新闻事实、模型判断，以及仍然不知道的事。"],
  ].map(([tab, title, subtitle]) => ({
    path: `/funds/${tab}`,
    component: () => import("./pages/FundWorkspace.vue"),
    meta: { title, subtitle, fundTab: tab },
  })),
  {
    path: "/legacy-overview",
    component: () => import("./pages/Dashboard.vue"),
    meta: { title: "研究总览", subtitle: "用真实证据，检验每一个交易假设。" },
  },
  {
    path: "/market",
    component: () => import("./pages/Market.vue"),
    meta: {
      title: "ETF行情中心",
      subtitle: "可追溯的行情，是可靠研究的起点。",
    },
  },
  {
    path: "/predictions",
    component: () => import("./pages/Predictions.vue"),
    meta: {
      title: "AI预测中心",
      subtitle: "独立信号、冻结记录，与未来真实行情对账。",
    },
  },
  {
    path: "/backtests",
    component: () => import("./pages/Backtests.vue"),
    meta: {
      title: "策略回测中心",
      subtitle: "统一资金、成本与风险约束，比较样本外表现。",
    },
  },
  {
    path: "/paper",
    component: () => import("./pages/Paper.vue"),
    meta: {
      title: "模拟交易中心",
      subtitle: "每一笔虚拟资金变动，都有完整记录。",
    },
  },
  {
    path: "/rankings",
    component: () => import("./pages/Rankings.vue"),
    meta: { title: "模型与策略比较", subtitle: "历史成绩与前瞻证据分开衡量。" },
  },
  {
    path: "/reports",
    component: () => import("./pages/Reports.vue"),
    meta: {
      title: "每日量化研究报告",
      subtitle: "保存每个交易日的真实证据与历史版本。",
    },
  },
  {
    path: "/forward",
    component: () => import("./pages/Forward.vue"),
    meta: {
      title: "前瞻交易实验",
      subtitle: "同批次四策略，按冻结协议持续积累真实前瞻证据。",
    },
  },
  {
    path: "/intelligence",
    component: () => import("./pages/Intelligence.vue"),
    meta: {
      title: "财经情报",
      subtitle: "从原始来源到逐 ETF 分析，核对每条信息的时间与依据。",
    },
  },
  {
    path: "/gold",
    component: () => import("./pages/Gold.vue"),
    meta: {
      title: "黄金研究",
      subtitle: "研究人民币黄金基准的中长期变化，核算实物交易成本。",
    },
  },
  {
    path: "/system",
    component: () => import("./pages/System.vue"),
    meta: {
      title: "系统管理",
      subtitle: "查看数据任务、模型配置与服务运行状态。",
    },
  },
];
createApp(App)
  .use(createRouter({ history: createWebHashHistory(), routes }))
  .use(ElementPlus, { locale: zhCn })
  .mount("#app");
