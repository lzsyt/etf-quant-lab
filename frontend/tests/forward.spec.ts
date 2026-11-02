import { test, expect, type Page } from "@playwright/test";

/** 前瞻页面只使用显式隔离接口夹具；不发送生产写入或模型调用。 */
async function mockForward(page: Page, payload: Record<string, unknown>) {
  const writes: string[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== "GET") writes.push(path);
    if (path === "/api/auth/me") {
      await route.fulfill({ json: { username: "fixture-admin" } });
    } else if (path === "/api/forward") {
      await route.fulfill({ json: payload });
    } else {
      await route.fulfill({ status: 404, json: { message: "未定义测试接口" } });
    }
  });
  return writes;
}

/** 四账户的真实空样式夹具，零成交不能伪装收益证据。 */
function fixture() {
  return {
    enabled: true,
    experiment: {
      id: "isolated-fixture-batch",
      strategy_version: "forward-v2.0.0",
      protocol_version: "OPEN_NET_REBALANCE_V1",
      label_version: "OPEN_TO_OPEN_H1_V1",
      start_date: "2026-10-26",
      created_at: "2026-10-24T12:00:00Z",
    },
    next_schedule: "2026-10-26T09:30:00Z",
    accounts: [
      "LGBM_BASE",
      "XGB_BASE",
      "LGBM_MATCHED_CONTROL",
      "FUSION_EXPERIMENT",
    ].map((strategy) => ({
      id: "fixture-" + strategy,
      strategy,
      initial_capital: "100000.00",
      curve: [],
      metrics: {
        total_return: 0,
        max_drawdown: 0,
        fees: "0",
        commissions: "0",
        slippage_cost: "0",
        transaction_cost: "0",
        turnover: 0,
        average_position: 0,
        fill_count: 0,
        effective_days: 0,
        evaluation_status: "INSUFFICIENT_SAMPLES",
      },
    })),
    decisions: [] as Record<string, any>[],
    news: [] as Record<string, any>[],
    health: [] as Record<string, any>[],
    models: [],
    paired: {
      independent_dates: 0,
      valid_news_dates: 0,
      signal_differences: [] as Record<string, any>[],
      buy_decision_changes: 0,
      reduction_decision_changes: 0,
      risk_vetoes: 0,
      fallback_days: 0,
      fallback_reasons: {},
    },
  };
}

/** 冻结时刻、标签版本和有量纲边界均来自接口，不在浏览器重造信号。 */
function decision(target: number | null = 0) {
  return {
    id: "fixture-decision-1",
    account_id: "fixture-FUSION_EXPERIMENT",
    symbol: "510300",
    signal_date: "2026-10-23",
    entry_date: "2026-10-26",
    evaluation_date: "2026-10-27",
    generated_at: "2026-10-24T12:00:00Z",
    details: {
      strategy: "FUSION_EXPERIMENT",
      strategy_version: "forward-v2.0.0",
      label_version: "OPEN_TO_OPEN_H1_V1",
      protocol_version: "OPEN_NET_REBALANCE_V1",
      ml: {
        model_id: "fixture-new-label-model",
        raw_probability: 0.6,
        probability: 0.58,
      },
      news: { status: "AVAILABLE", sentiment_score: 0, risk_level: "high" },
      effective_mode: "FUSION",
      score: 0.112,
      formula: "0.7 × (2 × P - 1) + 0.3 × S",
      target_weight: target,
      current_weight: 0,
      reason_code: "NEWS_RISK_VETO",
      reason: "新闻高风险否决，等待合法时点减仓",
      analysis_id: "fixture-analysis",
      trace_id: "fixture-trace-1",
    },
    plan: { intents: [], diagnostics: [] },
    actual: null,
    execution: null,
  };
}

test("没有新批次显示真实空态且不写入", async ({ page }) => {
  const writes = await mockForward(page, { enabled: false, experiment: null });
  await page.goto("/#/forward");
  await expect(page.getByTestId("forward-empty")).toContainText("尚无新批次");
  await expect(page.getByRole("img", { name: "研究数据图表" })).toHaveCount(0);
  expect(writes).toEqual([]);
});

