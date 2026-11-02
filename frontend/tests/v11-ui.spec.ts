import { test, expect, type Page } from "@playwright/test";

type MockPayload = unknown | ((url: URL) => unknown);

for (const width of [1440, 390]) {
  /** 设置真实交互使用可持久的接口夹具：保存、刷新、无效组合和失败后的当前值均需一致。 */
  test(`模型设置分别保存并按模型切换强度 ${width}`, async ({ page }) => {
    const providers = [
      {
        id: "deepseek",
        name: "DeepSeek",
        enabled: true,
        configured: true,
        current: { model: "deepseek-flash", reasoning_effort: "none" },
        models: [
          {
            id: "deepseek-flash",
            name: "DeepSeek Flash",
            efforts: ["none", "low", "high", "max"],
            input_per_million: "0.3",
            output_per_million: "1.2",
          },
          {
            id: "deepseek-v4-pro",
            name: "DeepSeek V4 Pro",
            efforts: ["none", "low", "high", "max"],
            input_per_million: "1.32",
            output_per_million: "3.96",
          },
        ],
      },
      {
        id: "openai",
        name: "GPT",
        enabled: true,
        configured: true,
        current: { model: "gpt-6.1-sol", reasoning_effort: "low" },
        models: [
          {
            id: "gpt-6.1-sol",
            name: "GPT-6.1 Sol",
            efforts: ["low", "medium", "high", "xhigh", "max"],
            input_per_million: "2",
            output_per_million: "10",
          },
          {
            id: "gpt-6-luna",
            name: "GPT-6 Luna",
            efforts: ["none", "low", "medium", "high", "xhigh", "max"],
            input_per_million: "0.1",
            output_per_million: "0.5",
          },
        ],
      },
    ];
    const writes: { path: string; body: any }[] = [];
    let reject = false;
    await page.setViewportSize({ width, height: 1000 });
    await page.route("**/api/**", async (route) => {
      const request = route.request(),
        path = new URL(request.url()).pathname;
      if (request.method() === "POST") {
        const body = request.postDataJSON();
        writes.push({ path, body });
        const provider = providers.find(
          (p) => path === `/api/system/model-settings/${p.id}`,
        );
        if (!provider || reject)
          return route.fulfill({
            status: 400,
            json: { message: "所选模型或推理强度不受支持" },
          });
        provider.current = body;
        return route.fulfill({ json: body });
      }
      if (path === "/api/auth/me")
        return route.fulfill({ json: { username: "admin" } });
      if (path === "/api/auth/csrf")
        return route.fulfill({
          json: { header: "X-CSRF-TOKEN", token: "TEST_ONLY" },
        });
      if (path === "/api/system")
        return route.fulfill({
          json: {
            llm: { providers, daily_limit_usd: 1, gpt_daily_limit_usd: 1 },
          },
        });
      return route.fulfill({ json: [] });
    });
    await page.goto("/#/system");
    await expect(
      page.getByLabel("DeepSeek 推理模型", { exact: true }),
    ).toHaveValue("deepseek-flash");
    await expect(page.getByLabel("GPT 推理强度", { exact: true })).toHaveValue(
      "low",
    );
    await page
      .getByLabel("DeepSeek 推理模型", { exact: true })
      .selectOption("deepseek-v4-pro");
    await page
      .getByLabel("DeepSeek 推理强度", { exact: true })
      .selectOption("high");
    await page
      .getByRole("button", { name: "保存 DeepSeek 设置", exact: true })
      .click();
    await expect(
      page.getByText("DeepSeek 配置已保存，后续分析生效"),
    ).toBeVisible();
    await page
      .getByLabel("GPT 推理模型", { exact: true })
      .selectOption("gpt-6-luna");
    await page.getByLabel("GPT 推理强度", { exact: true }).selectOption("none");
    await page
      .getByLabel("GPT 推理模型", { exact: true })
      .selectOption("gpt-6.1-sol");
    await expect(page.getByLabel("GPT 推理强度", { exact: true })).toHaveValue(
      "low",
    );
    await page
      .getByLabel("GPT 推理模型", { exact: true })
      .selectOption("gpt-6-luna");
    await page.getByLabel("GPT 推理强度", { exact: true }).selectOption("high");
    await page
      .getByRole("button", { name: "保存 GPT 设置", exact: true })
      .click();
    await expect(page.getByText("GPT 配置已保存，后续分析生效")).toBeVisible();
    await page.reload();
    await expect(
      page.getByLabel("DeepSeek 推理模型", { exact: true }),
    ).toHaveValue("deepseek-v4-pro");
    await expect(page.getByLabel("GPT 推理模型", { exact: true })).toHaveValue(
      "gpt-6-luna",
    );
    await expect(page.getByLabel("GPT 推理强度", { exact: true })).toHaveValue(
      "high",
    );
    await expect(
      page.getByRole("group", { name: "GPT模型配置", exact: true }),
    ).toContainText("输入 $0.1 / 输出 $0.5");
    reject = true;
    await page.getByLabel("GPT 推理强度", { exact: true }).selectOption("max");
    await page
      .getByRole("button", { name: "保存 GPT 设置", exact: true })
      .click();
    await expect(page.getByText("所选模型或推理强度不受支持")).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("GPT 推理强度", { exact: true })).toHaveValue(
      "high",
    );
    expect(writes.map((w) => w.path)).toEqual([
      "/api/system/model-settings/deepseek",
      "/api/system/model-settings/openai",
      "/api/system/model-settings/openai",
    ]);
    expect(writes[0].body).toEqual({
      model: "deepseek-v4-pro",
      reasoning_effort: "high",
    });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
  });
}

