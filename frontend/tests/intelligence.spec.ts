import { test, expect, type Page } from "@playwright/test";

/** 所有新闻、费用和分析均为隔离夹具；只读路由禁止触发采集、训练或付费请求。 */
async function mockIntelligence(
  page: Page,
  payload: Record<string, any>,
  status = 200,
) {
  const writes: string[] = [];
  const queries: URLSearchParams[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() !== "GET") writes.push(url.pathname);
    if (url.pathname === "/api/auth/me") {
      await route.fulfill({ json: { username: "isolated-fixture-admin" } });
    } else if (url.pathname === "/api/intelligence") {
      queries.push(url.searchParams);
      await route.fulfill({ status, json: payload });
    } else {
      await route.fulfill({
        status: 404,
        json: { message: "未定义的隔离接口" },
      });
    }
  });
  return { writes, queries };
}

/** 一条明确为Mock的摘要证据；full content仅用于检查页面不会展示第三方全文。 */
function newsFixture() {
  return {
    id: "fixture-news-1",
    title: "隔离政策公告样本",
    summary: "本样本用于验证来源和输入时间。",
    content: "不得对外展示的全文夹具",
    content_type: "SUMMARY",
    nature: "MOCK",
    source_name: "PBOC",
    source_url: "https://www.pbc.gov.cn/fixture-only",
    published_at: "2026-10-24T01:00:00Z",
    fetched_at: "2026-10-24T01:10:00Z",
    publication_verified: true,
    metadata: {
      category: "MONETARY_POLICY",
      related_symbols: ["510300", "510500", "159915"],
      relevance_status: "RELEVANT",
      relevance_reason: "命中货币政策主题，仍需语义判断",
      importance_score: 80,
      importance_level: "high",
      importance_reasons: [
        { reason: "OFFICIAL_PRIMARY_SOURCE", points: 25 },
        { reason: "RECENCY", points: 20 },
      ],
      event_id: "fixture-event",
      selection_version: "fixture-selection-v2.1",
    },
  };
}

/** 每次分析记录的批次费用只有一份，按ETF显示时不制造多笔计费。 */
function fixture() {
  const news = newsFixture();
  return {
    generated_at: "2026-10-25T03:00:00Z",
    news: [news],
    news_total: 1,
    legacy_analyses: [],
    sources: [
      {
        source: "PBOC",
        name: "中国人民银行",
        status: "OK",
        last_attempt_at: "2026-10-24T01:10:00Z",
        last_success_at: "2026-10-24T01:10:00Z",
        count: 3,
        relevant_count: 1,
        stale: false,
      },
      {
        source: "SINA_FINANCE",
        name: "新浪财经RSS",
        status: "DISABLED",
        error_code: "STALE_SOURCE_FEED",
        source_updated_at: "2018-09-23T10:55:05Z",
        stale: true,
        count: 0,
        relevant_count: 0,
      },
      {
        source: "NBS",
        name: "统计信息源",
        status: "FAILED",
        error_code: "ReadTimeout",
        count: null,
        relevant_count: null,
      },
    ],
    analyses: [
      {
        id: "fixture-analysis",
        status: "SUCCEEDED",
        nature: "MOCK",
        evaluation_type: "FORWARD_PAPER",
        created_at: "2026-10-24T02:00:00Z",
        input_cutoff: "2026-10-24T01:59:00Z",
        quote: {
          eligible_source_ids: [news.id],
          prompt_version: "etf-symbol-h1-v2.1",
          schema_version: "etf-symbol-events-v2.1",
          model: "deepseek-flash",
          model_version: "fixture-config-label",
        },
        input_snapshot: { news: [news] },
        result: {
          nature: "MOCK",
          response_model: "fixture-model",
          result: {
            items: [
              {
                symbol: "510300",
                horizon: 1,
                status: "AVAILABLE",
                relevant_source_ids: [news.id],
                sentiment_score: 0,
                risk_level: "high",
                rationale: "隔离风险说明，不是真实市场结论",
                relevance_level: "high",
                direction: "mixed",
                facts: [
                  { source_id: news.id, quote: "本样本用于验证来源和输入时间" },
                ],
                impact_logic: "隔离影响逻辑",
                uncertainty: "短期影响尚不能确定",
              },
            ],
          },
        },
        cost: {
          input_tokens: 1000,
          output_tokens: 200,
          actual_cost: "0.0005",
          reserved_cost: "0.002",
          status: "CONFIRMED",
        },
        usage: [
          {
            decision_id: "fixture-decision",
            symbol: "510300",
            account_id: "fixture-D",
            strategy: "FUSION_EXPERIMENT",
            generated_at: "2026-10-24T02:01:00Z",
            news_available: true,
            mode: "FUSION",
          },
        ],
        participated_in_fusion: true,
      },
    ],
  };
}