test("四策略零成交和样本不足不填收益曲线", async ({ page }) => {
  const writes = await mockForward(page, fixture());
  await page.goto("/#/forward");
  await page.getByRole("tab", { name: "策略对比", exact: true }).click();
  await expect(page.getByTestId("forward-curve-empty")).toBeVisible();
  await expect(page.getByTestId("forward-sample-warning")).toContainText(
    "尚不足以评价",
  );
  await expect(page.getByRole("img", { name: "研究数据图表" })).toHaveCount(0);
  await expect(
    page.getByText("C · 门槛匹配对照", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("D · 新闻融合实验", { exact: true }),
  ).toBeVisible();
  await expect(page.locator(".page")).not.toContainText("年化");
  await expect(page.locator(".page")).not.toContainText("NaN");
  expect(writes).toEqual([]);
});

test("有效零新闻与high风险保留且融合分数不是概率", async ({ page }) => {
  const payload = fixture();
  payload.decisions = [decision()];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  const row = page
    .getByTestId("forward-decisions")
    .locator(".el-table__body tr")
    .first();
  await expect(row).toContainText("新闻有效");
  await expect(row).toContainText("S 0.000000");
  await expect(row).toContainText("风险 高");
  await expect(row).toContainText("分数 0.112000");
  await expect(row).not.toContainText("11.20%");
  await expect(row).not.toContainText("ML_FALLBACK");
  await row.click();
  const detail = page.locator(".el-drawer");
  await expect(detail).toContainText("2026/10/24");
  await expect(detail).toContainText("2026-10-27");
  await expect(detail).toContainText("fixture-new-label-model");
  await expect(detail).toContainText("OPEN_TO_OPEN_H1_V1");
  await expect(detail).toContainText("新闻高风险否决");
  await expect(detail).toContainText("不是实时成交");
});

test("模型缺失目标与明确清仓零目标分别显示", async ({ page }) => {
  const payload = fixture();
  const missing = decision(null);
  missing.id = "fixture-missing";
  missing.symbol = "510500";
  missing.details.reason = "模型缺失或过期，保持既有持仓";
  missing.details.reason_code = "MODEL_MISSING_OR_STALE";
  payload.decisions = [decision(0), missing];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  const rows = page
    .getByTestId("forward-decisions")
    .locator(".el-table__body tr");
  await expect(rows.nth(0)).toContainText("0.00%");
  await expect(rows.nth(0)).not.toContainText("无有效目标");
  await expect(rows.nth(1)).toContainText("无有效目标 · 保持持仓");
  await expect(rows.nth(1)).toContainText("模型缺失或过期");
});

test("API失败降级和实际摘要输入可追踪", async ({ page }) => {
  const payload = fixture();
  const row = decision();
  row.details.effective_mode = "ML_FALLBACK";
  row.details.reason = "机器学习未达阈值";
  row.details["fallback_reason"] = "HTTP_503";
  row.details.news = {
    status: "FAILED",
    sentiment_score: null,
    risk_level: null,
  } as any;
  payload.decisions = [row];
  payload.news = [
    {
      id: "fixture-analysis",
      status: "FAILED",
      error_code: "HTTP_503",
      quote: {
        eligible_source_ids: ["fixture-news-1"],
        input_content_type: "SUMMARY",
        relevance: {
          candidate_count: 6,
          accepted_count: 1,
          excluded_count: 5,
          rejected: [{ id: "fixture-noise", reason: "NO_RELEVANT_NEWS" }],
        },
        input_text: "不得显示的内部原始提示词",
      },
      input_snapshot: {
        news: [
          {
            id: "fixture-news-1",
            title: "隔离测试政策标题",
            summary: "隔离测试政策摘要",
            content: "不得显示的存档全文",
            content_type: "SUMMARY",
            source_name: "FIXTURE",
            published_at: "2026-10-24T01:00:00Z",
          },
        ],
      },
    },
  ];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await expect(page.getByTestId("forward-decisions")).toContainText(
    "ML_FALLBACK",
  );
  await expect(page.getByTestId("forward-decisions")).toContainText(
    "API 失败（HTTP_503）",
  );
  const news = page.getByLabel("新闻覆盖与真实输入");
  await expect(news).toContainText("候选 6 条");
  await expect(news).toContainText("选入 1 条");
  await expect(news).toContainText("通过校验的引用 0 条");
  await news
    .getByRole("button", { name: "查看排除原因与选入新闻摘要" })
    .click();
  await expect(news).toContainText("隔离测试政策摘要");
  await expect(news).toContainText("fixture-noise");
  await expect(news).not.toContainText("不得显示的内部原始提示词");
  await expect(news).not.toContainText("不得显示的存档全文");
});

test("新闻服务失败不冒充整个系统停止", async ({ page }) => {
  const payload = fixture();
  payload.health = [
    {
      stage: "MARKET",
      status: "SUCCEEDED",
      last_success_at: "2026-10-24T12:00:00Z",
    },
    {
      stage: "NEWS",
      status: "FAILED",
      reason: "HTTP_503",
      next_scheduled_at: "2026-10-26T09:30:00Z",
    },
    {
      stage: "PREDICTION",
      status: "SUCCEEDED",
      last_success_at: "2026-10-24T12:01:00Z",
    },
  ];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await page.getByRole("tab", { name: "运行健康", exact: true }).click();
  const rows = page
    .getByRole("tabpanel", { name: "运行健康" })
    .locator(".el-table__body tr");
  await expect(rows.nth(0)).toContainText("行情");
  await expect(rows.nth(0)).toContainText("成功");
  await expect(rows.nth(1)).toContainText("新闻分析");
  await expect(rows.nth(1)).toContainText("API 失败");
  await expect(rows.nth(2)).toContainText("冻结预测");
  await expect(rows.nth(2)).toContainText("成功");
});

test("前瞻三分区在手机宽度不撑破页面", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const payload = fixture();
  payload.decisions = [decision(null)];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  for (const name of ["今日决策", "策略对比", "运行健康"]) {
    await page.getByRole("tab", { name, exact: true }).click();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(391);
  }
});

