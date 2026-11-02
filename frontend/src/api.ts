import { ref } from "vue";
import { ElMessage } from "element-plus";

export type Row = Record<string, any>;

/** 原生记录按显式算法区分，兼容黄金旧协议名称；缺字段仍沿用历史 LightGBM 身份。 */
export function quantAlgorithm(record?: Row | null): string {
  const value = String(
    record?.algorithm || record?.metadata?.algorithm || "lightgbm",
  );
  const algorithm = value.toLowerCase();
  return ["xgboost", "xgboost_small_fixed"].includes(algorithm)
    ? "XGBoost"
    : ["lightgbm", "lightgbm_small_fixed"].includes(algorithm)
      ? "LightGBM"
      : value;
}
export const signedIn = ref(false);
let csrf: { header: string; token: string } | null = null;

export async function refreshCsrf() {
  const result = await fetch("/api/auth/csrf", { credentials: "same-origin" });
  if (!result.ok) throw new Error("无法建立安全会话");
  csrf = await result.json();
}

export async function api<T = any>(
  path: string,
  body?: unknown,
  options: { method?: string; idempotencyKey?: string } = {},
): Promise<T> {
  if (body !== undefined && !csrf) await refreshCsrf();
  const headers: Record<string, string> = { Accept: "application/json" };
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    headers[csrf!.header] = csrf!.token;
  }
  if (options.idempotencyKey)
    headers["Idempotency-Key"] = options.idempotencyKey;
  const response = await fetch("/api" + path, {
    method: options.method || (body === undefined ? "GET" : "POST"),
    credentials: "same-origin",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (response.status === 401) {
    signedIn.value = false;
    throw new Error("登录已失效，请重新登录");
  }
  if (!response.ok) {
    const value = await response.json().catch(() => ({}));
    throw new Error(
      value.message || value.error || `请求失败（${response.status}）`,
    );
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

export async function login(username: string, password: string) {
  await refreshCsrf();
  const response = await fetch("/api/auth/login", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      [csrf!.header]: csrf!.token,
    },
    body: new URLSearchParams({ username, password }),
  });
  if (!response.ok) throw new Error("账号或密码不正确");
  await refreshCsrf();
  signedIn.value = true;
}

export async function logout() {
  await api("/auth/logout", {});
  signedIn.value = false;
  csrf = null;
}

/** 改密成功后清除当前页面的登录状态及已失效的CSRF令牌。 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
  confirmation: string,
) {
  await api("/auth/password", { currentPassword, newPassword, confirmation });
  signedIn.value = false;
  csrf = null;
}

export async function guarded(action: () => Promise<unknown>) {
  try {
    await action();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "操作失败");
  }
}

export const money = (value: unknown) =>
  value == null || value === "" || !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toLocaleString("zh-CN", {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      });
export const percent = (value: unknown) =>
  value == null || value === "" || !Number.isFinite(Number(value))
    ? "—"
    : `${(Number(value) * 100).toFixed(2)}%`;
export const number = (value: unknown) =>
  value == null || value === "" || !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toFixed(3);
export const time = (value: unknown) =>
  value
    ? new Date(String(value)).toLocaleString("zh-CN", {
        timeZone: "Asia/Shanghai",
        hour12: false,
      })
    : "—";
export const names: Row = {
  ma: "均线趋势",
  rsi: "RSI反转",
  macd: "MACD",
  momentum: "趋势动量",
  rotation: "相对强弱轮动",
  lightgbm: "LightGBM",
  xgboost: "XGBoost",
  llm: "LLM事件情绪",
  fusion: "融合实验 70/30",
  buy_hold: "风险约束买入持有",
  cash: "现金基准",
};
export const statuses: Row = {
  UP: "上涨",
  DOWN: "下跌",
  FLAT: "中性/空仓",
  MISSING: "缺失",
  SUCCEEDED: "成功",
  FAILED: "失败",
  QUEUED: "等待",
  RUNNING: "执行中",
  RESERVED: "已预留",
  UNCERTAIN: "待核账",
  CONFIRMED: "已确认估算",
  FILLED: "已成交",
  REJECTED: "未成交",
  REAL: "真实数据",
  DEMO: "演示数据",
  MOCK: "Mock测试",
  CSV_UNVERIFIED: "未核验CSV",
  SKIPPED: "已跳过",
  NO_TRADES: "未发生交易",
  LOW_ACTIVITY: "交易稀少",
  ACTIVE: "已有交易",
  INSUFFICIENT_DATA: "样本不足",
  INSUFFICIENT_SAMPLES: "样本不足",
  FORWARD_OBSERVATIONS: "已有前瞻样本",
};

export async function submitTask(type: string, payload: Row = {}) {
  return api("/tasks", { type, payload, business_key: crypto.randomUUID() });
}