/** 新协议夹具只在浏览器隔离层模拟：事实来自quote公开目录，模型只能回传ID。 */
function evidenceFixture(): Record<string, any> {
  const payload: Record<string, any> = fixture();
  const record = payload.analyses[0],
    source = record.input_snapshot.news[0];
  source.source_name = "CSRC";
  source.source_url = "https://www.csrc.gov.cn/fixture-evidence";
  const id = "EV_" + "a".repeat(64),
    snapshot = "b".repeat(64);
  Object.assign(record.quote, {
    schema_version: "etf-symbol-evidence-v2.1.2",
    prompt_version: "etf-symbol-h1-v2.1.3",
    evidence_version: "news-evidence-v1",
    evidence_snapshot_id: snapshot,
    evidence_catalog: [
      {
        id,
        source_id: source.id,
        field: "content",
        source_content_type: "FULL_TEXT",
        content_type: "EXCERPT",
        published_at: source.published_at,
        fetched_at: source.fetched_at,
        standalone: false,
        context_required: true,
        start: 0,
        end: 300,
        text: "不应展示的完整冻结原文",
        display_text: "获准公开的冻结官方事实片段。",
        display_status: "DISPLAYABLE",
        display_truncated: true,
      },
    ],
  });
  Object.assign(record.result, {
    evidence_version: "news-evidence-v1",
    evidence_snapshot_id: snapshot,
    evidence_catalog: [{ id, display_text: "模型伪造目录的文本" }],
  });
  Object.assign(record.result.result.items[0], {
    evidence_ids: [id],
    facts: [{ quote: "模型伪造的事实文本" }],
  });
  return payload;
}

test("财经情报真实空态与只读刷新", async ({ page }) => {
  const { writes, queries } = await mockIntelligence(page, {
    sources: [],
    news: [],
    news_total: 0,
    analyses: [],
    legacy_analyses: [],
  });
  await page.goto("/#/intelligence");
  await expect(page.getByTestId("intelligence-news")).toContainText(
    "没有符合条件的已保存新闻",
  );
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  await expect(page.getByTestId("intelligence-analyses")).toContainText(
    "没有分析不等于市场中性",
  );
  await page.getByRole("button", { name: "刷新记录", exact: true }).click();
  await expect.poll(() => queries.length).toBe(2);
  expect(writes).toEqual([]);
});

test("来源成功空缺停用与失败保留各自状态", async ({ page }) => {
  await mockIntelligence(page, fixture());
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "来源监控", exact: true }).click();
  const rows = page
    .getByTestId("intelligence-sources")
    .locator(".el-table__body tr");
  await expect(rows.nth(0)).toContainText("采集成功");
  await expect(rows.nth(0)).toContainText("3 / 1");
  await expect(rows.nth(1)).toContainText("已停用");
  await expect(rows.nth(1)).toContainText("2018/9/23");
  await expect(rows.nth(2)).toContainText("ReadTimeout");
  await expect(rows.nth(2)).toContainText("— / —");
});

test("摘要详情保留来源时间与筛选理由且不展示全文", async ({ page }) => {
  await mockIntelligence(page, fixture());
  await page.goto("/#/intelligence");
  await page
    .getByTestId("intelligence-news")
    .locator(".el-table__body tr")
    .first()
    .click();
  const drawer = page
    .locator(".el-drawer")
    .filter({ hasText: "新闻出处与筛选证据" });
  await expect(drawer).toContainText("来源摘要");
  await expect(drawer).toContainText("Mock 测试");
  await expect(drawer).toContainText("2026/10/24 09:00:00");
  await expect(drawer).toContainText("2026/10/24 09:10:00");
  await expect(drawer).toContainText("仍需语义判断");
  await expect(drawer).toContainText("官方原始来源 +25；信息时效 +20");
  await expect(drawer).not.toContainText("[object Object]");
  await expect(
    drawer.getByRole("link", { name: "查看原始来源" }),
  ).toHaveAttribute("href", "https://www.pbc.gov.cn/fixture-only");
  await expect(drawer).not.toContainText("不得对外展示的全文夹具");
});