/** 使用明确标记的固定接口夹具验证界面语义，不能将本测试结果称为真实模型或线上接口验收。 */
async function mockApi(page: Page, payloads: Record<string, MockPayload>) {
  const writes: string[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() !== "GET") writes.push(url.pathname);
    const fixture =
      url.pathname === "/api/auth/me"
        ? { username: "admin" }
        : payloads[url.pathname];
    if (fixture === undefined) {
      await route.fulfill({
        status: 404,
        json: { message: "测试夹具未定义该接口" },
      });
      return;
    }
    const value = await (typeof fixture === "function"
      ? fixture(url)
      : fixture);
    await route.fulfill({ json: value });
  });
  return writes;
}

/** 系统页展示具体缺失日期、ETF与重试安排，保留旧失败只有异常类名时的兼容显示。 */
test("日终任务显示真实失败原因及恢复安排", async ({ page }) => {
  await mockApi(page, {
    "/api/system": {
      llm: { daily_limit_usd: 1, gpt_daily_limit_usd: 1 },
    },
    "/api/system/audit": [],
    "/api/tasks": [
      {
        id: "fixture-forward-failed",
        task_type: "FORWARD_EOD",
        status: "FAILED",
        attempts: 4,
        updated_at: "2026-10-26T10:41:48Z",
        error_code: "IllegalArgumentException",
        error_message: "日终行情缺失：2026-10-26，ETF 159915；已停止结算与预测",
      },
      {
        id: "fixture-old-failed",
        task_type: "TRAIN",
        status: "FAILED",
        error_code: "LegacyFailureFixture",
      },
    ],
  });
  await page.goto("/#/system");
  await expect(
    page.getByText("日终行情缺失：2026-10-26，ETF 159915；已停止结算与预测"),
  ).toBeVisible();
  await expect(
    page.getByText("当日交易时段结束后按小时自动重试，数据补齐后继续"),
  ).toBeVisible();
  await expect(page.getByText("LegacyFailureFixture")).toBeVisible();
  await expect(
    page.locator(".info-row").filter({ hasText: "DeepSeek 每日费用上限" }),
  ).toHaveText(/DeepSeek 每日费用上限（USD）\s*1/);
  await expect(
    page.locator(".info-row").filter({ hasText: "GPT 每日费用上限" }),
  ).toHaveText(/GPT 每日费用上限（USD）\s*1/);
  await expect(page.getByText(/两家额度独立/)).toBeVisible();
});

