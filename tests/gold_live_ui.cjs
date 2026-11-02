#!/usr/bin/env node
/**
 * 黄金模块真实页面验收：只读页面及纯成本计算，禁止采集、训练、导入、交易或付费任务。
 * 无 --run 不加载凭据或访问网络。凭据/Cookie仅留在进程内，关闭trace/video，不输出异常原文。
 */
const fs = require("node:fs");
const path = require("node:path");
const { chromium, expect } = require(
  path.resolve(__dirname, "../frontend/node_modules/@playwright/test"),
);
const root = path.resolve(__dirname, "..");
const args = process.argv.slice(2);

/** 只读取明确命令参数，证据目录必须全新以保留原验收。 */
function option(name) {
  const index = args.indexOf(name);
  return index < 0 ? undefined : args[index + 1];
}

/** 真实验收仅允许已指定公网域名或本机回环HTTP，不发送凭据到其他站点。 */
function verifiedBase() {
  const url = new URL(
    process.env.ETF_GOLD_BASE_URL || "https://etf.example.invalid",
  );
  const allowed =
    url.origin === "https://etf.example.invalid" ||
    (url.protocol === "http:" &&
      ["127.0.0.1", "localhost"].includes(url.hostname));
  if (
    !allowed ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== "/"
  )
    throw new Error("验收目标不在允许范围");
  return url.origin;
}

/** 只保存可公开研究元数据；不归档完整API响应、模型原文或凭据。 */
function summary(data) {
  return {
    benchmark: data.benchmark,
    status: data.status,
    as_of: data.as_of,
    updated_at: data.updated_at,
    history_rows: data.history?.length,
    coverage: data.coverage,
    predictions: (data.predictions || []).map((row) => ({
      id: row.id,
      horizon: row.horizon,
      model_id: row.model_id,
      reference_date: row.reference_date,
      generated_at: row.generated_at,
      target_date: row.target_date,
      target_date_status: row.target_date_status,
      probability_up: row.probability_up,
      probability_validation_status: row.probability_validation_status,
      interval_lower: row.interval_lower,
      interval_upper: row.interval_upper,
      nominal_coverage: row.nominal_coverage,
      has_actual: row.actual != null,
    })),
    models: (data.models || []).map((row) => ({
      model_id: row.model_id,
      horizon: row.horizon,
      coverage: row.metrics?.coverage,
      advantage_status: row.metrics?.advantage_status,
      final_test: row.metrics?.final_test,
    })),
    sources: (data.sources || []).map((row) => ({
      series_id: row.series_id,
      status: row.status,
      count: row.count,
      last_observation_date: row.last_observation_date,
      checked_at: row.checked_at,
    })),
    news: {
      status: data.news?.status,
      display_status: data.news?.display_status,
      error_code: data.news?.error_code,
      explanation_status: data.news?.result?.result?.status,
    },
    health: {
      enabled: data.health?.enabled,
      scheduling_enabled: data.health?.scheduling_enabled,
    },
  };
}