test("筛选条件交给后端且不产生写入", async ({ page }) => {
  const { writes, queries } = await mockIntelligence(page, fixture());
  await page.goto("/#/intelligence");
  await page
    .getByRole("combobox", { name: "ETF筛选", exact: true })
    .press("ArrowDown");
  await page
    .getByRole("option", { name: "510300 · 沪深300", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "重要性筛选", exact: true })
    .press("ArrowDown");
  await page.getByRole("option", { name: "高", exact: true }).click();
  await page
    .getByRole("combobox", { name: "新闻类别筛选", exact: true })
    .press("ArrowDown");
  await page.getByRole("option", { name: "货币政策", exact: true }).click();
  await page.getByRole("button", { name: "筛选新闻", exact: true }).click();
  await expect.poll(() => queries.length).toBe(2);
  expect(queries[1].get("symbol")).toBe("510300");
  expect(queries[1].get("importance")).toBe("high");
  expect(queries[1].get("category")).toBe("MONETARY_POLICY");
  expect(queries[1].get("offset")).toBe("0");
  expect(writes).toEqual([]);
});

test("有效零情绪与高风险保留并显示逐ETF依据和实际消费", async ({ page }) => {
  // 仅在隔离HTTP夹具中覆盖真实标记分支，绝不向正式系统写入此记录。
  const payload: Record<string, any> = fixture();
  payload.analyses[0].nature = "REAL";
  payload.analyses[0].result.nature = "REAL";
  payload.analyses[0].usage[0].nature = "REAL";
  payload.analyses[0].usage[0].evaluation_type = "FORWARD_PAPER";
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const row = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr")
    .first();
  await expect(row).toContainText("分析有效");
  await expect(row).toContainText("0.0000");
  await expect(row).toContainText("风险 高");
  await expect(row).toContainText("已参与融合决策");
  await expect(row).not.toContainText("0.00%");
  await row.click();
  const drawer = page
    .locator(".el-drawer")
    .filter({ hasText: "逐 ETF 新闻分析证据" });
  await expect(drawer).toContainText("etf-symbol-events-v2.1");
  await expect(drawer).toContainText("隔离影响逻辑");
  await expect(drawer).toContainText("短期影响尚不能确定");
  await expect(drawer).toContainText("fixture-news-1");
  await expect(drawer).toContainText("0.00050000 USD");
  await expect(drawer).toContainText("fixture-decision");
  await expect(drawer.locator(".fact-item").first()).toContainText(
    "本样本用于验证来源和输入时间",
  );
  await expect(drawer).not.toContainText("不得对外展示的全文夹具");
});

test("API失败分析不填零费用且回退引用不冒充融合", async ({ page }) => {
  const payload: Record<string, any> = fixture();
  payload.analyses[0].status = "FAILED";
  payload.analyses[0].error_code = "ReadTimeout";
  payload.analyses[0].result = {};
  payload.analyses[0].cost = { status: "UNCERTAIN" };
  payload.analyses[0].usage[0].news_available = false;
  payload.analyses[0].usage[0].mode = "ML_FALLBACK";
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const row = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr")
    .first();
  await expect(row).toContainText("未返回逐ETF结果");
  await expect(row).toContainText("ReadTimeout");
  await expect(row).toContainText("未参与有效融合");
  await expect(row).toContainText("— USD");
  await expect(row).not.toContainText("0.0000");
});

test("接口读取失败明确报错且不显示伪造新闻", async ({ page }) => {
  const { writes } = await mockIntelligence(
    page,
    { message: "情报查询暂不可用" },
    503,
  );
  await page.goto("/#/intelligence");
  await expect(page.getByTestId("intelligence-error")).toContainText(
    "情报查询暂不可用",
  );
  await expect(
    page.getByTestId("intelligence-news").locator(".el-table__body tr"),
  ).toHaveCount(0);
  expect(writes).toEqual([]);
});

test("手机布局及来源文本不成为可执行HTML", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const payload = fixture();
  payload.news[0].title = '<img src=x onerror="alert(1)">隔离文本';
  payload.news[0].source_url = "javascript:alert(1)";
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page
    .getByTestId("intelligence-news")
    .locator(".el-table__body tr")
    .first()
    .click();
  const drawer = page
    .locator(".el-drawer")
    .filter({ hasText: "新闻出处与筛选证据" });
  await expect(drawer).toContainText('<img src=x onerror="alert(1)">隔离文本');
  await expect(drawer.getByRole("link", { name: "查看原始来源" })).toHaveCount(
    0,
  );
  const box = await drawer.boundingBox();
  expect(box!.width).toBeLessThanOrEqual(390);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
});