/** 未到期的周末预测保留真实生成时间，score不能被界面格式化为收益率。 */
test("预测分数与周末实际生成时间保持原义", async ({ page }) => {
  await mockApi(page, {
    "/api/predictions": [
      {
        prediction: {
          id: "fixture-frozen-1",
          symbol: "510300",
          strategy_id: "fusion",
          horizon: 1,
          signal_date: "2026-10-23",
          target_date: "2026-10-26",
          created_at: "2026-10-24T02:15:00Z",
          probability: null,
          raw_score: -0.012,
          direction: "DOWN",
          model_version: "fixture-fusion-v1",
          market_cutoff: "2026-10-23T07:00:00Z",
          details: { score: -0.012, target_weight: "0" },
        },
        actual: null,
      },
    ],
    "/api/analyses": [],
    "/api/news": [],
    "/api/models": [],
  });
  await page.goto("/#/predictions");
  const row = page.locator(".el-table__body tr").first();
  await expect(row).toContainText("-0.012");
  await expect(row).toContainText("2026-10-26");
  await expect(row).toContainText("2026/10/24");
  await expect(row).not.toContainText("-1.20%");
  await row.click();
  await expect(
    page.getByRole("heading", { name: "冻结预测证据" }),
  ).toBeVisible();
  await expect(page.locator(".el-drawer")).toContainText("fixture-frozen-1");
  await expect(page.locator(".el-drawer")).toContainText(
    "不是预期收益率或校准概率",
  );
  await expect(page.locator(".el-drawer")).toContainText("历史未记录");
});

/** 已启用但未被调度的账户必须解释无成交原因，不能将数据库开关写成运行中。 */
test("模拟策略启用不冒充任务真实执行", async ({ page }) => {
  await mockApi(page, {
    "/api/paper/accounts": [
      {
        id: "lightgbm",
        enabled: true,
        equity: "100000",
        cash: "100000",
        initial_capital: "100000",
        state: { cumulative_fees: "0" },
      },
    ],
    "/api/paper/positions": [],
    "/api/paper/orders": [],
    "/api/paper/fills": [],
    "/api/paper/snapshots": [],
    "/api/risk": { current: {}, history: [] },
    "/api/risk/events": [],
    "/api/paper/diagnostics": {
      reason_code: "TASK_NOT_EXECUTED",
      message: "策略任务尚未真正执行",
      enabled: true,
      scheduling_enabled: false,
      last_task: null,
      fill_count: 0,
      capital_utilization: 0,
    },
    "/api/paper/reconstruction": {
      consistent: true,
      cash_difference: "0",
      quantity_differences: [],
    },
  });
  await page.goto("/#/paper");
  await expect(page.getByText("策略已启用", { exact: true })).toBeVisible();
  await expect(page.getByTestId("paper-diagnostic-message")).toHaveText(
    "策略任务尚未真正执行",
  );
  await expect(page.getByText("模拟策略运行中", { exact: true })).toHaveCount(
    0,
  );
  await page.getByRole("button", { name: "核对成交与账户" }).click();
  await expect(page.getByText(/当前账户与历史账本一致/)).toBeVisible();
});

/** 没有交易的低回撤不被包装为风险控制结果，历史缺失指标保持未知。 */
test("回测零成交明确提示且不编造缺失指标", async ({ page }) => {
  await mockApi(page, {
    "/api/market/instruments": [{ symbol: "510500", name: "中证500ETF" }],
    "/api/backtests": [
      {
        id: "fixture-experiment",
        status: "SUCCEEDED",
        nature: "REAL",
        created_at: "2026-10-24T10:00:00Z",
      },
    ],
    "/api/backtests/fixture-experiment": {
      result: {
        nature: "REAL",
        start: "2026-04-01",
        end: "2026-10-23",
        results: {
          xgboost: {
            metrics: {
              total_return: 0,
              annualized_return: 0,
              max_drawdown: 0,
              trade_count: 0,
              win_rate: null,
              capital_utilization: null,
            },
            curve: [],
            fills: [],
          },
        },
      },
    },
  });
  await page.goto("/#/backtests");
  await expect(page.getByTestId("backtest-activity")).toContainText(
    "未发生交易",
  );
  await expect(page.getByTestId("backtest-activity")).toContainText("不能证明");
  await expect(page.locator(".page")).not.toContainText("NaN");
  await expect(
    page.getByText("风险约束买入持有与各策略使用相同仓位限制", {
      exact: false,
    }),
  ).toBeVisible();
});

