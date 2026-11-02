import { expect, test, type Page } from "@playwright/test";

/** 全部数字仅为隔离界面夹具，不进入真实行情、模型或生产数据库。 */
function fixture(): Record<string, any> {
  return {
    benchmark: {
      series_id: "SGE_AU9999",
      name: "上海黄金交易所 Au99.99",
      price_field: "日收盘价",
      unit: "CNY/g",
    },
    status: "PARTIAL",
    as_of: "2026-10-23",
    updated_at: "2026-10-23T09:00:00Z",
    history: [
      { date: "2026-10-22", price: "990.00", source: "ISOLATED_FIXTURE" },
      { date: "2026-10-23", price: "1000.00", source: "ISOLATED_FIXTURE" },
    ],
    predictions: [
      {
        id: "isolated-gold-63",
        horizon: 63,
        reference_date: "2026-10-23",
        reference_price: "1000.00",
        data_cutoff: "2026-10-23T08:00:00Z",
        generated_at: "2026-10-23T09:00:00Z",
        target_date: "2027-01-22",
        target_date_status: "ESTIMATED",
        median_return: 0.04,
        probability_up: 0.61,
        interval_lower: "920.00",
        interval_upper: "1160.00",
        nominal_coverage: 0.8,
        model_id: "isolated-model-63",
        label_version: "GOLD_CLOSE_RETURN_V1",
        actual: null,
      },
      {
        id: "isolated-gold-126",
        horizon: 126,
        reference_date: "2026-10-23",
        reference_price: "1000.00",
        data_cutoff: "2026-10-23T08:00:00Z",
        generated_at: "2026-10-23T09:00:00Z",
        target_date: null,
        target_date_status: "ESTIMATED",
        median_return: -0.02,
        probability_up: null,
        interval_lower: null,
        interval_upper: null,
        nominal_coverage: null,
        model_id: "isolated-model-126",
        label_version: "GOLD_CLOSE_RETURN_V1",
        actual: null,
      },
    ],
    models: [
      {
        model_id: "isolated-model-63",
        horizon: 63,
        metadata: { feature_version: "isolated" },
        metrics: {
          advantage_status: "EXPERIMENTAL_NO_STABLE_ADVANTAGE",
          pit_status: "CURRENT_VINTAGE_REPLAY_NOT_STRICT_PIT",
          nominal_coverage: 0.8,
          coverage: {
            start: "2013-01-01",
            end: "2026-10-23",
            years: 13.8,
            observations: 3000,
            matured_samples: 2937,
            approx_independent_windows: 46,
          },
          final_test: {
            train_samples: 2000,
            calibration_samples: 300,
            test_samples: 250,
            test_start: "2025-01-01",
            test_end: "2025-12-31",
            candidate: {
              brier: 0.31,
              accuracy: 0.49,
              interval_coverage: 0.62,
              interval_width_return: 0.24,
            },
            baseline: { brier: 0.24, accuracy: 0.56 },
            nonoverlap: {
              samples: 4,
              candidate_brier: 0.35,
              baseline_brier: 0.25,
            },
          },
          rolling: [],
          warnings: ["夹具成绩较差；不能改变测试段来选择结果。"],
        },
      },
    ],
    sources: [
      {
        series_id: "SGE_AU9999",
        name: "国内日收盘",
        status: "STALE",
        last_date: "2026-10-23",
        source_url: "https://www.sge.com.cn/",
        reason: "保留上次有效观察日",
      },
      {
        series_id: "USD_CNY",
        name: "人民币汇率",
        status: "EMPTY",
        source_url: "javascript:alert(1)",
        reason: "未返回有效观测",
      },
      {
        series_id: "REAL_YIELD",
        name: "实际利率",
        status: "FAILED",
        reason: "来源访问失败",
      },
      {
        series_id: "USD_GOLD",
        name: "国际金价",
        status: "PARTIAL",
        reason: "仅部分历史覆盖",
      },
    ],
    coverage: [
      {
        series_id: "SGE_AU9999",
        count: 2,
        first_date: "2026-10-22",
        last_date: "2026-10-23",
      },
    ],
    scenarios: [
      {
        name: "偏强条件",
        assumptions: ["实际利率下行", "需求增强"],
        mechanism: "持有黄金的机会成本可能降低",
        invalidation: "实际利率反向上行",
      },
    ],
    news: {
      status: "INSUFFICIENT_DATA",
      reason: "解释暂不可用：没有合格的黄金证据。",
    },
    health: {
      enabled: true,
      scheduling_enabled: true,
      schedule: "每日增量；每周预测；每月重训",
      tasks: [],
    },
  };
}

