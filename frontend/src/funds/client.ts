import { api, type Row } from "../api";

export type FundMode = "REAL" | "DEMO";
export const demoEnabled = import.meta.env.VITE_FUND_DEMO_ENABLED === "true";
export const fundNames: Record<string, string> = {
  "460300": "华泰柏瑞沪深300ETF联接A",
  "007169": "易方达中债1–3年国开行债券指数A",
  "008701": "华夏黄金ETF联接A",
};
export const labelMap: Record<string, string> = {
  SUBSCRIPTION: "申购",
  REDEMPTION: "赎回",
  DIVIDEND_CASH: "现金分红",
  DIVIDEND_REINVESTMENT: "红利再投资",
  FIXED: "固定定投",
  RULE: "简单规则研究",
  MODEL: "逐期训练模型研究",
  SUCCEEDED: "完成",
  QUEUED: "排队中",
  RUNNING: "运行中",
  NOT_AVAILABLE: "不满足比较条件",
  NO_TRADES: "没有成交，尚不能判断策略能力",
  NAV_CHANGE_NOT_TOTAL_RETURN: "单位净值变化不含完整分红总回报",
  HISTORICAL_POINT_IN_TIME_UNVERIFIED: "历史资料的当时可得性尚未核实",
  DEALING_CALENDAR_UNVERIFIED: "销售渠道申请日历未核实",
  DIVIDEND_SPLIT_COVERAGE_UNVERIFIED: "分红与拆分覆盖尚未核实",
  NO_VALIDATED_ADJUSTMENT_POLICY: "尚无通过验证的金额调整规则",
  PLANNED_FUNDING_NOT_POSTED: "计划资金尚未到账",
  FUND_NAV_OBSERVATIONS: "基金净值观察日",
  NOT_STRICT_PIT_CURRENT_VINTAGE_WITH_CONSERVATIVE_DELAY:
    "采用当前修订净值与保守延迟，不是严格当时可得回测",
  NOT_TOTAL_RETURN_CORPORATE_ACTIONS_UNVERIFIED:
    "分红拆分未完整核实，不是总回报",
  COMMON_NAV_DATES_NOT_VERIFIED_CHANNEL_CALENDAR:
    "共同净值日仅为研究假设，不代表已核实渠道开放日",
  SIMPLIFIED_SAME_DAY_CONFIRMATION_NOT_ACTUAL_EXECUTION:
    "研究按同日确认简化，实际确认仍以平台为准",
  DECLARED_FEE_ASSUMPTION_NOT_VERIFIED_CHANNEL_FEE:
    "比较费率是声明假设，不是已核实渠道费率",
  RESEARCH_MULTIPLIERS_NOT_VALIDATED_INVESTMENT_POLICY:
    "调整倍数只供研究，未获真实投资资格",
  NO_FUND_NEWS_SNAPSHOT: "尚未生成本基金专属新闻快照",
  NO_FROZEN_FUND_FORECAST: "尚无本基金该窗口的冻结预测",
  NO_FUND_MODEL: "尚无可用基金模型工件",
  NAV_MISSING_OR_STALE: "净值缺失或已陈旧",
  FUND_LLM_DISABLED: "基金大模型分析未启用",
  LLM_DISABLED: "大模型分析未启用",
  NO_FORWARD_EVIDENCE: "真实前瞻样本尚未积累",
  NOT_TOTAL_RETURN: "当前仅研究净值变化，不含完整分红",
  NOT_STRICT_PIT: "历史资料不能证明当时已经可得",
  RESEARCH_NOT_CASH_ADVICE: "研究判断不能直接作为资金调整依据",
  CALENDAR_UNVERIFIED: "未来净值日历未核实，目标日期待确认",
  WAITING_OBSERVATIONS: "等待足够净值观测",
  READY_FOR_EVALUATION: "观测已足，等待到期核验任务",
  OVERLAPPING_HORIZON_LABELS: "窗口标签存在重叠，样本不相互独立",
  REAL: "真实数据",
  DEMO: "演示数据",
  EQUITY: "股票宽基",
  BOND: "短期政策性金融债",
  GOLD: "黄金",
  VALID: "可用",
  UNVERIFIED: "待身份核验",
  INVALID: "无效",
  FUND_IDENTITY_NOT_VERIFIED: "旧基金分析尚未通过身份校验",
  FUND_IDENTITY_MISMATCH: "基金身份或材料归属不一致",
  WARNING: "待核验",
  MISSING: "缺少数据",
  STALE: "数据陈旧",
  NOT_LOADED: "尚未加载",
  PARTIAL: "部分已知",
  COMPLETE: "完整",
  MODEL_NOT_READY: "尚无有效模型",
  NOT_DUE: "尚未到期",
  WAITING_DATA: "等待实际净值",
  EVALUATED: "已验证",
  FROZEN: "已冻结",
  HISTORICAL_RESEARCH: "历史研究回放",
  FORWARD_VALIDATION: "前瞻冻结验证",
  RESEARCH_ONLY: "仅供研究",
  ACCEPTED: "已通过资格门槛",
  FAILED: "未通过",
  UNTESTED: "尚未验证",
  DIRECTION_ONLY: "仅预测方向",
  RETURN_POINT: "收益终点",
  RETURN_QUANTILES: "收益终点与分位区间",
  NAV_CHANGE: "单位净值变化（不含完整分红）",
  FUND_NAV_CHANGE: "单位净值变化（不含完整分红）",
  TOTAL_RETURN: "含分红总回报",
  FUND_TOTAL_RETURN: "含分红总回报",
  MODEL_ONLY: "仅使用历史数据模型",
  MODEL_AND_NEWS: "模型与新闻均参与",
  NEWS_UNAVAILABLE: "新闻分析暂不可用",
  NO_VALID_SIGNAL: "暂无有效判断",
  CONFLICT: "模型与新闻存在冲突",
  DISPLAY_ONLY: "仅作资料展示",
  NOT_USED: "本次未使用新闻",
  INSUFFICIENT_INPUT: "材料不足",
  INSUFFICIENT_DATA: "有效资料或样本不足",
  BUDGET_EXCEEDED: "超过预算",
  BUDGET_BLOCKED: "费用预算已用尽",
  PARSE_ERROR: "分析格式校验未通过",
  CACHE_HIT: "复用已有分析",
  POSITIVE: "偏积极",
  NEGATIVE: "风险提示",
  MIXED: "影响不一",
  NEUTRAL: "中性",
  UNKNOWN: "未知",
  BASELINE_ONLY: "维持原计划",
  ADJUSTMENT_ELIGIBLE: "允许调整",
  BLOCKED_DATA: "数据条件未满足",
  ALREADY_EXECUTED: "本期已执行",
  ACTUAL_MANUAL: "真实手工账本",
  SIMULATED: "模拟研究账户",
  DRAFT: "草稿 / 待补资料",
  SUBMITTED: "已提交 / 待确认",
  CONFIRMED: "已确认",
  CANCELLED: "已撤销",
  REVERSED: "已冲正 / 待退款",
  REFUNDED: "退款已到账",
  USER_REPORTED_PURCHASES_NOT_CONFIRMED:
    "用户报告的购买仍有待确认记录；总额保持部分已知",
  UNKNOWN_CASH_BALANCE: "现金余额尚未核实",
  NAV_NOT_AVAILABLE: "净值尚未公布或未加载",
  NEWS_NOT_AVAILABLE: "没有有效新闻分析",
  NO_VALIDATED_FUSION: "暂无已验证融合收益预测",
};
/** 未识别的服务端原因码仍原样保留，避免隐藏真实失败原因。 */
export function label(value: unknown): string {
  if (value == null || value === "") return "待确认";
  return labelMap[String(value)] || String(value);
}
/** 外部资料只开放明确的 HTTP(S) 链接，不执行网页中的脚本。 */
export function safeLink(value: unknown): string | undefined {
  try {
    const u = new URL(String(value));
    return ["http:", "https:"].includes(u.protocol) ? u.href : undefined;
  } catch {
    return undefined;
  }
}
/** 货币输入以分精度验证；禁止将错误输入静默截断或改成零。 */
export function validMoney(value: string): boolean {
  return /^(0|[1-9]\d{0,11})(\.\d{1,2})?$/.test(value);
}
/** 前端快速合计仅采用整数分；最终经济校验仍由服务端执行。 */
export function sumMoney(values: string[]): string {
  if (!values.every(validMoney)) return "—";
  const cents = values.reduce((sum, v) => {
    const [a, b = ""] = v.split(".");
    return sum + BigInt(a) * 100n + BigInt(b.padEnd(2, "0"));
  }, 0n);
  return `${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
}
/** 演示数据只存在单独适配器中，任何正式请求失败都不会落到此分支。 */
function demoRead(path: string): Row {
  const code =
    path.match(/funds\/(\d+)/)?.[1] ||
    path.match(/forecasts\/demo-(\d+)/)?.[1] ||
    "460300";
  const horizon = path.includes("horizon=60") || path.endsWith("-60") ? 60 : 20;
  const observations = [
    "2026-10-21",
    "2026-10-22",
    "2026-10-23",
    "2026-10-26",
    "2026-10-27",
  ].map((nav_date, i) => ({
    nav_date,
    unit_nav: String(1 + i * 0.01),
    provider: "DEMO_FIXTURE",
    revision: 1,
    quality_status: "VALID",
    first_observed_at: `${nav_date}T15:00:00Z`,
    published_at: null,
  }));
  const forecast = {
    id: `demo-${code}-${horizon}`,
    fund_code: code,
    nature: "DEMO",
    evidence_type: "HISTORICAL_RESEARCH",
    capability: "RETURN_POINT",
    target_basis: "NAV_CHANGE",
    horizon,
    anchor_date: "2026-10-27",
    anchor_value: "1.04",
    generated_at: "2026-10-28T08:00:00Z",
    input_cutoff_at: "2026-10-27T15:00:00Z",
    model_version_id: "DEMO_ONLY",
    validation_status: "RESEARCH_ONLY",
    points: [
      {
        horizon,
        target_date: horizon === 20 ? "2026-11-25" : "2027-01-22",
        point_return: "0.012",
        probability_up: null,
        actual_return: null,
        evaluation_status: "NOT_DUE",
      },
    ],
    reason_codes: ["演示终点，不是真实模型结果"],
  };
  const news = {
    fund_code: code,
    nature: "DEMO",
    analysis_status: "INSUFFICIENT_INPUT",
    sentiment_score: null,
    forecast_usage: "NOT_USED",
    documents: [],
    facts: [],
    inferences: [],
    uncertainties: ["演示环境不调用付费模型"],
    reason_codes: ["DEMO_ONLY"],
  };
  if (path === "/funds")
    return {
      nature: "DEMO",
      completeness: "COMPLETE",
      funds: Object.entries(fundNames).map(([code, name], i) => ({
        code,
        name,
        asset_class: ["EQUITY", "BOND", "GOLD"][i],
        latest_nav: "1.0400",
        nav_date: "2026-10-27",
        nav_status: "VALID",
        warnings: ["仅供交互演示"],
      })),
      tasks: [],
    };
  if (path.includes("/nav"))
    return {
      fund_code: code,
      nature: "DEMO",
      target_basis: "NAV_CHANGE",
      status: "VALID",
      observations,
      warnings: ["模拟净值不能作为投资依据"],
    };
  if (path.includes("/forecasts?"))
    return {
      fund_code: code,
      horizon,
      forecasts: [forecast],
      models: [],
      status: "RESEARCH_ONLY",
      reason_codes: [],
    };
  if (path.startsWith("/forecasts/")) return forecast;
  if (path.includes("research-summary"))
    return {
      fund_code: code,
      forecast,
      forecast_id: forecast.id,
      usage: "MODEL_ONLY",
      news,
      reason_codes: ["DEMO_ONLY"],
    };
  if (path.includes("/news")) return news;
  throw new Error("演示模式不支持此操作");
}
/** 由用户显式选择且构建开关允许时读取演示域，其余始终访问真实 API。 */
export async function fundRead(path: string, mode: FundMode): Promise<any> {
  if (mode === "DEMO") {
    if (!demoEnabled) throw new Error("演示入口未启用");
    return demoRead(path);
  }
  return api(path);
}
/** 每次明确操作持有一个幂等键，网络失败重试时由调用方保留该键。 */
export async function fundWrite(
  path: string,
  body: Row,
  key: string,
  method = "POST",
) {
  return api(path, body, { method, idempotencyKey: key });
}