/** 四类证据必须分别请求和显示，实时前瞻不得继承历史收益。 */
test("排行榜四类结果隔离且缺样本不绘制虚构曲线", async ({ page }) => {
  const seen: string[] = [];
  await mockApi(page, {
    "/api/models": [],
    "/api/fusion/evaluation": {
      status: "INSUFFICIENT_DATA",
      available_dates: 0,
      required_dates: 150,
    },
    "/api/rankings": (url: URL) => {
      const mode = url.searchParams.get("mode") || "";
      seen.push(mode);
      return {
        mode,
        rows: [
          {
            strategy_id: "lightgbm",
            label: "夹具" + mode,
            total_return: mode === "history" ? 0.1 : null,
            samples: 0,
          },
        ],
        curves: [],
        calibration: [],
        limitations: ["固定测试夹具，不是市场研究结果"],
      };
    },
  });
  await page.goto("/#/rankings");
  await expect(
    page.getByText("夹具history", { exact: true }).first(),
  ).toBeVisible();
  for (const [label, mode] of [
    ["严格样本外回测", "oos"],
    ["实时前瞻性验证", "forward"],
    ["实时模拟交易", "paper"],
  ]) {
    await page
      .getByRole("radiogroup", { name: "比较证据类型" })
      .getByText(label, { exact: true })
      .click();
    await expect(
      page.getByText("夹具" + mode, { exact: true }).first(),
    ).toBeVisible();
    await expect(page.getByText("夹具history", { exact: true })).toHaveCount(0);
  }
  expect(seen).toEqual(["history", "oos", "forward", "paper"]);
  await expect(page.getByRole("img", { name: "研究数据图表" })).toHaveCount(0);
  await expect(
    page.getByText("0 / 150 个到期信号交易日", { exact: true }),
  ).toBeVisible();
});

/** 概率校准必须有可用概率/实际频率，界面展示真实夹具样本，不用情绪分数伪造概率。 */
test("排行榜只依据已提供曲线和校准样本绘图", async ({ page }) => {
  await mockApi(page, {
    "/api/models": [],
    "/api/fusion/evaluation": {
      status: "INSUFFICIENT_DATA",
      available_dates: 2,
      required_dates: 150,
    },
    "/api/rankings": {
      mode: "history",
      rows: [],
      curves: [
        {
          strategy_id: "lightgbm",
          points: [
            {
              date: "2026-10-22",
              total_return: 0,
              drawdown: 0,
              accuracy: 0.5,
              samples: 2,
            },
            {
              date: "2026-10-23",
              total_return: -0.01,
              drawdown: 0.01,
              accuracy: 0.75,
              samples: 4,
            },
          ],
        },
      ],
      calibration: [
        {
          strategy_id: "lightgbm",
          bucket: "0.6–0.7",
          predicted_probability: 0.65,
          observed_frequency: 0.5,
          samples: 2,
        },
      ],
    },
  });
  await page.goto("/#/rankings");
  await expect(page.getByRole("img", { name: "研究数据图表" })).toHaveCount(4);
  await expect(page.getByText("65.00%", { exact: true })).toBeVisible();
  await expect(page.getByText("0.6–0.7", { exact: true })).toBeVisible();
});