test("黄金旧页同期限分别保留两种量化结果和冻结模型，兼容旧算法名称与缺字段", async ({
  page,
}) => {
  const payload = fixture();
  // 旧黄金工件仅在元数据中保存带 small_fixed 的名称；历史预测本身可无算法字段。
  payload.models[0].metadata.algorithm = "LightGBM_small_fixed";
  payload.predictions.push({
    ...payload.predictions[0],
    id: "isolated-xgb-63",
    algorithm: "xgboost",
    model_id: "isolated-xgb-model-63",
    generated_at: "2026-10-24T09:00:00Z",
    median_return: 0.08,
    probability_up: null,
  });
  payload.models.push({
    ...structuredClone(payload.models[0]),
    model_id: "isolated-xgb-model-63",
    metadata: { algorithm: "XGBoost_small_fixed" },
  });
  const writes = await mockGold(page, payload);
  await page.goto("/#/gold");
  const lgb = page.getByTestId("gold-prediction-63");
  const xgb = page.getByTestId("gold-prediction-xgboost-63");
  await expect(lgb).toContainText("LightGBM");
  await expect(lgb).toContainText("4.00%");
  await expect(xgb).toContainText("XGBoost");
  await expect(xgb).toContainText("8.00%");
  await expect(page.getByTestId("gold-prediction-xgboost-126")).toContainText(
    "尚无有效冻结预测",
  );
  await xgb
    .getByRole("button", { name: "查看本条冻结证据", exact: true })
    .click();
  await expect(page.locator(".el-drawer")).toContainText("XGBoost");
  await expect(page.locator(".el-drawer")).toContainText(
    "isolated-xgb-model-63",
  );
  await page.keyboard.press("Escape");
  await expect(page.locator(".el-drawer")).not.toBeVisible();
  await page.getByRole("tab", { name: "历史验证", exact: true }).click();
  await expect(
    page.getByTestId("gold-model").filter({ hasText: "XGBoost" }),
  ).toHaveCount(1);
  await expect(
    page.getByTestId("gold-model").filter({ hasText: "LightGBM" }),
  ).toHaveCount(1);
  await expect(page.getByTestId("gold-model").getByRole("heading")).toHaveText([
    "约3个月（63交易日） · LightGBM · 历史样本外验证",
    "约3个月（63交易日） · XGBoost · 历史样本外验证",
  ]);
  expect(writes).toEqual([]);
});

/** 浏览器只接触隔离接口，并记录写入以验证刷新不会触发副作用。 */
async function mockGold(
  page: Page,
  payload: Record<string, any>,
  costResponse: Record<string, any> = {},
) {
  const writes: { path: string; body: Record<string, any> }[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== "GET")
      writes.push({ path, body: request.postDataJSON() });
    if (path === "/api/auth/me")
      await route.fulfill({ json: { username: "fixture-admin" } });
    else if (path === "/api/auth/csrf")
      await route.fulfill({
        json: { header: "X-CSRF-TOKEN", token: "isolated-fixture-only" },
      });
    else if (path === "/api/gold/overview")
      await route.fulfill({ json: payload });
    else if (path.startsWith("/api/gold/predictions/"))
      await route.fulfill({
        json: {
          ...payload.predictions.find((row: Record<string, any>) =>
            path.endsWith(row.id),
          ),
          input_snapshot: { frozen_source: "isolated-snapshot-only" },
        },
      });
    else if (path === "/api/gold/cost")
      await route.fulfill({ json: costResponse });
    else if (path === "/api/gold/import")
      await route.fulfill({
        json: { imported: 1, duplicates: 0, status: "CSV_UNVERIFIED" },
      });
    else
      await route.fulfill({ status: 404, json: { message: "未定义测试接口" } });
  });
  return writes;
}

test("空数据保持空图空预测且首次浏览及刷新不写入", async ({ page }) => {
  const writes = await mockGold(page, {
    status: "EMPTY",
    history: [],
    predictions: [],
    models: [],
    sources: [],
  });
  await page.goto("/#/gold");
  await expect(
    page.getByRole("link", { name: "预测工作台", exact: true }),
  ).toHaveAttribute("href", /asset=Au99.99/);
  await expect(
    page.getByRole("navigation", { name: "历史研究工具" }),
  ).toHaveCount(0);
  await expect(page.getByTestId("gold-history-empty")).toContainText(
    "不使用演示行情",
  );
  await expect(page.getByTestId("gold-prediction-empty")).toHaveCount(4);
  await expect(page.getByRole("img", { name: "研究数据图表" })).toHaveCount(0);
  await page.getByRole("button", { name: "刷新已保存结果" }).click();
  await expect(page.getByTestId("gold-data-asof")).toContainText(
    "尚无有效数据",
  );
  await expect(page.locator(".gold-page")).not.toContainText("50.00%");
  expect(writes).toEqual([]);
});

