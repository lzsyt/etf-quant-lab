#!/usr/bin/env node
/** V2.1真实HTTPS只读验收。显式--run后才加载受保护凭据；不采集、不调用模型、不触发业务任务。 */
const fs = require("node:fs");
const path = require("node:path");
const { chromium, expect } = require(
  path.resolve(__dirname, "../frontend/node_modules/@playwright/test"),
);
const root = path.resolve(__dirname, "..");
const args = process.argv.slice(2);

/** 仅解析明确的输出目录，避免默认运行将登录或截图写入未知位置。 */
function option(name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

/** 保存元数据与状态，不保存响应原文、Cookie、授权头或任何凭据。 */
function safeSummary(payload) {
  return {
    generated_at: payload.generated_at,
    news_total: payload.news_total,
    sources: (payload.sources || []).map((row) => ({
      source: row.source,
      name: row.name,
      status: row.status,
      last_attempt_at: row.last_attempt_at,
      last_success_at: row.last_success_at,
      count: row.count,
      relevant_count: row.relevant_count,
      error_code: row.error_code,
      stale: row.stale,
      source_updated_at: row.source_updated_at,
    })),
    analyses: (payload.analyses || []).map((row) => ({
      id: row.id,
      status: row.status,
      nature: row.nature,
      prompt_version: row.quote?.prompt_version,
      schema_version: row.quote?.schema_version,
      model: row.result?.response_model || row.quote?.model,
      participated_in_fusion: row.participated_in_fusion,
      input_tokens: row.cost?.input_tokens,
      output_tokens: row.cost?.output_tokens,
      estimated_usd: row.cost?.actual_cost,
    })),
    legacy_analysis_count: (payload.legacy_analyses || []).length,
  };
}

/** 全流程允许认证写入；任何非认证业务写入立即阻止并使验收失败。 */
async function main() {
  if (!args.includes("--run") || !option("--output")) {
    console.log(
      "只读线上验收未运行。用法：node tests/news_v21_live_ui.cjs --run --output <全新证据目录>",
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
  if (typeof config.admin_password !== "string" || !config.admin_password)
    throw new Error("缺少受保护管理员凭据");
  fs.mkdirSync(output, { recursive: true, mode: 0o700 });
  let browser;
  let stage = "launch";
  let injectedFailure = false;
  const evidence = {
    started_at: new Date().toISOString(),
    base_url: base,
    business_writes: [],
    page_errors: [],
    api_failures: [],
    assertions: [],
    screenshots: [],
    success: false,
  };
  try {
    browser = await chromium.launch({
      executablePath:
        process.env.ETF_CHROMIUM_PATH ||
        "/home/developer/.codex/tools/ms-playwright/chromium-1228/chrome-linux64/chrome",
    });
    const context = await browser.newContext({
      viewport: { width: 1440, height: 1050 },
    });
    await context.route("**/api/**", async (route) => {
      const request = route.request();
      const pathname = new URL(request.url()).pathname;
      if (
        !["GET", "HEAD"].includes(request.method()) &&
        pathname !== "/api/auth/login"
      ) {
        evidence.business_writes.push({
          path: pathname,
          method: request.method(),
        });
        await route.abort();
      } else await route.continue();
    });
    const page = await context.newPage();
    page.on("pageerror", (error) =>
      evidence.page_errors.push({ name: error.name }),
    );
    page.on("response", (response) => {
      const pathname = new URL(response.url()).pathname;
      if (
        !injectedFailure &&
        pathname.startsWith("/api/") &&
        response.status() >= 400 &&
        !(pathname === "/api/auth/me" && response.status() === 401)
      ) {
        evidence.api_failures.push({
          path: pathname,
          status: response.status(),
        });
      }
    });
    stage = "login";
    await page.goto(base + "/#/intelligence", {
      waitUntil: "domcontentloaded",
    });
    await page.locator('input[name="username"]').fill("admin");
    await page.locator('input[name="password"]').fill(config.admin_password);
    const initialResponse = page.waitForResponse(
      (response) => new URL(response.url()).pathname === "/api/intelligence",
    );
    await page.getByRole("button", { name: "进入工作台", exact: true }).click();
    const initial = await initialResponse;
    delete config.admin_password;
    expect(initial.status()).toBe(200);
    const initialPayload = await initial.json();
    evidence.api = safeSummary(initialPayload);
    await expect(page.getByTestId("intelligence-news")).toBeVisible();
    await expect(page.locator('input[name="password"]')).toHaveCount(0);
    evidence.assertions.push("真实HTTPS登录与财经情报深链接成功");

    stage = "news";
    await page.screenshot({
      path: path.join(output, "news-desktop.png"),
      fullPage: true,
    });
    evidence.screenshots.push("news-desktop.png");
    const rows = page
      .getByTestId("intelligence-news")
      .locator(".el-table__body tr");
    if (await rows.count()) {
      await rows.first().click();
      const drawer = page
        .locator(".el-drawer")
        .filter({ hasText: "新闻出处与筛选证据" });
      await expect(drawer).toContainText("抓取时间");
      await expect(drawer).toContainText("初筛结果");
      await expect(drawer).toContainText("页面仅展示允许展示的摘要");
      await drawer
        .getByRole("button", { name: "关闭此对话框", exact: true })
        .click();
      await expect(drawer).not.toBeVisible();
      evidence.assertions.push("真实新闻摘要、原始来源和筛选证据可查看");
    } else {
      await expect(page.getByTestId("intelligence-news")).toContainText(
        "没有符合条件的已保存新闻",
      );
      evidence.assertions.push("真实无新闻空态可辨认");
    }
    stage = "filter";
    await page
      .getByRole("combobox", { name: "ETF筛选", exact: true })
      .press("ArrowDown");
    await page
      .getByRole("option", { name: "510300 · 沪深300", exact: true })
      .click();
    const filteredResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return (
        url.pathname === "/api/intelligence" &&
        url.searchParams.get("symbol") === "510300"
      );
    });
    await page.getByRole("button", { name: "筛选新闻", exact: true }).click();
    const filtered = await filteredResponse;
    expect(filtered.status()).toBe(200);
    const filteredPayload = await filtered.json();
    expect(
      (filteredPayload.news || []).every((row) =>
        row.metadata?.related_symbols?.includes("510300"),
      ),
    ).toBe(true);
    evidence.filtered_news_total = filteredPayload.news_total;
    evidence.assertions.push("ETF筛选真实请求成功且结果均匹配品种");

    stage = "sources";
    await page.getByRole("tab", { name: "来源监控", exact: true }).click();
    await expect(page.getByTestId("intelligence-sources")).toBeVisible();
    await page.screenshot({
      path: path.join(output, "sources-desktop.png"),
      fullPage: true,
    });
    evidence.screenshots.push("sources-desktop.png");
    evidence.assertions.push("真实来源状态、成功时间和失败停用原因可查看");

    stage = "analyses";
    await page.getByRole("tab", { name: "DeepSeek 分析", exact: true }).click();
    const analyses = page.getByTestId("intelligence-analyses");
    await expect(analyses).toBeVisible();
    const analysisRows = analyses.locator(".el-table__body tr");
    if (await analysisRows.count()) {
      await analysisRows.first().click();
      const drawer = page
        .locator(".el-drawer")
        .filter({ hasText: "逐 ETF 新闻分析证据" });
      await expect(drawer).toContainText("提示词 / 结构版本");
      await expect(drawer).toContainText("应用估算不等于供应商账单");
      await expect(drawer).toContainText("实际决策引用");
      await page.screenshot({
        path: path.join(output, "analysis-detail-desktop.png"),
        fullPage: true,
        animations: "disabled",
      });
      evidence.screenshots.push("analysis-detail-desktop.png");
      await drawer
        .getByRole("button", { name: "关闭此对话框", exact: true })
        .click();
      await expect(drawer).not.toBeVisible();
      evidence.assertions.push("实际分析版本、费用及决策引用可查看");
    } else {
      await expect(analyses).toContainText("没有分析不等于市场中性");
      evidence.assertions.push("真实无分析空态可辨认");
    }
    await page.screenshot({
      path: path.join(output, "analyses-desktop.png"),
      fullPage: true,
    });
    evidence.screenshots.push("analyses-desktop.png");

    stage = "mobile";
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole("tab", { name: "财经新闻", exact: true }).click();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(390);
    await page.screenshot({
      path: path.join(output, "news-mobile.png"),
      fullPage: true,
    });
    evidence.screenshots.push("news-mobile.png");
    evidence.assertions.push("390px手机页面无整页横向溢出");

    stage = "read-failure-display";
    injectedFailure = true;
    // 只在当前浏览器拦截一次只读请求，制造本地503以验证提示；不更改生产服务状态。
    const failOnce = async (route) =>
      route.fulfill({
        status: 503,
        json: { message: "只读验收：浏览器隔离的读取失败" },
      });
    await page.route("**/api/intelligence?**", failOnce);
    await page.getByRole("button", { name: "刷新记录", exact: true }).click();
    await expect(page.getByTestId("intelligence-error")).toContainText(
      "浏览器隔离的读取失败",
    );
    await expect(page.locator(".page")).toContainText("上次成功读取的记录");
    await page.unroute("**/api/intelligence?**", failOnce);
    injectedFailure = false;
    const restoredResponse = page.waitForResponse(
      (response) => new URL(response.url()).pathname === "/api/intelligence",
    );
    await page.getByRole("button", { name: "刷新记录", exact: true }).click();
    expect((await restoredResponse).status()).toBe(200);
    await expect(page.getByTestId("intelligence-error")).toHaveCount(0);
    evidence.assertions.push(
      "隔离读取失败明确提示并保留旧时间，恢复后真实读取成功",
    );

    expect(evidence.business_writes).toEqual([]);
    expect(evidence.page_errors).toEqual([]);
    expect(evidence.api_failures).toEqual([]);
    evidence.success = true;
  } catch (error) {
    // Playwright错误可能包含fill值，故禁止保存message/stack或登录阶段截图。
    evidence.failure_stage = stage;
    evidence.error_name = error.name;
    process.exitCode = 1;
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
  // 配置读取等启动错误同样只输出类型，不意外输出凭据内容。
  console.error(
    JSON.stringify({
      success: false,
      stage: "preflight",
      error_name: error.name,
    }),
  );
  process.exitCode = 1;
});