/** 空日报保持空态，不能凭空显示一天的分析、收益或模型费用。 */
test("日报没有归档时显示样本不足", async ({ page }) => {
  const writes = await mockApi(page, { "/api/reports": [] });
  await page.goto("/#/reports");
  await expect(page.getByTestId("daily-report-empty")).toContainText(
    "样本不足",
  );
  await expect(page.locator(".stats")).toHaveCount(0);
  expect(writes).toEqual([]);
});

/** 同一日期的两个已保存版本可回看；查看日报不得触发分析或写入。 */
test("日报历史版本独立展示且不发起写请求", async ({ page }) => {
  const archived = (id: string, version: number) => ({
    id,
    report_date: "2026-10-23",
    version,
    created_at: "2026-10-24T01:00:00Z",
    report: {
      trade_date: "2026-10-23",
      etf_count: 3,
      market_cutoff: "2026-10-23",
      valid_prediction_samples: 0,
      cost_usd: null,
      previous_matured_accuracy: null,
      predictions: [],
      news: [],
      accounts: [],
      trades: [],
      anomalies: [],
      conclusion: "夹具历史版本" + version,
    },
  });
  const writes = await mockApi(page, {
    "/api/reports": [archived("r2", 2), archived("r1", 1)],
    "/api/reports/r2": archived("r2", 2),
    "/api/reports/r1": archived("r1", 1),
  });
  await page.goto("/#/reports");
  await expect(page.getByText("夹具历史版本2", { exact: true })).toBeVisible();
  await page.getByRole("combobox", { name: "研究日报历史版本" }).press("Enter");
  await page.getByRole("option", { name: /版本 1/ }).click();
  await expect(page.getByText("夹具历史版本1", { exact: true })).toBeVisible();
  await expect(page.getByText("夹具历史版本2", { exact: true })).toHaveCount(0);
  await expect(page.locator(".page")).not.toContainText("NaN");
  expect(writes).toEqual([]);
});

/** 新增四类切换和第八页导航在手机宽度不撑破页面。 */
test("排行与日报在手机尺寸保持可读", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApi(page, {
    "/api/rankings": { rows: [], curves: [], calibration: [] },
    "/api/models": [],
    "/api/fusion/evaluation": { available_dates: 0, required_dates: 150 },
    "/api/reports": [],
  });
  for (const path of ["/rankings", "/reports"]) {
    await page.goto("/#" + path);
    await expect(page.locator(".page-heading h1")).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(391);
  }
});

/** 快速切换时拒绝迟到的旧响应，防止把历史回测数据显示到前瞻标签下。 */
test("排行榜丢弃迟到的旧类别响应", async ({ page }) => {
  let releaseHistory: () => void = () => {};
  const historyReady = new Promise<void>((resolve) => {
    releaseHistory = resolve;
  });
  await mockApi(page, {
    "/api/models": [],
    "/api/fusion/evaluation": { available_dates: 0, required_dates: 150 },
    "/api/rankings": async (url: URL) => {
      const mode = url.searchParams.get("mode");
      if (mode === "history") await historyReady;
      return {
        rows: [{ strategy_id: "ma", label: "夹具" + mode }],
        curves: [],
        calibration: [],
      };
    },
  });
  await page.goto("/#/rankings");
  await page
    .getByRole("radiogroup", { name: "比较证据类型" })
    .getByText("实时前瞻性验证", { exact: true })
    .click();
  await expect(page.getByText("夹具forward", { exact: true })).toBeVisible();
  const response = page.waitForResponse(
    (item) =>
      item.url().includes("/api/rankings?") &&
      item.url().includes("mode=history"),
  );
  releaseHistory();
  await response;
  await expect(page.getByText("夹具forward", { exact: true })).toBeVisible();
  await expect(page.getByText("夹具history", { exact: true })).toHaveCount(0);
});