test("拒单与计划市场时刻不能冒充已成交时间", async ({ page }) => {
  const payload = fixture();
  const row: Record<string, any> = decision();
  row.plan = { market_executed_at: "2026-10-26T01:30:00Z", diagnostics: [] };
  row.execution = {
    diagnostics: [
      { symbol: "510300", status: "REJECTED", reason: "PRICE_LIMIT_NO_FILL" },
    ],
    orders: [
      {
        symbol: "510300",
        status: "REJECTED",
        market_executed_at: "2026-10-26T01:30:00Z",
      },
    ],
    fills: [],
    snapshot: { booked_at: "2026-10-26T10:00:00Z" },
  };
  payload.decisions = [row];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await page
    .getByTestId("forward-decisions")
    .locator(".el-table__body tr")
    .first()
    .click();
  await expect(page.locator(".el-drawer")).toContainText(
    "触及涨跌停，无法撮合",
  );
  const times = page.getByTestId("forward-execution-times");
  await expect(times).toContainText("市场成交时刻：—");
  await expect(times).toContainText("2026/10/26 18:00:00");
  await expect(times).not.toContainText("9:30");
});

test("配对信号按独立日期展示且来源陈旧可追踪", async ({ page }) => {
  const payload = fixture();
  payload.paired.independent_dates = 1;
  payload.paired.valid_news_dates = 1;
  payload.paired.signal_differences = ["510300", "510500", "159915"].map(
    (symbol) => ({
      date: "2026-10-23",
      symbol,
      control: { score: 0.112, eligible: true, target_weight: 0.2 },
      fusion: { score: 0.112, eligible: true, target_weight: 0.2 },
    }),
  );
  payload.health = [
    {
      stage: "NEWS",
      details: {
        collection: {
          sources: [
            {
              source: "SINA_FINANCE",
              status: "STALE",
              error_code: "STALE_SOURCE_FEED",
              candidate_count: 0,
              count: 0,
              source_updated_at: "2018-09-23T10:55:05Z",
            },
          ],
        },
      },
    },
  ];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await page.getByRole("button", { name: "查看采集来源状态" }).click();
  await expect(page.getByLabel("新闻覆盖与真实输入")).toContainText(
    "新闻源长期未更新",
  );
  await page.getByRole("tab", { name: "策略对比", exact: true }).click();
  const paired = page.getByLabel("C D 配对增量证据");
  await expect(paired.locator(".info-row").nth(0)).toContainText("1");
  await expect(paired.locator(".info-row").nth(2)).toContainText("3");
  await expect(paired.locator(".el-table__body tr")).toHaveCount(3);
  await expect(paired).not.toContainText("[object Object]");
});