test("63和126日独立展示缺失概率不补0.5且预计日历与前瞻待验证明确", async ({
  page,
}) => {
  await mockGold(page, fixture());
  await page.goto("/#/gold");
  const three = page.getByTestId("gold-prediction-63");
  const six = page.getByTestId("gold-prediction-126");
  await expect(three).toContainText("约3个月（63交易日）");
  await expect(three).toContainText("61.00%");
  await expect(three).toContainText("预计日期 · 日历未确认");
  await expect(three).toContainText("待验证");
  await expect(six).toContainText("中位判断偏弱");
  await expect(six).toContainText("未提供 · 尚无合格概率");
  await expect(six).not.toContainText("50.00%");
  await expect(six).toContainText("未来交易日历未确认");
  await expect(page.getByRole("img", { name: "研究数据图表" })).toHaveCount(1);
  await three.getByRole("button", { name: "查看本条冻结证据" }).click();
  await expect(page.locator(".el-drawer")).toContainText("isolated-model-63");
  await expect(page.locator(".el-drawer")).toContainText(
    "GOLD_CLOSE_RETURN_V1",
  );
  await page.getByText("完整版本、输入快照与追加评估", { exact: true }).click();
  await expect(page.locator(".el-drawer")).toContainText(
    "isolated-snapshot-only",
  );
});

test("收益与概率方向冲突时显式提示而缺失概率不推定冲突", async ({ page }) => {
  const payload = fixture();
  payload.predictions[0].median_return = -0.01;
  await mockGold(page, payload);
  await page.goto("/#/gold");
  await expect(page.getByTestId("gold-estimate-conflict")).toHaveCount(1);
  await expect(page.getByTestId("gold-prediction-63")).toContainText(
    "当前方向判断不一致",
  );
  await expect(page.getByTestId("gold-prediction-126")).not.toContainText(
    "当前方向判断不一致",
  );
});

test("刷新失败保留原始行情日期和生成时间不伪装今日更新", async ({ page }) => {
  const writes = await mockGold(page, fixture());
  await page.goto("/#/gold");
  await expect(page.getByTestId("gold-data-asof")).toContainText("2026-10-23");
  const updated = await page.getByTestId("gold-updated-at").textContent();
  await page.route("**/api/gold/overview", (route) =>
    route.fulfill({ status: 503, json: { message: "来源暂时不可用" } }),
  );
  await page.getByRole("button", { name: "刷新已保存结果" }).click();
  await expect(page.getByTestId("gold-error")).toContainText("来源暂时不可用");
  await expect(page.getByTestId("gold-data-asof")).toContainText("2026-10-23");
  await expect(page.getByTestId("gold-updated-at")).toHaveText(updated || "");
  expect(writes).toEqual([]);
});

test("样本外差结果和独立窗口保留并与基线比较", async ({ page }) => {
  await mockGold(page, fixture());
  await page.goto("/#/gold");
  await page.getByRole("tab", { name: "历史验证", exact: true }).click();
  const model = page.getByTestId("gold-model");
  await expect(model).toContainText("尚无稳定优势");
  await expect(model).toContainText("0.3100 / 0.2400");
  await expect(model).toContainText("49.00% / 56.00%");
  await expect(model).toContainText("80.00% / 62.00%");
  await expect(model).toContainText("4 / 0.3500 与 0.2500");
  await expect(model).toContainText("非严格点时验证");
  await expect(page.locator(".gold-page")).not.toContainText("NaN");
});

test("来源空数据失败过期部分覆盖分别显示且拒绝危险链接", async ({ page }) => {
  const writes = await mockGold(page, fixture());
  await page.goto("/#/gold");
  await page.getByRole("tab", { name: "数据来源与健康", exact: true }).click();
  const sources = page.getByTestId("gold-source");
  await expect(sources.nth(0)).toContainText("数据过期");
  await expect(sources.nth(0)).toContainText("2026-10-23");
  await expect(sources.nth(1)).toContainText("空数据");
  await expect(sources.nth(2)).toContainText("失败");
  await expect(sources.nth(3)).toContainText("部分覆盖");
  await expect(
    sources.nth(0).getByRole("link", { name: "查看原始来源" }),
  ).toHaveAttribute("href", "https://www.sge.com.cn/");
  await expect(sources.nth(1).getByRole("link")).toHaveCount(0);
  expect(writes).toEqual([]);
});