test("新采集故障显示告警且部分失败保留已有条数", async ({ page }) => {
  const payload: Record<string, any> = fixture();
  payload.sources[0].status = "PARTIAL";
  payload.sources[0].last_reported_at = "2026-10-24T01:10:00Z";
  payload.collection_failure = [
    {
      created_at: "2026-10-25T01:00:00Z",
      details: { error_code: "ReadTimeout" },
    },
  ];
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await expect(
    page.getByTestId("intelligence-collection-failure"),
  ).toContainText("最近一次新闻采集失败");
  await page.getByRole("tab", { name: "来源监控", exact: true }).click();
  const row = page
    .getByTestId("intelligence-sources")
    .locator(".el-table__body tr")
    .first();
  await expect(row).toContainText("部分采集失败");
  await expect(row).toContainText("3 / 1");
});

test("历史采集失败不覆盖后续已完成采集状态", async ({ page }) => {
  const payload: Record<string, any> = fixture();
  payload.sources[0].last_reported_at = "2026-10-25T02:00:00Z";
  payload.collection_failure = [
    {
      created_at: "2026-10-24T02:00:00Z",
      details: { error_code: "ReadTimeout" },
    },
  ];
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await expect(page.getByTestId("intelligence-news")).toBeVisible();
  await expect(page.getByTestId("intelligence-collection-failure")).toHaveCount(
    0,
  );
});

test("实际送模截断后的正文显示节选而非存档全文", async ({ page }) => {
  const payload: Record<string, any> = fixture();
  const analysis = payload.analyses[0];
  analysis.input_snapshot.news[0].content_type = "FULL_TEXT";
  analysis.input_snapshot.news[0].metadata.content_type = "FULL_TEXT";
  analysis.quote.input_content_type = "MIXED";
  analysis.quote.relevance = {
    content_reductions: [
      {
        id: "fixture-news-1",
        reason: "MODEL_CONTENT_LIMIT",
        original_chars: 5000,
        input_chars: 1600,
      },
    ],
  };
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  await page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr")
    .first()
    .click();
  const source = page.locator(".el-drawer .covered-news").first();
  await expect(source).toContainText("正文节选");
  await expect(source).toContainText("实际送模节选 1600 字");
  await expect(source).not.toContainText("完整正文");
});

test("Mock或后端未确认及非前瞻引用均不冒充真实融合参与", async ({ page }) => {
  const payload: Record<string, any> = fixture();
  const original = payload.analyses[0];
  payload.analyses = [0, 1, 2].map((index) => {
    const record = structuredClone(original);
    record.id = "fixture-nonparticipation-" + index;
    record.nature = index === 0 ? "MOCK" : "REAL";
    record.result.nature = record.nature;
    record.participated_in_fusion = index !== 1;
    record.usage[0].nature = "REAL";
    record.usage[0].evaluation_type =
      index === 2 ? "HISTORICAL" : "FORWARD_PAPER";
    return record;
  });
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const rows = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr");
  await expect(rows).toHaveCount(3);
  for (let index = 0; index < 3; index++) {
    await expect(rows.nth(index)).toContainText("未参与有效融合");
    await expect(rows.nth(index)).not.toContainText("已参与融合决策");
  }
});