test("总交易成本展示佣金与滑点价差且不重复叠加", async ({ page }) => {
  const payload = fixture();
  Object.assign(payload.accounts[0].metrics, {
    fees: "5",
    commissions: "5",
    slippage_cost: "1.2",
    transaction_cost: "6.2",
    spread_cost: "0.2",
  });
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await page.getByRole("tab", { name: "策略对比", exact: true }).click();
  const panel = page.getByRole("tabpanel", { name: "策略对比" });
  const row = panel.locator(".el-table__body tr").first();
  await expect(panel).toContainText("总交易成本");
  await expect(row).toContainText("6.20");
  await expect(row).toContainText("5.00");
  await expect(row).toContainText("1.20");
  await expect(row).not.toContainText("6.40");
  await expect(panel).toContainText("价差不再重复叠加");
});

test("最新校验失败显示字段诊断及节选输入且不泄露正文", async ({ page }) => {
  const payload = fixture();
  payload.news = [
    {
      id: "fixture-new-analysis",
      status: "FAILED",
      error_code: "INVALID_OUTPUT",
      quote: {
        prompt_version: "etf-symbol-h1-v2.1.1",
        schema_version: "etf-symbol-events-v2.1",
        input_content_type: "EXCERPT",
        eligible_source_ids: ["selected-news"],
        request_fingerprint: "f".repeat(64),
        input_text: "不得显示的原始提示词",
        input_budget_breakdown: { total_bytes: 4096, selected_articles: 1 },
        relevance: {
          candidate_count: 2,
          accepted_count: 1,
          excluded_count: 1,
          content_reductions: [{ id: "selected-news", input_chars: 1600 }],
        },
      },
      input_snapshot: {
        news: [
          {
            id: "selected-news",
            title: "选入官方标题",
            summary: "允许展示的存档摘要",
            content: "不得显示的正文",
            content_type: "FULL_TEXT",
            source_name: "FIXTURE",
            source_url: "https://example.org/fixture",
          },
          { id: "excluded-news", title: "未选入候选标题" },
        ],
      },
      result: {
        nature: "MOCK",
        input_tokens: 2000,
        output_tokens: 100,
        cost_usd: "0.0004",
        attempt_evidence: [
          {
            attempt: 1,
            http_status: 200,
            error_code: "INVALID_OUTPUT",
            raw_output: "不得显示的原始响应",
            validation_errors: [
              {
                path: "items[0].direction",
                code: "DIRECTION_SCORE_CONFLICT",
                message: "中性方向必须对应零情绪",
              },
            ],
          },
        ],
      },
    },
  ];
  const writes = await mockForward(page, payload);
  await page.goto("/#/forward");
  const panel = page.getByLabel("新闻覆盖与真实输入");
  await expect(panel).toContainText("正文节选");
  await expect(panel).toContainText("通过校验的引用 0 条");
  await expect(panel).toContainText("etf-symbol-h1-v2.1.1");
  await panel
    .getByRole("button", { name: "查看排除原因与选入新闻摘要" })
    .click();
  await expect(panel).toContainText("允许展示的存档摘要");
  await expect(panel).not.toContainText("未选入候选标题");
  await expect(panel).not.toContainText("完整正文");
  await panel.getByRole("button", { name: "查看输入预算与结构校验" }).click();
  await expect(panel).toContainText("4096 UTF-8 字节");
  await expect(panel).toContainText("2000 / 100");
  await expect(panel).toContainText("items[0].direction");
  await expect(panel).toContainText("中性方向必须对应零情绪");
  await expect(panel).not.toContainText("不得显示");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(391);
  expect(writes).toEqual([]);
});