test("成本以十进制字符串提交后端且结果与输入修改不混淆", async ({ page }) => {
  const writes = await mockGold(page, fixture(), {
    actual_cost_per_gram: "1020.00",
    total_purchase_cost: "102000.00",
    benchmark_equivalent_grams: "100",
    repurchase_net: "99000.00",
    net_profit: "-3000.00",
    net_return: "-0.0294117647",
    break_even_benchmark_price: "1030.00",
    quote_status: "ASSUMPTION",
    assumptions: ["回购价差不变假设"],
    forecast_ranges: [],
    profit_probability: null,
  });
  await page.goto("/#/gold");
  await page.getByRole("tab", { name: "实物成本", exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "克数", exact: true }),
  ).toHaveValue("");
  await page.getByRole("textbox", { name: "克数", exact: true }).fill("100");
  await page
    .getByRole("textbox", { name: "实际买入价", exact: true })
    .fill("1020");
  await page
    .getByRole("textbox", { name: "回购每克扣减", exact: true })
    .fill("10");
  await page.getByRole("button", { name: "计算成本与保本价" }).click();
  await expect(page.getByTestId("gold-cost-result")).toContainText("1,030.00");
  expect(writes).toHaveLength(1);
  expect(writes[0]).toMatchObject({
    path: "/api/gold/cost",
    body: {
      quantity: "100",
      unit_purchase_price: "1020",
      benchmark_price: "1000.00",
      repurchase_deduction_per_gram: "10",
      quote_kind: "USER_ASSUMPTION",
    },
  });
  expect(writes[0].body).not.toHaveProperty("total_purchase_cost");
  await expect(page.getByTestId("gold-cost-result")).not.toContainText(
    "50.00%",
  );
  await page.getByRole("textbox", { name: "克数", exact: true }).fill("200");
  await expect(page.getByTestId("gold-cost-result")).toHaveCount(0);
});

test("成本校验错误不展示结果且旧报价显式过期", async ({ page }) => {
  await mockGold(page, fixture(), {
    quote_status: "STALE",
    quote_age_hours: 80,
    assumptions: [],
    forecast_ranges: [],
  });
  await page.goto("/#/gold");
  await page.getByRole("tab", { name: "实物成本", exact: true }).click();
  await page.getByRole("textbox", { name: "克数", exact: true }).fill("10");
  await page
    .getByRole("textbox", { name: "实际买入价", exact: true })
    .fill("1020");
  await page.getByRole("button", { name: "计算成本与保本价" }).click();
  await expect(page.getByTestId("gold-cost-result")).toContainText(
    "报价已过期",
  );
  await page.route("**/api/gold/cost", (route) =>
    route.fulfill({ status: 400, json: { message: "克数必须大于0" } }),
  );
  await page.getByRole("textbox", { name: "克数", exact: true }).fill("0");
  await page.getByRole("button", { name: "计算成本与保本价" }).click();
  await expect(page.getByTestId("gold-cost-error")).toContainText(
    "克数必须大于0",
  );
  await expect(page.getByTestId("gold-cost-result")).toHaveCount(0);
});

test("12个月定性条件与新闻信息不足不制造概率或目标价", async ({ page }) => {
  const writes = await mockGold(page, fixture());
  await page.goto("/#/gold");
  await page
    .getByRole("tab", { name: "宏观与12个月情景", exact: true })
    .click();
  await expect(page.getByTestId("gold-news-state")).toContainText(
    "没有合格的黄金证据",
  );
  await expect(page.getByText("定性条件情景", { exact: true })).toBeVisible();
  await expect(
    page.getByText("实际利率反向上行", { exact: true }),
  ).toBeVisible();
  expect(writes).toEqual([]);
});