test("失败批次保留选入来源预算和校验明细但不显示未通过的分数", async ({
  page,
}) => {
  const payload: Record<string, any> = fixture();
  const record = payload.analyses[0];
  record.status = "FAILED";
  record.error_code = "INVALID_OUTPUT";
  record.nature = "REAL";
  record.result.nature = "REAL";
  record.result.result.items[0].sentiment_score = 0.9999;
  record.quote.prompt_version = "etf-symbol-h1-v2.1.1";
  record.quote.request_fingerprint = "fixture-request-fingerprint";
  record.quote.input_text = "不得显示的完整原始提示词";
  record.quote.input_budget_breakdown = {
    unit: "UTF8_BYTES",
    total_bytes: 4096,
    selected_articles: 1,
    summary_omitted_count: 1,
    components_bytes: {
      system_instructions: 1024,
      news_title_summary: 100,
      news_content: 1948,
      transport_reserve: 1024,
    },
  };
  record.result.attempt_evidence = [
    {
      attempt: 1,
      http_status: 200,
      error_code: "INVALID_OUTPUT",
      raw_output: "不得显示的原始模型响应",
      validation_errors: [
        {
          path: "items[0].facts[0].quote",
          code: "FACT_QUOTE_NOT_FOUND",
          message: "事实摘录不是冻结来源原文子串",
        },
      ],
    },
  ];
  record.input_snapshot.news.push({
    ...newsFixture(),
    id: "excluded-input",
    title: "未选入候选不能冒充模型输入",
  });
  const { writes } = await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const rows = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText("未返回逐ETF结果");
  await expect(rows.first()).toContainText("未参与有效融合");
  await expect(rows.first()).not.toContainText("0.9999");
  await rows.first().click();
  const drawer = page
    .locator(".el-drawer")
    .filter({ hasText: "逐 ETF 新闻分析证据" });
  await expect(drawer).toContainText("没有通过校验的引用新闻");
  await expect(page.getByTestId("analysis-selected-input")).toContainText(
    "隔离政策公告样本",
  );
  await expect(page.getByTestId("analysis-selected-input")).not.toContainText(
    "未选入候选不能冒充模型输入",
  );
  await expect(page.getByTestId("analysis-validation")).toContainText(
    "items[0].facts[0].quote",
  );
  await expect(page.getByTestId("analysis-validation")).toContainText(
    "FACT_QUOTE_NOT_FOUND",
  );
  await expect(page.getByTestId("analysis-validation")).toContainText(
    "事实摘录不是冻结来源原文子串",
  );
  await page.getByTestId("analysis-input-budget").locator("summary").click();
  await expect(page.getByTestId("analysis-input-budget")).toContainText(
    "4096 UTF-8 字节",
  );
  await expect(page.getByTestId("analysis-input-budget")).toContainText(
    "正文或节选：1948 字节",
  );
  await expect(drawer).toContainText("fixture-request-fingerprint");
  await expect(drawer).not.toContainText("不得显示");
  expect(writes).toEqual([]);
});

test("日保护和新版验证限制保留各记录版本且无成功分析假象", async ({ page }) => {
  const payload: Record<string, any> = fixture();
  const original = payload.analyses[0];
  payload.analyses = [
    {
      prompt: "etf-symbol-h1-v2.1.1",
      error: "DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED",
    },
    {
      prompt: "etf-symbol-h1-v2.1",
      error: "DAILY_VERSION_VALIDATION_LIMIT_CURRENT_REQUEST_NOT_VALIDATED",
    },
    { prompt: "etf-symbol-h1-v2.0", error: "FUTURE_UNRECOGNIZED_GUARD" },
  ].map((entry, index) => ({
    ...structuredClone(original),
    id: `guard-${index}`,
    status: "MISSING",
    error_code: entry.error,
    quote: { ...original.quote, prompt_version: entry.prompt },
    cost: {},
  }));
  const { writes } = await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const rows = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr");
  await expect(rows).toHaveCount(3);
  await expect(rows.nth(0)).toContainText("当日调用保护：当前请求尚未验证");
  await expect(rows.nth(0)).toContainText("etf-symbol-h1-v2.1.1");
  await expect(rows.nth(1)).toContainText("新版验证次数已用尽");
  await expect(rows.nth(1)).toContainText("etf-symbol-h1-v2.1");
  await expect(rows.nth(2)).toContainText("FUTURE_UNRECOGNIZED_GUARD");
  await expect(rows.nth(2)).toContainText("etf-symbol-h1-v2.0");
  for (const row of await rows.all()) {
    await expect(row).not.toContainText("分析有效");
    await expect(row).not.toContainText("0.0000");
  }
  expect(writes).toEqual([]);
});