test("最新分析成功不能改写旧冻结决策的ML_FALLBACK和版本", async ({ page }) => {
  const payload = fixture();
  const row: Record<string, any> = decision();
  row.details.effective_mode = "ML_FALLBACK";
  row.details.fallback_reason = "REAL_CALL_PENDING";
  row.details.news = {
    status: "MISSING",
    sentiment_score: null,
    risk_level: null,
  };
  payload.decisions = [row];
  payload.news = [
    {
      id: "new-success",
      status: "SUCCEEDED",
      quote: {
        prompt_version: "etf-symbol-h1-v2.1.1",
        schema_version: "etf-symbol-events-v2.1",
      },
      result: {
        nature: "MOCK",
        result: { items: [{ relevant_source_ids: ["new-source"] }] },
      },
    },
    {
      id: "fixture-analysis",
      status: "MISSING",
      error_code: "REAL_CALL_PENDING",
      quote: {
        prompt_version: "etf-symbol-h1-v2.0",
        schema_version: "etf-symbol-h1-v2.0",
      },
    },
  ];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await expect(page.getByTestId("forward-latest-analysis")).toContainText(
    "etf-symbol-h1-v2.1.1",
  );
  await expect(page.getByTestId("forward-latest-analysis")).toContainText(
    "不会改写上方历史冻结决策",
  );
  const frozen = page
    .getByTestId("forward-decisions")
    .locator(".el-table__body tr")
    .first();
  await expect(frozen).toContainText("ML_FALLBACK");
  await expect(frozen).toContainText("真实调用待验证");
  await expect(frozen).not.toContainText("新闻有效");
  await frozen.click();
  const drawer = page.locator(".el-drawer");
  await expect(drawer).toContainText("etf-symbol-h1-v2.0");
  await expect(drawer).not.toContainText("etf-symbol-h1-v2.1.1");
  await drawer
    .getByRole("button", { name: "该决策关联的分析输入摘要" })
    .click();
  await expect(drawer).toContainText("该决策冻结模式 ML_FALLBACK");
  await expect(drawer).not.toContainText("new-source");
});

/** 证据ID协议的隔离响应；冻结quote与模型自造文本特意不同以验证事实来源边界。 */
function evidenceRecord(): Record<string, any> {
  const id = "EV_" + "a".repeat(64),
    snapshot = "b".repeat(64);
  return {
    id: "fixture-evidence-analysis",
    status: "SUCCEEDED",
    quote: {
      schema_version: "etf-symbol-evidence-v2.1.2",
      prompt_version: "etf-symbol-h1-v2.1.3",
      evidence_version: "news-evidence-v1",
      evidence_snapshot_id: snapshot,
      evidence_catalog: [
        {
          id,
          source_id: "fixture-source",
          field: "content",
          source_content_type: "FULL_TEXT",
          content_type: "EXCERPT",
          standalone: true,
          context_required: false,
          text: "不应展示的完整原片段",
          display_text: "获准官方冻结证据片段",
          display_status: "DISPLAYABLE",
          display_truncated: true,
        },
      ],
      eligible_source_ids: ["fixture-source"],
      relevance: { candidate_count: 1, accepted_count: 1, excluded_count: 0 },
    },
    input_snapshot: {
      news: [
        {
          id: "fixture-source",
          source_name: "CSRC",
          source_url: "https://www.csrc.gov.cn/fixture-evidence",
          title: "冻结来源标题",
          summary: "公开摘要",
        },
      ],
    },
    result: {
      nature: "MOCK",
      evidence_version: "news-evidence-v1",
      evidence_snapshot_id: snapshot,
      evidence_catalog: [{ id, display_text: "模型伪造目录片段" }],
      result: {
        items: [
          {
            symbol: "510300",
            status: "AVAILABLE",
            sentiment_score: 0,
            risk_level: "low",
            evidence_ids: [id],
            relevant_source_ids: ["fixture-source"],
            facts: [{ quote: "模型伪造事实" }],
            impact_logic: "这是模型影响推断",
            uncertainty: "短期影响无法确认",
          },
        ],
      },
    },
  };
}