/** 五日预测评价与当前一日模拟账户是不同对象，模拟页签必须回到一日信号。 */
test("模拟比较不冒充五日独立账户", async ({ page }) => {
  const horizons: string[] = [];
  await mockApi(page, {
    "/api/models": [],
    "/api/fusion/evaluation": { available_dates: 0, required_dates: 150 },
    "/api/rankings": (url: URL) => {
      if (url.searchParams.get("mode") === "paper")
        horizons.push(url.searchParams.get("horizon") || "");
      return { rows: [], curves: [], calibration: [] };
    },
  });
  await page.goto("/#/rankings");
  await page
    .getByRole("radiogroup", { name: "比较预测跨度" })
    .getByText("5个交易日", { exact: true })
    .click();
  await page
    .getByRole("radiogroup", { name: "比较证据类型" })
    .getByText("实时模拟交易", { exact: true })
    .click();
  await expect(
    page.getByText("模拟账户仅消费1日信号", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("radiogroup", { name: "比较预测跨度" }),
  ).toHaveCount(0);
  await expect.poll(() => horizons).toEqual(["1"]);
});

/** 资金或回撤证据为NULL时，不能制造-100%收益或0%回撤。 */
test("未知账户权益和回撤保持未知", async ({ page }) => {
  await mockApi(page, {
    "/api/dashboard": {
      account: {
        id: "lightgbm",
        equity: null,
        cash: null,
        initial_capital: "100000",
        state: {},
      },
      enabled_strategies: [],
    },
    "/api/paper/accounts": [
      {
        id: "lightgbm",
        equity: null,
        cash: null,
        initial_capital: "100000",
        state: {},
      },
    ],
    "/api/paper/positions": [],
    "/api/paper/orders": [],
    "/api/paper/fills": [],
    "/api/paper/snapshots": [
      { trade_date: "2026-10-23", equity: null, details: { drawdown: null } },
    ],
    "/api/risk": { current: {}, history: [] },
    "/api/risk/events": [],
    "/api/paper/diagnostics": {},
  });
  await page.goto("/#/legacy-overview");
  await expect(page.locator(".stats .stat").nth(2)).toContainText("—");
  await expect(page.locator(".stats .stat").nth(2)).not.toContainText(
    "-100.00%",
  );
  await expect(page.locator(".stats .stat").nth(3)).not.toContainText("0.00%");
  await page.goto("/#/paper");
  await expect(page.locator(".stats .stat").nth(2)).toContainText("—");
  await expect(page.locator(".stats .stat").nth(2)).not.toContainText(
    "-100.00%",
  );
});

/** 跨源抽样按当前ETF计数，并保留范围与历史纠错证据，不能冒充全历史核验。 */
test("行情跨源摘要区分抽样和历史量额修正", async ({ page }) => {
  const evidence = {
    provider: "TENCENT_PUBLIC_NEWFQKLINE",
    checked_at: "2026-10-24T12:54:50Z",
    sample_count: 11,
    start_date: "2026-10-09",
    end_date: "2026-10-23",
    scope: "仅近期11日OHLC/量额交叉采样，非全历史验证",
    price_match: true,
    corrected_dates: [],
  };
  const correctedDates = [
    "2023-12-12",
    "2023-12-14",
    "2023-12-15",
    "2024-11-01",
  ];
  await mockApi(page, {
    "/api/market/instruments": [
      { symbol: "510300", name: "沪深300ETF" },
      { symbol: "159915", name: "创业板ETF" },
    ],
    "/api/market/bars": [],
    "/api/market/indicators": { indicators: [] },
    "/api/market/quality": [],
    "/api/market/quality-report": {
      items: [
        {
          symbol: "510300",
          cross_source_status: "SAMPLED_VERIFIED",
          cross_source_evidence: evidence,
        },
        {
          symbol: "159915",
          cross_source_status: "SAMPLED_VERIFIED_WITH_CORRECTIONS",
          cross_source_evidence: {
            ...evidence,
            corrected_dates: correctedDates,
          },
        },
      ],
    },
  });
  await page.goto("/#/market");
  await expect(
    page.getByText("抽样核验通过（非全历史）", { exact: true }),
  ).toBeVisible();
  const summary = page.getByLabel("跨源抽样证据");
  await expect(summary).toContainText("本ETF 11 个交易日");
  await expect(summary).toContainText(evidence.scope);
  await expect(summary).not.toContainText("已修正量额日期");
  await page.getByRole("combobox", { name: "ETF品种" }).press("Enter");
  await page.getByRole("option", { name: "159915 · 创业板ETF" }).click();
  await expect(
    page.getByText("抽样核验通过，已修正异常量额", { exact: true }),
  ).toBeVisible();
  await expect(summary).toContainText("本ETF 11 个交易日");
  await expect(summary).not.toContainText("33 个交易日");
  for (const date of correctedDates) await expect(summary).toContainText(date);
  await page.locator(".el-table__body tr").first().click();
  await expect(page.locator(".el-drawer")).toContainText(
    "cross_source_evidence",
  );
  await expect(page.locator(".el-drawer")).toContainText("2024-11-01");
});

/** 工件统计来自全版本列表，不能误标为上方某次冻结实验的模型成绩。 */
test("模型工件列表明示全版本并展示独立身份", async ({ page }) => {
  await mockApi(page, {
    "/api/models": [
      {
        id: "fixture-model-v1",
        symbol: "510300",
        algorithm: "lightgbm",
        horizon: 1,
        nature: "REAL",
        trained_at: "2026-10-23T02:00:00Z",
        feature_version: "ohlcv-v1",
        metrics: {},
      },
      {
        id: "fixture-model-v11",
        symbol: "510300",
        algorithm: "lightgbm",
        horizon: 1,
        nature: "REAL",
        trained_at: "2026-10-24T03:00:00Z",
        feature_version: "ohlcv-v1.1",
        metrics: {},
      },
    ],
    "/api/fusion/evaluation": { status: "INSUFFICIENT_DATA" },
    "/api/rankings": { mode: "oos", rows: [], curves: [], calibration: [] },
  });
  await page.goto("/#/rankings");
  await page
    .getByRole("radiogroup", { name: "比较证据类型" })
    .getByText("严格样本外回测", { exact: true })
    .click();
  const heading = page.getByRole("heading", {
    name: "模型工件独立测试（全版本，不对应上方冻结实验）",
  });
  await expect(heading).toBeVisible();
  const panel = page.locator("section").filter({ has: heading });
  await expect(panel).toContainText("fixture-model-v1");
  await expect(panel).toContainText("fixture-model-v11");
  await expect(panel).toContainText("2026/10/24");
  await expect(panel).toContainText("ohlcv-v1.1");
  await expect(panel).toContainText("不代表上方冻结实验实际使用的模型");
});

/** 日报可包含两个预测跨度，汇总与账户日期必须标明各自口径。 */
test("日报展示预测跨度与一日样本及账户结算日期", async ({ page }) => {
  const archived = {
    id: "fixture-report-horizons",
    report_date: "2026-10-23",
    version: 1,
    report: {
      valid_prediction_samples: 12,
      previous_matured_accuracy: { samples: 2, accuracy: 0.5 },
      predictions: [
        {
          symbol: "510300",
          strategy_id: "lightgbm",
          horizon: 1,
          target_date: "2026-10-26",
        },
        {
          symbol: "510300",
          strategy_id: "lightgbm",
          horizon: 5,
          target_date: "2026-10-30",
        },
      ],
      accounts: [
        {
          strategy_id: "lightgbm",
          equity: "100000",
          last_settlement_date: "2026-10-22",
        },
      ],
    },
  };
  await mockApi(page, {
    "/api/reports": [archived],
    "/api/reports/fixture-report-horizons": archived,
  });
  await page.goto("/#/reports");
  await expect(
    page.getByText("累计有效到期预测样本（1日）", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("昨日到期预测方向准确率（1日）", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("1 个交易日", { exact: true })).toBeVisible();
  await expect(page.getByText("5 个交易日", { exact: true })).toBeVisible();
  await expect(page.getByText("最近结算日", { exact: true })).toBeVisible();
  await expect(page.getByText("2026-10-22", { exact: true })).toBeVisible();
});
