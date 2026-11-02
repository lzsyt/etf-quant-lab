#!/usr/bin/env node
/**
 * V2.1.1 线上只读浏览器验收。无 --run 时不加载凭据、不启动浏览器、不访问网络。
 * 仅认证允许 POST；其余任何写请求均拦截。只读页面不触发采集、模型、任务或交易。
 * 不保存 Cookie、凭据、完整 API 响应、正文或原始模型输出；失败不输出异常 message/stack。
 */
const fs = require("node:fs");
const path = require("node:path");
const { chromium, expect } = require(
  path.resolve(__dirname, "../frontend/node_modules/@playwright/test"),
);
const root = path.resolve(__dirname, "..");
const args = process.argv.slice(2);
const labels = {
  AVAILABLE: "分析有效",
  FAILED: "失败",
  MISSING: "缺失",
  SUCCEEDED: "成功",
  NO_RELEVANT_NEWS: "无相关新闻",
  INSUFFICIENT_DATA: "信息不足",
  STALE_NEWS: "新闻过期",
  low: "低",
  medium: "中",
  high: "高",
  SUMMARY: "来源摘要",
  FULL_TEXT: "完整正文",
  EXCERPT: "正文节选",
  TITLE_ONLY: "仅标题",
  MIXED: "混合类型（逐条标注）",
};