test("首页明示差区间覆盖和最终概率不可验证，不把空Brier补成零", async ({
  page,
}) => {
  const payload = fixture();
  payload.models[0].metrics.final_test.candidate.brier = null;
  payload.models[0].metrics.final_test.candidate.interval_coverage = 0.454;
  await mockGold(page, payload);
  await page.goto("/#/gold");
  const warning = page
    .getByTestId("gold-prediction-63")
    .getByTestId("gold-interval-validation");
  await expect(warning).toContainText("45.40%");
  await expect(warning).toContainText("低于名义覆盖");
  await expect(warning).toContainText("最终测试未取得合格概率验证");
  await page.getByRole("tab", { name: "历史验证", exact: true }).click();
  await expect(page.getByTestId("gold-model")).toContainText("— / 0.2400");
});

test("有效解释以证据ID映射冻结原文并分开机制假设与事实", async ({ page }) => {
  const payload = fixture();
  payload.news = {
    status: "SUCCEEDED",
    display_status: "SUCCEEDED",
    quote: {
      domain: "GOLD_LONG_TERM",
      schema_version: "gold-evidence-v1",
      evidence_snapshot_id: "isolated-snapshot",
      evidence_catalog: [
        {
          id: "EV_isolated",
          snapshot_id: "isolated-snapshot",
          text: "这是隔离夹具中的官方原文节选。",
          content_type: "EXCERPT",
          source_url: "https://www.sge.com.cn/",
          published_at: "2026-10-22T01:00:00Z",
          fetched_at: "2026-10-22T02:00:00Z",
        },
      ],
    },
    result: {
      status: "SUCCEEDED",
      nature: "REAL",
      evidence_snapshot_id: "isolated-snapshot",
      generated_at: "2026-10-23T09:00:00Z",
      valid_until: "2026-10-30T09:00:00Z",
      result: {
        status: "AVAILABLE",
        summary: "隔离界面夹具，不代表实际黄金观点。",
        mechanisms: [
          {
            evidence_ids: ["EV_isolated", "EV_missing"],
            kind: "CAUSAL_HYPOTHESIS",
            mechanism: "黄金持有成本可能变化",
            counter_factor: "需求减弱可能抵消",
            invalidation: "利率方向反转",
            uncertainty: "无法识别是否已经计价",
          },
        ],
      },
    },
  };
  await mockGold(page, payload);
  await page.goto("/#/gold");
  await page
    .getByRole("tab", { name: "宏观与12个月情景", exact: true })
    .click();
  const mechanism = page.getByTestId("gold-news-mechanism");
  await expect(mechanism).toContainText("因果机制假设（待验证）");
  await expect(mechanism).toContainText("这是隔离夹具中的官方原文节选。");
  await expect(mechanism).toContainText("证据无法在当前冻结目录回查");
  await expect(mechanism).toContainText("无法识别是否已经计价");
});

test("失败解释即使携带原始模型文本也不冒充已核实事实", async ({ page }) => {
  const payload = fixture();
  payload.news = {
    status: "FAILED",
    error_code: "API_FAILED",
    result: {
      status: "FAILED",
      nature: "REAL",
      raw_output: "绝不能展示的未校验原始文本",
      result: {
        summary: "非法成功摘要",
        mechanisms: [{ mechanism: "非法机制" }],
      },
    },
  };
  await mockGold(page, payload);
  await page.goto("/#/gold");
  await page
    .getByRole("tab", { name: "宏观与12个月情景", exact: true })
    .click();
  await expect(page.getByTestId("gold-news-mechanism")).toHaveCount(0);
  await expect(page.locator(".gold-page")).not.toContainText("非法成功摘要");
  await expect(page.locator(".gold-page")).not.toContainText("未校验原始文本");
  await expect(page.getByText("当前状态原因：解释接口调用失败")).toBeVisible();
});

test("CSV只有显式导入才写入且不触发任务", async ({ page }) => {
  const writes = await mockGold(page, fixture());
  await page.goto("/#/gold");
  await page.getByRole("tab", { name: "数据来源与健康", exact: true }).click();
  await page.getByLabel("选择黄金CSV").setInputFiles({
    name: "isolated-empty.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("series_id,observation_date,value\n"),
  });
  expect(writes).toEqual([]);
  await page.getByRole("button", { name: "校验并导入所选CSV" }).click();
  await expect(
    page.getByText("导入 1 条，已有记录 0 条", { exact: false }),
  ).toBeVisible();
  expect(writes.map((row) => row.path)).toEqual(["/api/gold/import"]);
});

test("手机布局可读且黄金首页及成本表不横向溢出", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockGold(page, fixture());
  await page.goto("/#/gold");
  await expect(page.getByTestId("gold-prediction-63")).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    ),
  ).toBe(true);
  await page.getByRole("tab", { name: "实物成本", exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "克数", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    ),
  ).toBe(true);
});