/** 登录一次，依次检查五个真实标签、冻结证据、纯计算器以及原ETF页面。 */
async function main() {
  if (!args.includes("--run") || !option("--output")) {
    console.log(
      "未访问网络。用法：node tests/gold_live_ui.cjs --run --output <全新证据目录> [--require-predictions]；ETF_GOLD_BASE_URL可指定本机地址，ETF_E2E_CONFIG指定受保护凭据文件。",
    );
    return;
  }
  const base = verifiedBase();
  const output = path.resolve(option("--output"));
  if (fs.existsSync(output)) throw new Error("证据目录已存在，拒绝覆盖");
  const configPath =
    process.env.ETF_E2E_CONFIG ||
    path.join(
      root,
      base.startsWith("https:")
        ? ".runtime/server-secrets.json"
        : ".runtime/secrets.json",
    );
  if (fs.statSync(configPath).mode & 0o077)
    throw new Error("凭据文件权限不够严格");
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  if (typeof config.admin_password !== "string" || !config.admin_password)
    throw new Error("缺少受保护管理员凭据");
  fs.mkdirSync(output, { recursive: true, mode: 0o700 });
  const evidence = {
    started_at: new Date().toISOString(),
    base_url: base,
    assertions: [],
    screenshots: [],
    prohibited_requests: [],
    page_errors: [],
    api_failures: [],
    allowed_calculations: 0,
    not_applicable: [],
    success: false,
  };
  let browser;
  let page;
  let stage = "launch";
  let loggedIn = false;
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
      const request = route.request();
      const url = new URL(request.url());
      const allowedPost =
        url.origin === base &&
        request.method() === "POST" &&
        ["/api/auth/login", "/api/gold/cost"].includes(url.pathname);
      if (!["GET", "HEAD"].includes(request.method()) && !allowedPost) {
        evidence.prohibited_requests.push({
          path: url.pathname,
          method: request.method(),
        });
        return route.abort();
      }
      if (allowedPost && url.pathname === "/api/gold/cost")
        evidence.allowed_calculations++;
      return route.continue();
    });
    page = await context.newPage();
    page.on("pageerror", (error) =>
      evidence.page_errors.push({ name: error.name }),
    );
    page.on("response", (response) => {
      const url = new URL(response.url());
      if (
        url.origin === base &&
        url.pathname.startsWith("/api/") &&
        response.status() >= 400 &&
        !(url.pathname === "/api/auth/me" && response.status() === 401)
      )
        evidence.api_failures.push({
          path: url.pathname,
          status: response.status(),
        });
    });
    /** 只等待指定真实方法与路径，避免静态页面HTTP200冒充业务成功。 */
    const responseFor = (pathname, method = "GET") =>
      page.waitForResponse(
        (response) => {
          const url = new URL(response.url());
          return (
            url.origin === base &&
            url.pathname === pathname &&
            response.request().method() === method
          );
        },
        { timeout: 60000 },
      );
    /** 只在登录完成、口令输入已经消失后截图，禁止把凭据录入画面落盘。 */
    const screenshot = async (name) => {
      expect(loggedIn).toBe(true);
      await expect(page.locator('input[name="password"]')).toHaveCount(0);
      await page.screenshot({
        path: path.join(output, name),
        fullPage: true,
        animations: "disabled",
      });
      evidence.screenshots.push(name);
    };
    stage = "login";
    await page.goto(base + "/#/gold", { waitUntil: "domcontentloaded" });
    await page.locator('input[name="username"]').fill("admin");
    await page.locator('input[name="password"]').fill(config.admin_password);
    const initialResponse = responseFor("/api/gold/overview");
    await page.getByRole("button", { name: "进入工作台", exact: true }).click();
    delete config.admin_password;
    const initial = await initialResponse;
    expect(initial.status()).toBe(200);
    const payload = await initial.json();
    await expect(page.locator(".sidebar")).toBeVisible();
    loggedIn = true;
    evidence.data = summary(payload);
    expect(payload.history.length).toBeGreaterThan(0);
    expect(payload.as_of).toBe(payload.history.at(-1).date);
    expect(
      payload.history.every(
        (row) =>
          Number(row.price) > 0 &&
          !/MOCK|DEMO|CSV_UNVERIFIED/.test(row.quality || ""),
      ),
    ).toBe(true);
    evidence.assertions.push(
      "已登录真实API，国内黄金历史非空且日期/单位可追溯",
    );
    await expect(page.getByTestId("gold-data-asof")).toContainText(
      payload.as_of,
    );
    await expect(page.getByRole("img", { name: "研究数据图表" })).toHaveCount(
      1,
    );
    await screenshot("gold-outlook-desktop.png");

    stage = "frozen-predictions";
    if (args.includes("--require-predictions")) {
      expect(payload.predictions.some((row) => row.horizon === 63)).toBe(true);
      expect(payload.predictions.some((row) => row.horizon === 126)).toBe(true);
    }
    if (payload.predictions.length) {
      const first =
        [...payload.predictions]
          .filter((row) => row.horizon === 63)
          .sort((a, b) =>
            String(b.generated_at).localeCompare(String(a.generated_at)),
          )[0] || payload.predictions[0];
      const detailResponse = responseFor(`/api/gold/predictions/${first.id}`);
      await page
        .getByTestId(`gold-prediction-${first.horizon}`)
        .getByRole("button", { name: "查看本条冻结证据" })
        .click();
      const detail = await (await detailResponse).json();
      expect(detail.id).toBe(first.id);
      expect(detail.input_snapshot).toBeTruthy();
      expect(detail.label_version).toBeTruthy();
      expect(detail.feature_version).toBeTruthy();
      expect(detail.code_version).toBeTruthy();
      expect(detail.artifact_sha256).toMatch(/^[a-f0-9]{64}$/);
      await expect(page.locator(".el-drawer")).toContainText(first.model_id);
      await screenshot("gold-frozen-evidence-desktop.png");
      await page
        .locator(".el-drawer")
        .getByRole("button", { name: "关闭此对话框", exact: true })
        .click();
      await expect(page.locator(".el-drawer")).not.toBeVisible();
      evidence.frozen_evidence = {
        id: detail.id,
        model_id: detail.model_id,
        artifact_sha256: detail.artifact_sha256,
        label_version: detail.label_version,
        feature_version: detail.feature_version,
        code_version: detail.code_version,
        fit_at: detail.fit_at,
        generated_at: detail.generated_at,
        target_date_status: detail.target_date_status,
      };
      evidence.assertions.push(
        "精确预测ID读取完整冻结输入、工件摘要和代码/特征/标签版本",
      );
    } else
      evidence.not_applicable.push(
        "真实数据/模型门槛未达到，正式预测保持阻塞空态",
      );

    stage = "scenario-and-validation";
    await page
      .getByRole("tab", { name: "宏观与12个月情景", exact: true })
      .click();
    await expect(page.getByTestId("gold-news-state")).toBeVisible();
    await expect(
      page.getByText("定性条件情景", { exact: true }).first(),
    ).toBeVisible();
    await screenshot("gold-scenarios-desktop.png");
    await page.getByRole("tab", { name: "历史验证", exact: true }).click();
    await expect(page.getByTestId("gold-validation-boundary")).toBeVisible();
    if (payload.models.length) {
      await expect(page.getByTestId("gold-model")).toHaveCount(
        payload.models.length,
      );
      for (const model of payload.models) {
        const candidate = model.metrics?.final_test?.candidate;
        if (candidate && candidate.brier == null)
          evidence.assertions.push(
            `${model.horizon}日最终测试Brier为空，未伪造校准成绩`,
          );
      }
    }
    await screenshot("gold-validation-desktop.png");
    await page
      .getByRole("tab", { name: "数据来源与健康", exact: true })
      .click();
    await expect(page.getByTestId("gold-source")).toHaveCount(
      payload.sources.length,
    );
    await screenshot("gold-sources-desktop.png");
    evidence.assertions.push(
      "宏观情景、历史验证、来源健康分别展示，未触发采集或模型调用",
    );

    stage = "pure-cost-calculation";
    await page.getByRole("tab", { name: "实物成本", exact: true }).click();
    await page.getByRole("textbox", { name: "克数", exact: true }).fill("100");
    await page
      .getByRole("textbox", { name: "实际买入价", exact: true })
      .fill("1020");
    await page
      .getByRole("textbox", { name: "测算基准价格", exact: true })
      .fill("1000");
    await page
      .getByRole("textbox", { name: "回购每克扣减", exact: true })
      .fill("10");
    const costResponse = responseFor("/api/gold/cost", "POST");
    await page.getByRole("button", { name: "计算成本与保本价" }).click();
    const calculated = await costResponse;
    expect(calculated.status()).toBe(200);
    const cost = await calculated.json();
    expect(Number(cost.break_even_benchmark_price)).toBe(1030);
    expect(Number(cost.repurchase_net)).toBe(99000);
    expect(Number(cost.net_profit)).toBe(-3000);
    expect(cost.profit_probability).toBeNull();
    await expect(page.getByTestId("gold-cost-result")).toContainText(
      "1,030.00",
    );
    evidence.cost_test = {
      nature: "SYNTHETIC_ACCEPTANCE_INPUT_NOT_MARKET_DATA",
      break_even_benchmark_price: cost.break_even_benchmark_price,
      repurchase_net: cost.repurchase_net,
      net_profit: cost.net_profit,
      profit_probability: cost.profit_probability,
    };
    evidence.assertions.push(
      "真实Java纯计算API验证1000/1020/10条件下保本1030，无持仓写入",
    );
    await screenshot("gold-cost-desktop.png");

    stage = "mobile";
    await page.setViewportSize({ width: 390, height: 844 });
    for (const [name, suffix] of [
      ["实物成本", "cost"],
      ["走势与预测", "outlook"],
      ["宏观与12个月情景", "scenarios"],
      ["历史验证", "validation"],
      ["数据来源与健康", "sources"],
    ]) {
      await page.getByRole("tab", { name, exact: true }).click();
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      ).toBe(true);
      await screenshot(`gold-${suffix}-mobile.png`);
    }
    evidence.assertions.push("390px手机五标签无页面级横向溢出");

    stage = "etf-regression";
    await page.setViewportSize({ width: 1440, height: 1050 });
    const forwardResponse = responseFor("/api/forward");
    await page.goto(base + "/#/forward");
    const forward = await forwardResponse;
    expect(forward.status()).toBe(200);
    const forwardPayload = await forward.json();
    await expect(page.locator(".page-heading h1")).toHaveText("前瞻交易实验");
    evidence.etf = {
      experiment_id: forwardPayload.experiment?.id,
      accounts: forwardPayload.accounts?.length,
      decisions: forwardPayload.decisions?.length,
    };
    await screenshot("etf-forward-regression.png");
    const systemResponse = responseFor("/api/system");
    await page.goto(base + "/#/system");
    const system = await systemResponse;
    expect(system.status()).toBe(200);
    const systemPayload = await system.json();
    await expect(page.locator(".page-heading h1")).toHaveText("系统管理");
    evidence.system = {
      database: systemPayload.database,
      quant: systemPayload.quant,
      cpu_count: systemPayload.cpu_count,
      daily_limit_usd: systemPayload.llm?.daily_limit_usd,
      jvm_used_mb: systemPayload.jvm_used_mb,
    };
    await screenshot("etf-system-regression.png");
    expect(evidence.prohibited_requests).toEqual([]);
    expect(evidence.allowed_calculations).toBe(1);
    expect(evidence.page_errors).toEqual([]);
    expect(evidence.api_failures).toEqual([]);
    evidence.assertions.push(
      "原ETF前瞻/系统页正常，无JS错误/API失败/禁止的业务写入",
    );
    evidence.success = true;
  } catch (error) {
    evidence.failed_stage = stage;
    evidence.error_type = error.name;
    if (loggedIn && page)
      await page
        .screenshot({ path: path.join(output, "failure.png"), fullPage: true })
        .catch(() => {});
    process.exitCode = 1;
  } finally {
    delete config.admin_password;
    await browser?.close();
    evidence.finished_at = new Date().toISOString();
    fs.writeFileSync(
      path.join(output, "acceptance.json"),
      JSON.stringify(evidence, null, 2),
      { mode: 0o600 },
    );
    console.log(
      JSON.stringify({
        success: evidence.success,
        stage: evidence.failed_stage || "complete",
        assertions: evidence.assertions.length,
        screenshots: evidence.screenshots.length,
        page_errors: evidence.page_errors.length,
        api_failures: evidence.api_failures.length,
        prohibited_requests: evidence.prohibited_requests.length,
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
      error_type: error.name,
    }),
  );
  process.exitCode = 1;
});