/** 参数仅用于显式执行、全新证据目录及预期部署版本，不接受任意目标站点。 */
function option(name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

/** 空情绪必须保留横线，不能当作有效中性分数。 */
function score(value, digits = 4) {
  return value == null || value === "" || !Number.isFinite(Number(value))
    ? "—"
    : Number(value).toFixed(digits);
}

/** 对外投影不能包含正文或原始提示字段，遍历字段名而不落盘敏感值。 */
function forbiddenFields(
  value,
  current = "",
  fields = ["content", "input_text"],
) {
  if (!value || typeof value !== "object") return [];
  return Object.entries(value).flatMap(([key, child]) => {
    const name = current + "/" + key;
    return fields.includes(key) ? [name] : forbiddenFields(child, name, fields);
  });
}

/** 只按冻结选入 ID 查源，后续新闻和未选候选不能补成当时输入。 */
function selectedInputs(record) {
  const ids = new Set(record.quote?.eligible_source_ids || []);
  return (record.input_snapshot?.news || []).filter((item) => ids.has(item.id));
}

/** 新协议只验冻结quote公开目录；既有MISSING报价可有目录，但绝不能据此认定已成功分析。 */
function checkEvidenceCatalog(record) {
  const quote = record.quote || {};
  if (!quote.evidence_version) return;
  expect(quote.evidence_snapshot_id).toMatch(/^[a-f0-9]{64}$/);
  expect(Array.isArray(quote.evidence_catalog)).toBe(true);
  expect(
    forbiddenFields(record.result, "result", ["evidence_catalog"]),
  ).toEqual([]);
  for (const entry of quote.evidence_catalog) {
    expect(entry.id || entry.evidence_id).toMatch(/^EV_[a-f0-9]{64}$/);
    expect(entry).not.toHaveProperty("text");
    const source = (record.input_snapshot?.news || []).find(
      (item) => item.id === entry.source_id,
    );
    if (entry.display_status === "DISPLAYABLE") {
      expect(typeof entry.display_text).toBe("string");
      expect([...entry.display_text].length).toBeGreaterThan(0);
      expect([...entry.display_text].length).toBeLessThanOrEqual(240);
      const domain = {
        CSRC: "csrc.gov.cn",
        NBS: "stats.gov.cn",
        MOF: "mof.gov.cn",
      }[source?.source_name];
      expect(domain).toBeTruthy();
      const url = new URL(source.source_url);
      expect(["http:", "https:"]).toContain(url.protocol);
      expect(
        url.hostname === domain || url.hostname.endsWith("." + domain),
      ).toBe(true);
    } else expect(entry).not.toHaveProperty("display_text");
  }
}

/** 保存最小可追踪元数据，文章正文、提示词及模型原文均不进入证据文件。 */
function analysisSummary(record) {
  return {
    id: record.id,
    status: record.status,
    error_code: record.error_code,
    nature: record.nature || record.result?.nature,
    created_at: record.created_at,
    input_cutoff: record.input_cutoff,
    prompt_version: record.quote?.prompt_version,
    schema_version: record.quote?.schema_version,
    evidence_version: record.quote?.evidence_version,
    evidence_snapshot_id: record.quote?.evidence_snapshot_id,
    evidence_catalog: (record.quote?.evidence_catalog || []).map((entry) => ({
      id: entry.id || entry.evidence_id,
      source_id: entry.source_id,
      field: entry.field,
      content_type: entry.content_type,
      source_content_type: entry.source_content_type,
      published_at: entry.published_at,
      fetched_at: entry.fetched_at,
      standalone: entry.standalone,
      context_required: entry.context_required,
      display_status: entry.display_status,
      display_truncated: entry.display_truncated,
      display_chars:
        typeof entry.display_text === "string"
          ? [...entry.display_text].length
          : 0,
    })),
    request_fingerprint: record.quote?.request_fingerprint,
    model: record.result?.response_model || record.quote?.model,
    selected_count: record.quote?.eligible_source_ids?.length,
    input_content_type: record.quote?.input_content_type,
    input_budget_breakdown: record.quote?.input_budget_breakdown,
    selected_sources: selectedInputs(record).map((item) => ({
      id: item.id,
      title: item.title,
      source_name: item.source_name,
      published_at: item.published_at,
      fetched_at: item.fetched_at,
      content_type: (record.quote?.relevance?.content_reductions || []).some(
        (entry) => entry.id === item.id,
      )
        ? "EXCERPT"
        : item.content_type || item.metadata?.content_type || "SUMMARY",
    })),
    valid_items:
      record.status === "SUCCEEDED"
        ? (record.result?.result?.items || []).map((item) => ({
            symbol: item.symbol,
            status: item.status,
            sentiment_score: item.sentiment_score,
            risk_level: item.risk_level,
            relevant_source_ids: item.relevant_source_ids,
          }))
        : [],
    attempts: (record.result?.attempt_evidence || []).map((attempt) => ({
      attempt: attempt.attempt,
      http_status: attempt.http_status,
      error_code: attempt.error_code,
      validation_errors: (attempt.validation_errors || []).map((issue) => ({
        path: issue.path,
        code: issue.code,
      })),
    })),
    participated_in_fusion: record.participated_in_fusion,
    cost: record.cost
      ? {
          input_tokens: record.cost.input_tokens,
          output_tokens: record.cost.output_tokens,
          estimated_usd: record.cost.actual_cost,
          status: record.cost.status,
        }
      : {
          input_tokens: record.result?.input_tokens,
          output_tokens: record.result?.output_tokens,
          estimated_usd: record.result?.cost_usd,
        },
  };
}

/** 执行只读验收，登录之后才允许保存无凭据页面截图。 */
async function main() {
  if (!args.includes("--run") || !option("--output")) {
    console.log(
      "未连接远端。部署后用法：node tests/news_v211_live_ui.cjs --run --output <全新目录> [--expect-prompt <版本>] [--expect-schema <版本>] [--expect-status MISSING] [--expect-evidence-version news-evidence-v1] [--expect-evidence-count 8]",
    );
    return;
  }
  const base = "https://etf.example.invalid";
  const output = path.resolve(option("--output"));
  if (fs.existsSync(output)) throw new Error("证据目录已存在，拒绝覆盖");
  const configPath =
    process.env.ETF_E2E_CONFIG ||
    path.join(root, ".runtime/server-secrets.json");
  if (fs.statSync(configPath).mode & 0o077)
    throw new Error("凭据文件权限不够严格");
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  if (!config.admin_password || typeof config.admin_password !== "string")
    throw new Error("缺少受保护管理员凭据");
  fs.mkdirSync(output, { recursive: true, mode: 0o700 });
  const evidence = {
    started_at: new Date().toISOString(),
    base_url: base,
    expected_prompt: option("--expect-prompt"),
    expected_schema: option("--expect-schema"),
    expected_status: option("--expect-status"),
    expected_evidence_version: option("--expect-evidence-version"),
    expected_evidence_count: option("--expect-evidence-count"),
    business_writes: [],
    page_errors: [],
    api_failures: [],
    assertions: [],
    not_applicable: [],
    screenshots: [],
    success: false,
  };
  let browser, page;
  let stage = "launch",
    injectedFailure = false,
    loggedIn = false;
  try {
    browser = await chromium.launch({
      executablePath:
        process.env.ETF_CHROMIUM_PATH ||
        "/home/developer/.codex/tools/ms-playwright/chromium-1228/chrome-linux64/chrome",
    });
    const context = await browser.newContext({
      viewport: { width: 1440, height: 1050 },
    });
    await context.route("**/*", async (route) => {
      const request = route.request(),
        url = new URL(request.url());
      const allowedLogin =
        request.method() === "POST" &&
        url.origin === base &&
        url.pathname === "/api/auth/login";
      if (!["GET", "HEAD"].includes(request.method()) && !allowedLogin) {
        evidence.business_writes.push({
          path: url.pathname,
          method: request.method(),
        });
        await route.abort();
      } else await route.continue();
    });
    page = await context.newPage();
    page.on("pageerror", (error) =>
      evidence.page_errors.push({ name: error.name }),
    );
    page.on("response", (response) => {
      const url = new URL(response.url());
      if (
        !injectedFailure &&
        url.origin === base &&
        url.pathname.startsWith("/api/") &&
        response.status() >= 400 &&
        !(url.pathname === "/api/auth/me" && response.status() === 401)
      ) {
        evidence.api_failures.push({
          path: url.pathname,
          status: response.status(),
        });
      }
    });
    /** 只等待指定 GET 响应，登录和其他业务请求不冒充数据刷新。 */
    const readResponse = (pathname) =>
      page.waitForResponse((response) => {
        const url = new URL(response.url());
        return (
          url.origin === base &&
          url.pathname === pathname &&
          response.request().method() === "GET"
        );
      });
    /** 截图统一关闭动画，避免侧栏关闭中途被误判为页面遮挡。 */
    const screenshot = async (name) => {
      // Element Plus按高度展开内容；文本已在DOM不代表动画结束，截图前等待目录完全展开。
      await expect
        .poll(() =>
          page
            .locator(".el-collapse-item__wrap")
            .evaluateAll((nodes) =>
              nodes.every(
                (node) =>
                  getComputedStyle(node).display === "none" ||
                  node.clientHeight >= node.scrollHeight - 1,
              ),
            ),
        )
        .toBe(true);
      await page.screenshot({
        path: path.join(output, name),
        fullPage: true,
        animations: "disabled",
      });
      evidence.screenshots.push(name);
    };
    /** 抽屉关闭后等待不可见，后续证据不截取过渡帧。 */
    const closeDrawer = async (drawer) => {
      await drawer
        .getByRole("button", { name: "关闭此对话框", exact: true })
        .click();
      await expect(drawer).not.toBeVisible();
    };
    stage = "login";
    await page.goto(base + "/#/intelligence", {
      waitUntil: "domcontentloaded",
    });
    await page.locator('input[name="username"]').fill("admin");
    await page.locator('input[name="password"]').fill(config.admin_password);
    const initialResponse = readResponse("/api/intelligence");
    await page.getByRole("button", { name: "进入工作台", exact: true }).click();
    const initial = await initialResponse;
    delete config.admin_password;
    expect(initial.status()).toBe(200);
    const initialPayload = await initial.json();
    await expect(page.locator('input[name="password"]')).toHaveCount(0);
    loggedIn = true;
    expect(forbiddenFields(initialPayload)).toEqual([]);
    evidence.news_total = initialPayload.news_total;
    evidence.sources = (initialPayload.sources || []).map((row) => ({
      source: row.source,
      status: row.status,
      last_success_at: row.last_success_at,
      count: row.count,
      relevant_count: row.relevant_count,
      error_code: row.error_code,
      stale: row.stale,
    }));
    evidence.assertions.push(
      "真实HTTPS情报读取成功，响应无content/input_text字段",
    );

    stage = "news-and-filter";
    const newsTable = page.getByTestId("intelligence-news");
    await expect(newsTable).toBeVisible();
    await screenshot("news-desktop.png");
    const newsRows = newsTable.locator(".el-table__body tr");
    if (await newsRows.count()) {
      await newsRows.first().click();
      const drawer = page
        .locator(".el-drawer")
        .filter({ hasText: "新闻出处与筛选证据" });
      await expect(drawer).toContainText("抓取时间");
      await expect(drawer).toContainText("初筛结果");
      await expect(drawer).toContainText("页面仅展示允许展示的摘要");
      await closeDrawer(drawer);
    } else await expect(newsTable).toContainText("没有符合条件的已保存新闻");
    await page
      .getByRole("combobox", { name: "ETF筛选", exact: true })
      .press("ArrowDown");
    await page
      .getByRole("option", { name: "510300 · 沪深300", exact: true })
      .click();
    const filteredResponse = readResponse("/api/intelligence");
    await page.getByRole("button", { name: "筛选新闻", exact: true }).click();
    const filtered = await filteredResponse;
    expect(filtered.status()).toBe(200);
    expect(new URL(filtered.url()).searchParams.get("symbol")).toBe("510300");
    const payload = await filtered.json();
    expect(
      (payload.news || []).every((row) =>
        row.metadata?.related_symbols?.includes("510300"),
      ),
    ).toBe(true);
    evidence.filtered_news_total = payload.news_total;
    evidence.assertions.push("真实新闻或空态、来源筛选证据及510300筛选一致");
    await page.getByRole("tab", { name: "来源监控", exact: true }).click();
    await expect(page.getByTestId("intelligence-sources")).toBeVisible();
    await screenshot("sources-desktop.png");

    stage = "analysis-status-and-cost";
    evidence.analyses = (payload.analyses || []).map(analysisSummary);
    const expectedRows = (payload.analyses || []).flatMap((record) => {
      const items =
        record.status === "SUCCEEDED" ? record.result?.result?.items || [] : [];
      return items.length
        ? items.map((item) => ({ item, record }))
        : [{ item: {}, record }];
    });
    if (option("--expect-prompt"))
      expect(payload.analyses?.[0]?.quote?.prompt_version).toBe(
        option("--expect-prompt"),
      );
    if (option("--expect-schema"))
      expect(payload.analyses?.[0]?.quote?.schema_version).toBe(
        option("--expect-schema"),
      );
    if (option("--expect-status"))
      expect(payload.analyses?.[0]?.status).toBe(option("--expect-status"));
    if (option("--expect-evidence-version"))
      expect(payload.analyses?.[0]?.quote?.evidence_version).toBe(
        option("--expect-evidence-version"),
      );
    if (option("--expect-evidence-count"))
      expect(payload.analyses?.[0]?.quote?.evidence_catalog).toHaveLength(
        Number(option("--expect-evidence-count")),
      );
    for (const record of payload.analyses || []) checkEvidenceCatalog(record);
    await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
    const analysisTable = page.getByTestId("intelligence-analyses"),
      analysisRows = analysisTable.locator(".el-table__body tr");
    await expect(analysisRows).toHaveCount(expectedRows.length);
    if (!expectedRows.length)
      await expect(analysisTable).toContainText("没有分析不等于市场中性");
    for (const [index, { item, record }] of expectedRows.entries()) {
      const row = analysisRows.nth(index),
        cells = row.locator("td");
      await expect(row).toContainText(
        labels[item.status || record.status] || item.status || record.status,
      );
      await expect(cells.nth(2)).toContainText(score(item.sentiment_score));
      await expect(cells.nth(2)).toContainText(
        "风险 " + (labels[item.risk_level] || item.risk_level || "—"),
      );
      if (record.quote?.prompt_version)
        await expect(row).toContainText(record.quote.prompt_version);
      await expect(cells.nth(4)).toContainText(
        `${record.quote?.eligible_source_ids?.length ?? "—"} / ${(item.relevant_source_ids || []).length}`,
      );
      await expect(cells.nth(5)).toContainText(
        `入 ${record.cost?.input_tokens ?? "—"} / 出 ${record.cost?.output_tokens ?? "—"}`,
      );
      await expect(cells.nth(5)).toContainText(
        score(record.cost?.actual_cost, 8) + " USD",
      );
      const participated =
        record.status === "SUCCEEDED" &&
        item.status === "AVAILABLE" &&
        record.nature === "REAL" &&
        record.participated_in_fusion === true &&
        (record.usage || []).some(
          (usage) =>
            usage.symbol === item.symbol &&
            usage.strategy === "FUSION_EXPERIMENT" &&
            usage.news_available === true &&
            usage.nature === "REAL" &&
            usage.evaluation_type === "FORWARD_PAPER" &&
            usage.mode === "FUSION",
        );
      await expect(row).toContainText(
        participated ? "已参与融合决策" : "未参与有效融合",
      );
      if (record.status !== "SUCCEEDED") {
        await expect(cells.nth(2)).not.toContainText("0.0000");
        await expect(row).toContainText("未参与有效融合");
      }
    }
    evidence.assertions.push(
      "逐记录状态、版本、选入与引用数、情绪空值或有效值及估算费用与真实API一致",
    );
    await screenshot("analyses-desktop.png");
    if (expectedRows.length) {
      stage = "analysis-input-and-validation";
      const record = expectedRows[0].record;
      await analysisRows.first().click();
      const drawer = page
        .locator(".el-drawer")
        .filter({ hasText: "逐 ETF 新闻分析证据" });
      if (record.quote?.schema_version)
        await expect(drawer).toContainText(record.quote.schema_version);
      await expect(drawer).toContainText("应用估算不等于供应商账单");
      const inputArticles = page
        .getByTestId("analysis-selected-input")
        .locator(".covered-news");
      const inputs = selectedInputs(record);
      await expect(inputArticles).toHaveCount(inputs.length);
      for (const [index, item] of inputs.entries()) {
        await expect(inputArticles.nth(index)).toContainText(item.title);
        await expect(inputArticles.nth(index)).toContainText(item.id);
        const type = (record.quote?.relevance?.content_reductions || []).some(
          (entry) => entry.id === item.id,
        )
          ? "EXCERPT"
          : item.content_type || item.metadata?.content_type || "SUMMARY";
        await expect(inputArticles.nth(index)).toContainText(
          labels[type] || type,
        );
        if (item.source_url && /^https?:\/\//.test(item.source_url)) {
          await expect(
            inputArticles
              .nth(index)
              .getByRole("link", { name: "原始来源", exact: true }),
          ).toHaveAttribute("href", new URL(item.source_url).href);
        }
      }
      if (record.status !== "SUCCEEDED")
        await expect(drawer).toContainText("没有通过校验的引用新闻");
      await screenshot("analysis-input-desktop.png");
      if (record.quote?.evidence_version) {
        if (record.status === "MISSING")
          await expect(drawer).toContainText("WAITING_REAL_VALIDATION");
        const catalog = page.getByTestId("analysis-evidence-catalog");
        await catalog.locator("summary").click();
        await expect(catalog.locator(".fact-item")).toHaveCount(
          record.quote.evidence_catalog.length,
        );
        await expect(catalog).toContainText("尚非模型引用");
        for (const entry of record.quote.evidence_catalog) {
          await expect(catalog).toContainText(entry.id || entry.evidence_id);
          if (entry.display_status === "DISPLAYABLE")
            await expect(catalog).toContainText(entry.display_text);
        }
        if (record.status !== "SUCCEEDED")
          await expect(
            page.getByTestId("analysis-evidence-references"),
          ).toContainText("没有经校验的冻结证据引用");
        await catalog.scrollIntoViewIfNeeded();
        await screenshot("analysis-evidence-catalog-desktop.png");
        await catalog.locator("summary").click();
        evidence.assertions.push(
          "冻结输入目录ID/来源/官方短片段可核对；MISSING等待真实验证，无有效情绪或模型引用",
        );
      }
      const budget = record.quote?.input_budget_breakdown;
      if (budget) {
        expect(budget.unit).toBe("UTF8_BYTES");
        expect(
          Object.values(budget.components_bytes).reduce(
            (sum, value) => sum + Number(value),
            0,
          ),
        ).toBe(Number(budget.total_bytes));
        await page
          .getByTestId("analysis-input-budget")
          .locator("summary")
          .click();
        await expect(page.getByTestId("analysis-input-budget")).toContainText(
          `${budget.total_bytes} UTF-8 字节`,
        );
        await expect(page.getByTestId("analysis-input-budget")).toContainText(
          "不是供应商实际 Token 用量",
        );
      } else
        evidence.not_applicable.push("最新记录未保存字节预算，未推造旧版预算");
      const validation = page.getByTestId("analysis-validation");
      for (const attempt of record.result?.attempt_evidence || []) {
        for (const issue of attempt.validation_errors || []) {
          if (issue.path)
            await expect(validation).toContainText(String(issue.path));
          if (issue.code) await expect(validation).toContainText(issue.code);
        }
      }
      await validation.scrollIntoViewIfNeeded();
      await screenshot("analysis-validation-desktop.png");
      await closeDrawer(drawer);
      evidence.assertions.push(
        "冻结选入来源可追溯；预算为字节上界，失败有真实校验明细时逐字段展示",
      );
    }

    stage = "intelligence-mobile-and-local-read-error";
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("tab", { name: "财经新闻", exact: true }).click();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(391);
    await screenshot("intelligence-mobile.png");
    injectedFailure = true;
    const failRead = (route) =>
      route.fulfill({
        status: 503,
        json: { message: "只读验收：浏览器隔离的读取失败" },
      });
    await page.route("**/api/intelligence?**", failRead);
    await page.getByRole("button", { name: "刷新记录", exact: true }).click();
    await expect(page.getByTestId("intelligence-error")).toContainText(
      "浏览器隔离的读取失败",
    );
    await expect(page.locator(".page")).toContainText("上次成功读取的记录");
    await page.unroute("**/api/intelligence?**", failRead);
    injectedFailure = false;
    const recovered = readResponse("/api/intelligence");
    await page.getByRole("button", { name: "刷新记录", exact: true }).click();
    expect((await recovered).status()).toBe(200);
    await expect(page.getByTestId("intelligence-error")).toHaveCount(0);
    evidence.assertions.push(
      "情报手机布局正常；浏览器本地503保留旧数据，恢复只读请求成功",
    );

    stage = "forward-frozen-decisions";
    await page.setViewportSize({ width: 1440, height: 1050 });
    const forwardResponse = readResponse("/api/forward");
    await page.goto(base + "/#/forward", { waitUntil: "domcontentloaded" });
    const response = await forwardResponse;
    expect(response.status()).toBe(200);
    const forward = await response.json();
    for (const record of forward.news || []) checkEvidenceCatalog(record);
    expect(
      forbiddenFields({ news: forward.news, health: forward.health }),
    ).toEqual([]);
    expect(forward.accounts).toHaveLength(4);
    expect(forward.accounts.map((item) => item.strategy).sort()).toEqual(
      [
        "LGBM_BASE",
        "XGB_BASE",
        "LGBM_MATCHED_CONTROL",
        "FUSION_EXPERIMENT",
      ].sort(),
    );
    evidence.forward = {
      experiment_id: forward.experiment?.id,
      latest_analysis: forward.news?.[0]
        ? analysisSummary(forward.news[0])
        : null,
      accounts: forward.accounts.map((item) => ({
        id: item.id,
        strategy: item.strategy,
        initial_capital: item.initial_capital,
        metrics: item.metrics,
      })),
      decisions: (forward.decisions || []).map((row) => ({
        id: row.id,
        symbol: row.symbol,
        generated_at: row.generated_at,
        analysis_id: row.details?.analysis_id,
        mode: row.details?.effective_mode,
        news_status: row.details?.news?.status,
        sentiment_score: row.details?.news?.sentiment_score,
        fallback_reason: row.details?.fallback_reason,
      })),
    };
    const frozen = page
      .getByTestId("forward-decisions")
      .locator(".el-table__body tr");
    await expect(frozen).toHaveCount((forward.decisions || []).length);
    for (const [index, decision] of (forward.decisions || []).entries()) {
      await expect(frozen.nth(index)).toContainText(
        decision.details.effective_mode,
      );
      await expect(frozen.nth(index)).toContainText(
        "S " + score(decision.details.news?.sentiment_score, 6),
      );
    }
    await expect(page.getByTestId("forward-latest-analysis")).toContainText(
      "不会改写上方历史冻结决策",
    );
    const latest = forward.news?.[0];
    if (latest?.quote?.prompt_version)
      await expect(page.getByTestId("forward-latest-analysis")).toContainText(
        latest.quote.prompt_version,
      );
    await screenshot("forward-decisions-desktop.png");
    const fallbackIndex = (forward.decisions || []).findIndex(
      (row) =>
        row.details?.effective_mode === "ML_FALLBACK" &&
        row.details?.analysis_id !== latest?.id,
    );
    if (fallbackIndex >= 0) {
      const decision = forward.decisions[fallbackIndex],
        historic = forward.news?.find(
          (row) => row.id === decision.details.analysis_id,
        );
      await frozen.nth(fallbackIndex).click();
      const drawer = page
        .locator(".el-drawer")
        .filter({ hasText: "真实决策完整追踪" });
      await expect(drawer).toContainText("ML_FALLBACK");
      if (historic?.quote?.prompt_version)
        await expect(drawer).toContainText(historic.quote.prompt_version);
      if (
        historic?.quote?.prompt_version &&
        latest?.quote?.prompt_version &&
        historic.quote.prompt_version !== latest.quote.prompt_version
      )
        await expect(drawer).not.toContainText(latest.quote.prompt_version);
      await drawer
        .getByRole("button", { name: "该决策关联的分析输入摘要" })
        .click();
      await expect(drawer).toContainText(decision.details.analysis_id);
      await expect(drawer).toContainText("该决策冻结模式 ML_FALLBACK");
      await screenshot("forward-historical-fallback-desktop.png");
      await closeDrawer(drawer);
      evidence.assertions.push(
        historic?.quote?.prompt_version
          ? "较新分析与历史决策分开展示；既有ML_FALLBACK及其关联分析版本未被替换"
          : "较新分析与历史决策分开展示；既有ML_FALLBACK模式未被替换",
      );
      if (!historic)
        evidence.not_applicable.push(
          "历史关联分析不在forward最近记录列表，旧分析版本详情未覆盖",
        );
    } else
      evidence.not_applicable.push(
        "本次没有关联不同分析的历史ML_FALLBACK行，未制造降级样本",
      );
    evidence.assertions.push(
      "Forward真实状态和空/有效情绪匹配冻结API；news与health无content/input_text",
    );

    stage = "forward-latest-input-and-cost";
    if (latest) {
      const panel = page.getByLabel("新闻覆盖与真实输入");
      await expect(panel).toContainText(labels[latest.status] || latest.status);
      await expect(panel).toContainText(
        `选入 ${latest.quote?.relevance?.accepted_count ?? "—"} 条`,
      );
      if (latest.quote?.schema_version)
        await expect(panel).toContainText(latest.quote.schema_version);
      const inputPanel = panel
        .locator(".el-collapse-item")
        .filter({ hasText: "查看排除原因与选入新闻摘要" });
      await inputPanel
        .getByRole("button", {
          name: "查看排除原因与选入新闻摘要",
          exact: true,
        })
        .click();
      const sources = selectedInputs(latest);
      await expect(inputPanel.locator(".forward-news-item")).toHaveCount(
        sources.length,
      );
      for (const source of sources)
        await expect(inputPanel).toContainText(source.title);
      await panel
        .getByRole("button", { name: "查看输入预算与结构校验", exact: true })
        .click();
      await expect(panel).toContainText(
        `实际输入 / 输出 Token：${latest.result?.input_tokens ?? "—"} / ${latest.result?.output_tokens ?? "—"}`,
      );
      await expect(panel).toContainText(
        `应用估算 ${latest.result?.cost_usd ?? "—"} USD`,
      );
      await screenshot("forward-latest-input-desktop.png");
      await inputPanel
        .getByRole("button", {
          name: "查看排除原因与选入新闻摘要",
          exact: true,
        })
        .click();
      await panel
        .getByRole("button", { name: "查看输入预算与结构校验", exact: true })
        .click();
      evidence.assertions.push(
        "Forward最新分析的真实状态、版本、选入来源数量、Token及费用与API记录一致",
      );
      if (latest.quote?.evidence_version) {
        if (latest.status === "MISSING")
          await expect(panel).toContainText("WAITING_REAL_VALIDATION");
        await panel
          .getByRole("button", { name: "查看冻结事实与模型推断", exact: true })
          .click();
        const catalog = page.getByTestId("forward-evidence-catalog");
        await catalog.locator("summary").click();
        await expect(catalog.locator(".forward-news-item")).toHaveCount(
          latest.quote.evidence_catalog.length,
        );
        for (const entry of latest.quote.evidence_catalog)
          await expect(catalog).toContainText(entry.id || entry.evidence_id);
        if (latest.status !== "SUCCEEDED")
          await expect(
            page.getByTestId("forward-evidence-references"),
          ).toContainText("尚无经校验的逐 ETF 事实与推断");
        await screenshot("forward-evidence-catalog-desktop.png");
        await panel
          .getByRole("button", { name: "查看冻结事实与模型推断", exact: true })
          .click();
        evidence.assertions.push(
          "Forward冻结证据目录与报价一致，不把目录存在标记为已产生模型事实",
        );
      }
    }

    stage = "forward-four-accounts-and-mobile";
    await page.getByRole("tab", { name: "策略对比", exact: true }).click();
    const comparison = page.getByRole("tabpanel", {
      name: "策略对比",
      exact: true,
    });
    await expect(
      comparison.locator(".el-table").first().locator(".el-table__body tr"),
    ).toHaveCount(4);
    for (const label of [
      "A · LightGBM 基线",
      "B · XGBoost 基线",
      "C · 门槛匹配对照",
      "D · 新闻融合实验",
    ])
      await expect(comparison).toContainText(label);
    if (forward.accounts.every((row) => !row.curve?.length))
      await expect(page.getByTestId("forward-curve-empty")).toBeVisible();
    await screenshot("forward-accounts-desktop.png");
    await page.setViewportSize({ width: 390, height: 844 });
    for (const [tab, file] of [
      ["策略对比", "forward-accounts-mobile.png"],
      ["今日决策", "forward-decisions-mobile.png"],
    ]) {
      await page.getByRole("tab", { name: tab, exact: true }).click();
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth),
      ).toBeLessThanOrEqual(391);
      await screenshot(file);
    }
    evidence.assertions.push(
      "四个独立账户与真实空曲线保持可辨认，前瞻决策及账户手机页无整页横向溢出",
    );
    expect(evidence.business_writes).toEqual([]);
    expect(evidence.page_errors).toEqual([]);
    expect(evidence.api_failures).toEqual([]);
    evidence.success = true;
  } catch (error) {
    evidence.failure_stage = stage;
    evidence.error_name = error.name;
    process.exitCode = 1;
    // 只保存成功登录后的可见页面；不保存可能回显fill参数的Playwright错误文本。
    if (
      loggedIn &&
      page &&
      (await page.locator('input[name="password"]').count()) === 0
    ) {
      try {
        await page.screenshot({
          path: path.join(output, "failure.png"),
          fullPage: true,
          animations: "disabled",
        });
        evidence.screenshots.push("failure.png");
      } catch {
        /* 截图失败不能覆盖原始失败阶段。 */
      }
    }
  } finally {
    delete config.admin_password;
    if (browser) await browser.close();
    evidence.finished_at = new Date().toISOString();
    fs.writeFileSync(
      path.join(output, "result.json"),
      JSON.stringify(evidence, null, 2),
      { mode: 0o600 },
    );
    console.log(
      JSON.stringify({
        success: evidence.success,
        failure_stage: evidence.failure_stage,
        assertions: evidence.assertions.length,
        business_writes: evidence.business_writes.length,
        output,
      }),
    );
  }
}

main().catch((error) => {
  console.error(
    JSON.stringify({
      success: false,
      stage: "preflight",
      error_name: error.name,
    }),
  );
  process.exitCode = 1;
});