test("证据ID区分信息不足中性和高风险且只展示冻结片段", async ({ page }) => {
  const payload = evidenceFixture(),
    record = payload.analyses[0];
  const item = record.result.result.items[0];
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
      symbol: "510300",
      status: "INSUFFICIENT_DATA",
      sentiment_score: null,
      risk_level: null,
    },
    {
      ...structuredClone(item),
      symbol: "510500",
      status: "AVAILABLE",
      sentiment_score: 0,
      risk_level: "low",
      direction: "neutral",
    },
    {
      ...structuredClone(item),
      symbol: "159915",
      status: "AVAILABLE",
      sentiment_score: -0.2,
      risk_level: "high",
      direction: "negative",
    },
  ];
  const { writes } = await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const rows = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr");
  await expect(rows).toHaveCount(3);
  await expect(rows.nth(0)).toContainText("信息不足");
  await expect(rows.nth(0).locator("td").nth(2)).toContainText("—");
  await expect(rows.nth(1).locator("td").nth(2)).toContainText("0.0000");
  await expect(rows.nth(2)).toContainText("风险 高");
  await expect(rows.nth(2)).not.toContainText("失败");
  await rows.first().click();
  const drawer = page
    .locator(".el-drawer")
    .filter({ hasText: "逐 ETF 新闻分析证据" });
  const references = page.getByTestId("analysis-evidence-references");
  await expect(references).toContainText("获准公开的冻结官方事实片段");
  await expect(references).toContainText("需要上下文");
  await expect(references).toContainText("前 240 字");
  await expect(references).toContainText("来源摘要");
  await expect(references).toContainText("正文节选");
  await expect(references).not.toContainText("完整正文");
  await expect(drawer).toContainText("推断（模型影响逻辑）");
  await expect(drawer).toContainText("不确定性");
  await expect(drawer).not.toContainText("模型伪造");
  await expect(drawer).not.toContainText("完整冻结原文");
  await expect(
    references.getByRole("link", { name: "证据原始来源", exact: true }).first(),
  ).toHaveAttribute("href", "https://www.csrc.gov.cn/fixture-evidence");
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
  expect(writes).toEqual([]);
});

test("未知证据未获准来源和快照不一致均不以模型文本补齐", async ({ page }) => {
  const payload = evidenceFixture(),
    original = payload.analyses[0];
  payload.analyses = [0, 1, 2, 3].map((index) => {
    const record = structuredClone(original);
    record.id = `evidence-error-${index}`;
    if (index === 0)
      record.result.result.items[0].evidence_ids = ["EV_" + "f".repeat(64)];
    if (index === 1)
      record.quote.evidence_catalog[0].display_status = "SOURCE_NOT_APPROVED";
    if (index === 2) record.result.evidence_snapshot_id = "c".repeat(64);
    if (index === 3) delete record.quote.evidence_catalog[0].display_text;
    return record;
  });
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const rows = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr");
  const messages = [
    "证据 ID 不在冻结目录中",
    "来源展示权限未核实",
    "证据版本或快照无法核对",
    "冻结片段暂无可展示文本",
  ];
  for (let index = 0; index < messages.length; index++) {
    await rows.nth(index).click();
    const drawer = page
      .locator(".el-drawer")
      .filter({ hasText: "逐 ETF 新闻分析证据" });
    const references = page.getByTestId("analysis-evidence-references");
    await expect(references).toContainText(messages[index]);
    await expect(references).not.toContainText("获准公开的冻结官方事实片段");
    await expect(drawer).not.toContainText("模型伪造");
    await drawer
      .getByRole("button", { name: "关闭此对话框", exact: true })
      .click();
    await expect(drawer).not.toBeVisible();
  }
});

test("新协议仅报价等待可查输入目录但保留旧真实失败且不填情绪", async ({
  page,
}) => {
  const payload = evidenceFixture(),
    pending = payload.analyses[0];
  const old = structuredClone(fixture().analyses[0]);
  Object.assign(old, {
    status: "FAILED",
    nature: "REAL",
    error_code: "INVALID_OUTPUT",
  });
  Object.assign(pending, {
    status: "MISSING",
    error_code: "DAILY_CALL_LIMIT_CURRENT_REQUEST_NOT_VALIDATED",
    cost: {},
  });
  payload.analyses.push(old);
  await mockIntelligence(page, payload);
  await page.goto("/#/intelligence");
  await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
  const rows = page
    .getByTestId("intelligence-analyses")
    .locator(".el-table__body tr");
  await expect(rows).toHaveCount(2);
  await expect(rows.first()).toContainText("WAITING_REAL_VALIDATION");
  await expect(rows.first()).not.toContainText("0.0000");
  await expect(rows.nth(1)).toContainText("失败");
  await expect(rows.nth(1)).toContainText("真实数据");
  await rows.first().click();
  const catalog = page.getByTestId("analysis-evidence-catalog");
  await expect(catalog.locator("summary")).toContainText("1 项，尚非模型引用");
  await catalog.locator("summary").click();
  await expect(catalog).toContainText("获准公开的冻结官方事实片段");
  await expect(catalog).not.toContainText("完整冻结原文");
  await expect(page.getByTestId("analysis-evidence-references")).toContainText(
    "没有经校验的冻结证据引用",
  );
});