test("前瞻证据ID只显示冻结片段并区分不足中性高风险与模型推断", async ({
  page,
}) => {
  const payload = fixture(),
    record = evidenceRecord(),
    item = record.result.result.items[0];
  const titleId = "EV_" + "d".repeat(64);
  record.quote.evidence_catalog.push({
    ...record.quote.evidence_catalog[0],
    id: titleId,
    field: "title",
    content_type: "SUMMARY",
    display_text: "冻结标题片段",
  });
  item.evidence_ids.push(titleId);
  record.result.result.items = [
    {
      ...structuredClone(item),
      status: "INSUFFICIENT_DATA",
      sentiment_score: null,
      risk_level: null,
    },
    { ...structuredClone(item), symbol: "510500" },
    {
      ...structuredClone(item),
      symbol: "159915",
      sentiment_score: -0.2,
      risk_level: "high",
    },
  ];
  payload.news = [record];
  const writes = await mockForward(page, payload);
  await page.goto("/#/forward");
  await page
    .getByRole("button", { name: "查看冻结事实与模型推断", exact: true })
    .click();
  const references = page.getByTestId("forward-evidence-references");
  await expect(references).toContainText("510300 · 数据不足 · S — · 风险 —");
  await expect(references).toContainText(
    "510500 · 新闻有效 · S 0.000000 · 风险 低",
  );
  await expect(references).toContainText(
    "159915 · 新闻有效 · S -0.200000 · 风险 高",
  );
  await expect(references).toContainText("获准官方冻结证据片段");
  await expect(references).toContainText("来源摘要");
  await expect(references).toContainText("正文节选");
  await expect(references).not.toContainText("完整正文");
  await expect(references).toContainText("推断（模型影响逻辑）");
  await expect(references).toContainText("短期影响无法确认");
  await expect(references).not.toContainText("模型伪造");
  await expect(references).not.toContainText("完整原片段");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(391);
  expect(writes).toEqual([]);
});

test("前瞻未知证据不会采用模型伪造目录或旧facts", async ({ page }) => {
  const payload = fixture(),
    record = evidenceRecord();
  record.result.result.items[0].evidence_ids = ["EV_" + "f".repeat(64)];
  payload.news = [record];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await page
    .getByRole("button", { name: "查看冻结事实与模型推断", exact: true })
    .click();
  const references = page.getByTestId("forward-evidence-references");
  await expect(references).toContainText("证据 ID 不在冻结目录中");
  await expect(references).not.toContainText("获准官方冻结证据片段");
  await expect(references).not.toContainText("模型伪造");
});

test("前瞻仅报价WAITING可查看冻结输入目录但不填经校验事实", async ({
  page,
}) => {
  const payload = fixture(),
    record = evidenceRecord();
  record.status = "MISSING";
  record.error_code = "DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED";
  payload.news = [record];
  await mockForward(page, payload);
  await page.goto("/#/forward");
  await expect(page.getByLabel("新闻覆盖与真实输入")).toContainText(
    "WAITING_REAL_VALIDATION",
  );
  await page
    .getByRole("button", { name: "查看冻结事实与模型推断", exact: true })
    .click();
  const catalog = page.getByTestId("forward-evidence-catalog");
  await catalog.locator("summary").click();
  await expect(catalog).toContainText("1 项，尚非模型引用");
  await expect(catalog).toContainText("获准官方冻结证据片段");
  const references = page.getByTestId("forward-evidence-references");
  await expect(references).toContainText("尚无经校验的逐 ETF 事实与推断");
  await expect(references).not.toContainText("S 0.000000");
  await expect(references).not.toContainText("模型伪造");
});
