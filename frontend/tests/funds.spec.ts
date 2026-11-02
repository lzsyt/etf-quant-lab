import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
const output =
  "/home/developer/.codex/workspaces/etf-quant-lab/output/ledger-retirement-20261029";
const fundNames: Record<string, string> = {
  "460300": "华泰柏瑞沪深300ETF联接A",
  "007169": "易方达中债1–3年国开行债券指数A",
  "008701": "华夏黄金ETF联接A",
};
/** 全部结果是独立接口夹具，用于页面行为验证，不构成真实研究或资金证据。 */
async function fixtures(
  page: Page,
  options: { failNav?: boolean; slowEquity?: boolean } = {},
) {
  const writes: {
    path: string;
    body: any;
    key: string | undefined;
    method: string;
  }[] = [];
  const forecast = (code: string, horizon: number) => ({
    id: `fixture-${code}-${horizon}`,
    fund_code: code,
    nature: "REAL",
    horizon,
    evidence_type: "FORWARD_VALIDATION",
    capability: code === "007169" ? "DIRECTION_ONLY" : "RETURN_POINT",
    target_basis: "NAV_CHANGE",
    anchor_date: "2026-10-27",
    anchor_value: "1.25",
    generated_at: "2026-10-28T08:00:00Z",
    input_cutoff_at: "2026-10-27T15:00:00Z",
    validation_status: "RESEARCH_ONLY",
    model_version_id: "ISOLATED_FIXTURE",
    points: [
      {
        horizon,
        target_date: horizon === 20 ? "2026-11-25" : "2027-01-22",
        point_return: code === "007169" ? null : "0.02",
        probability_up: code === "007169" ? "0.6" : null,
        actual_return: null,
        evaluation_status: "NOT_DUE",
      },
    ],
  });
  await page.route("**/api/**", async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname.slice(4),
      code = path.match(/funds\/(\d+)/)?.[1] || "460300",
      horizon = Number(url.searchParams.get("horizon") || 20);
    if (req.method() !== "GET") {
      const body = req.postDataJSON();
      writes.push({
        path,
        body,
        key: req.headers()["idempotency-key"],
        method: req.method(),
      });
      await route.fulfill({ json: { id: "result-fixture" } });
      return;
    }
    let result: any = {};
    if (path === "/auth/me") result = { username: "admin" };
    else if (path === "/auth/csrf")
      result = { header: "X-CSRF-TOKEN", token: "ISOLATED_FIXTURE_ONLY" };
    else if (path === "/funds")
      result = {
        nature: "REAL",
        funds: Object.entries(fundNames).map(([code, name], i) => ({
          code,
          name,
          asset_class: ["EQUITY", "BOND", "GOLD"][i],
          latest_nav: "1.2500",
          nav_date: "2026-10-27",
          nav_status: i === 2 ? "STALE" : "VALID",
        })),
        tasks: [],
      };
    else if (path.endsWith("/nav")) {
      if (options.failNav) {
        await route.fulfill({
          status: 503,
          json: { message: "上游真实净值暂不可用" },
        });
        return;
      }
      if (options.slowEquity && code === "460300")
        await new Promise((r) => setTimeout(r, 500));
      result = {
        fund_code: code,
        nature: "REAL",
        target_basis: "NAV_CHANGE",
        status: "VALID",
        observations: [
          {
            nav_date: "2026-10-26",
            unit_nav: code === "008701" ? "2.04" : "1.23",
            provider: `ISOLATED_${code}`,
            revision: 1,
            first_observed_at: "2026-10-26T15:00:00Z",
          },
          {
            nav_date: "2026-10-27",
            unit_nav: code === "008701" ? "2.05" : "1.25",
            provider: `ISOLATED_${code}`,
            revision: 1,
            first_observed_at: "2026-10-27T15:00:00Z",
          },
        ],
        warnings: [],
      };
    } else if (path.match(/funds\/\d+\/forecasts$/))
      result = {
        fund_code: code,
        horizon,
        forecasts: [forecast(code, horizon)],
        models: [],
        status: "RESEARCH_ONLY",
      };
    else if (path.startsWith("/forecasts/")) result = forecast("460300", 20);
    else if (path.endsWith("/research-summary"))
      result = {
        fund_code: code,
        forecast: forecast(code, horizon),
        forecast_id: `fixture-${code}`,
        usage: "MODEL_ONLY",
        news: null,
      };
    else if (path.endsWith("/news"))
      result = {
        analysis_status: "INSUFFICIENT_INPUT",
        sentiment_score: null,
        forecast_usage: "DISPLAY_ONLY",
        documents: [
          {
            id: "safe-fixture",
            title: "公开资料标题",
            source_name: "隔离测试资料",
            source_url: "https://example.org/evidence",
            excerpt: "直接来源事实",
            content_type: "SUMMARY",
            published_at: "2026-10-27T10:00:00Z",
            first_seen_at: "2026-10-27T11:00:00Z",
            stance: "POSITIVE",
          },
          {
            id: "unsafe-fixture",
            title: "<img src=x onerror=alert(1)>",
            source_url: "javascript:alert(1)",
            excerpt: "<script>alert(1)</script>",
            content_type: "TITLE",
            stance: "NEGATIVE",
          },
        ],
        reason_codes: ["NEWS_NOT_AVAILABLE"],
      };
    else if (path === "/system/health") result = {};
    else result = [];
    await route.fulfill({ json: result });
  });
  return writes;
}

test("旧书签保留基金上下文，浏览零写入且不再出现第二套全局菜单", async ({
  page,
}) => {
  const writes = await fixtures(page);
  await page.goto("/#/funds/trends");
  await page.getByRole("button", { name: /易方达中债/ }).click();
  await expect(page).toHaveURL(/fund=007169/);
  await expect(
    page.getByText("当前模型只预测方向，不提供价格路径。上涨概率不是涨幅。"),
  ).toBeVisible();
  await page.getByRole("link", { name: "02 分析依据" }).click();
  await expect(
    page.getByRole("button", { name: /易方达中债/ }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByText("最新新闻资料", { exact: true }).click();
  await page.getByText("偏积极", { exact: true }).click();
  await expect(
    page.getByRole("button", { name: "公开资料标题" }),
  ).toBeVisible();
  await expect(page.locator(".research-menu")).toHaveCount(0);
  await expect(
    page.getByRole("navigation", { name: "主导航" }).getByRole("link"),
  ).toHaveCount(3);
  await expect(
    page.getByRole("link", { name: "预测工作台", exact: true }),
  ).toHaveAttribute("href", /asset=007169/);
  expect(writes).toHaveLength(0);
});

test("REAL失败明确报错，不回退演示净值或收益", async ({ page }) => {
  await fixtures(page, { failNav: true });
  await page.goto("/#/funds/trends");
  await expect(page.getByText("上游真实净值暂不可用")).toBeVisible();
  await expect(page.getByText("DEMO · 独立演示，仅供研究")).toHaveCount(0);
  await expect(page.getByText("模拟净值不能作为投资依据")).toHaveCount(0);
});

test("快速基金切换拒绝旧响应，窗口不复用h1/h5", async ({ page }) => {
  await fixtures(page, { slowEquity: true });
  await page.goto("/#/funds/trends");
  await page.getByRole("button", { name: /华夏黄金/ }).click();
  await page.getByText("60 日窗口", { exact: true }).click();
  await expect(
    page.getByText("fixture-008701-60", { exact: false }).first(),
  ).toBeAttached();
  await page.waitForTimeout(650);
  await page.getByText("图表数据表 · 键盘与触摸可读取").click();
  await expect(page.getByText("ISOLATED_008701 / 1").first()).toBeVisible();
  await expect(page.getByText("ISOLATED_460300 / 1")).toHaveCount(0);
});

test("冻结依据不取最新新闻，恶意文本不成为HTML或脚本链接，Esc返回焦点", async ({
  page,
}) => {
  await fixtures(page);
  await page.goto("/#/funds/evidence");
  await expect(page.getByText("本次没有符合条件的冻结资料")).toBeVisible();
  await page.getByText("最新新闻资料", { exact: true }).click();
  const title = page.getByRole("button", {
    name: "<img src=x onerror=alert(1)>",
  });
  await title.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByRole("dialog").locator("img,script")).toHaveCount(0);
  await expect(page.getByText("无可安全打开的来源链接")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(title).toBeFocused();
});

test("旧基金分析待身份核验时明确显示失效原因且不展示有效分数", async ({
  page,
}) => {
  const writes = await fixtures(page);
  await page.route("**/api/funds/007169/news", (route) =>
    route.fulfill({
      json: {
        fund_code: "007169",
        analysis_status: "UNVERIFIED",
        forecast_usage: "NOT_USED",
        sentiment_score: null,
        stance: "UNKNOWN",
        reason_codes: ["FUND_IDENTITY_NOT_VERIFIED"],
        facts: [],
        inferences: [],
        documents: [],
      },
    }),
  );
  await page.goto("/#/funds/evidence?fund=007169");
  await page.getByText("最新新闻资料", { exact: true }).click();
  await expect(page.getByText("旧基金分析尚未通过身份校验")).toBeVisible();
  await expect(page.getByText("待身份核验", { exact: true })).toBeVisible();
  await expect(page.getByText(/新闻分数：\s*—（无有效分数）/)).toBeVisible();
  expect(writes).toEqual([]);
});

for (const width of [1440, 1024, 768, 390, 320])
  test(`${width}px两页可读且无页面横向溢出`, async ({ page }) => {
    await fixtures(page);
    await page.setViewportSize({ width, height: 1000 });
    mkdirSync(output, { recursive: true });
    for (const tab of ["trends", "evidence"]) {
      await page.goto(`/#/funds/${tab}`);
      await expect(page.locator(".fund-cards")).toBeVisible();
      await expect(page.locator(".el-skeleton")).toHaveCount(0);
      const sizes = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        width: innerWidth,
      }));
      expect(sizes.scroll).toBeLessThanOrEqual(sizes.width + 1);
      await page.screenshot({
        path: `${output}/fund-${tab}-${width}.png`,
        fullPage: true,
      });
    }
  });

test("到期追加验证展示实际结果，原冻结预测保持不变", async ({ page }) => {
  await fixtures(page);
  await page.route("**/api/funds/460300/forecasts?*", async (route) =>
    route.fulfill({
      json: {
        fund_code: "460300",
        horizon: 20,
        status: "AVAILABLE",
        forecasts: [
          {
            id: "evaluated-fixture",
            horizon: 20,
            capability: "RETURN_POINT",
            target_basis: "NAV_CHANGE",
            evidence_type: "FORWARD_VALIDATION",
            anchor_date: "2026-09-01",
            anchor_value: "1",
            generated_at: "2026-09-02T08:00:00Z",
            points: [
              {
                horizon: 20,
                target_date: "2026-09-30",
                point_return: "0.02",
                actual_return: null,
                evaluation_status: "NOT_DUE",
              },
            ],
            evaluation: {
              evaluation_status: "EVALUATED",
              actual_return: "0.03",
              actual_value: "1.03",
            },
          },
        ],
        models: [],
      },
    }),
  );
  await page.goto("/#/funds/trends");
  await expect(
    page.getByText("预测 2.00%，实际 3.00%；终点相差 1.00%。"),
  ).toBeVisible();
  await expect(
    page.locator(".fund-table tbody").filter({ hasText: "已验证" }),
  ).toContainText("3.00%");
});

test("键盘可完成导航、数据表及新闻详情，刷新和后退保留上下文", async ({
  page,
}) => {
  const writes = await fixtures(page);
  await page.goto("/#/funds/trends?fund=008701&horizon=60");
  await expect(page.getByRole("button", { name: /华夏黄金/ })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await page.reload();
  await expect(page.getByRole("button", { name: /华夏黄金/ })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  const data = page.getByText("图表数据表 · 键盘与触摸可读取");
  await data.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByText("ISOLATED_008701 / 1").first()).toBeVisible();
  await expect(
    page.getByRole("checkbox", { name: "已验证区间" }),
  ).toBeDisabled();
  const evidence = page.getByRole("link", { name: "02 分析依据" });
  await evidence.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/evidence.*fund=008701/);
  await page.getByText("最新新闻资料", { exact: true }).locator("..").focus();
  await page.getByText("最新新闻资料", { exact: true }).click();
  const news = page.getByRole("button", { name: "公开资料标题" });
  await news.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(news).toBeFocused();
  await page.goBack();
  await expect(page).toHaveURL(/trends.*fund=008701/);
  expect(writes).toHaveLength(0);
});

test("触摸设备可切换图层、读取图表数据和关闭新闻详情", async ({ browser }) => {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    hasTouch: true,
    isMobile: true,
  });
  const page = await context.newPage();
  const writes = await fixtures(page);
  await page.goto("http://127.0.0.1:15173/#/funds/trends");
  await page.getByText("实际走势", { exact: true }).tap();
  await expect(
    page.getByRole("checkbox", { name: "实际走势" }),
  ).not.toBeChecked();
  await page.getByText("图表数据表 · 键盘与触摸可读取").tap();
  await expect(page.getByText("ISOLATED_460300 / 1").first()).toBeVisible();
  await page.getByRole("link", { name: /分析依据$/ }).tap();
  await page.getByText("最新新闻资料", { exact: true }).tap();
  await page.getByRole("button", { name: "公开资料标题" }).tap();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "关闭", exact: true })
    .tap();
  await expect(page.getByRole("dialog")).toBeHidden();
  expect(writes).toHaveLength(0);
  await context.close();
});

test("选择历史批次后分析页读取同一冻结记录，不偷偷改用最新预测", async ({
  page,
}) => {
  await fixtures(page);
  const run = (id: string) => ({
    id,
    fund_code: "460300",
    evidence_type: "FORWARD_VALIDATION",
    generated_at:
      id === "old-freeze" ? "2026-09-01T08:00:00Z" : "2026-10-28T08:00:00Z",
    capability: "RETURN_POINT",
    target_basis: "NAV_CHANGE",
    anchor_date: "2026-10-27",
    anchor_value: "1.25",
    points: [{ horizon: 20, target_date: "2026-11-25", point_return: "0.02" }],
  });
  await page.route("**/api/funds/460300/forecasts?*", async (route) =>
    route.fulfill({
      json: {
        forecasts: [run("latest-freeze"), run("old-freeze")],
        models: [],
      },
    }),
  );
  await page.route("**/api/forecasts/old-freeze", async (route) =>
    route.fulfill({
      json: {
        ...run("old-freeze"),
        news_snapshot: {
          analysis_status: "VALID",
          forecast_usage: "DISPLAY_ONLY",
          documents: [
            {
              id: "old-doc",
              title: "只属于旧批次的冻结来源",
              content_type: "SUMMARY",
              excerpt: "旧快照原文",
            },
          ],
        },
      },
    }),
  );
  await page.goto("/#/funds/trends");
  await page.locator("#fund-run").selectOption("old-freeze");
  await expect(page).toHaveURL(/forecast=old-freeze/);
  await page.getByRole("link", { name: "02 分析依据" }).click();
  await expect(
    page.getByRole("button", { name: "只属于旧批次的冻结来源" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "公开资料标题" })).toHaveCount(
    0,
  );
  await page.getByRole("button", { name: /华夏黄金/ }).click();
  await expect(page).not.toHaveURL(/forecast=/);
});

/** 旧书签回到研究页，页面不再查询或展示任何账本资源。 */
test("账本与定投下线，预测分析仍可用且不再读取资金接口", async ({ page }) => {
  const requests: string[] = [];
  await fixtures(page);
  page.on("request", (request) => requests.push(request.url()));
  await page.goto("/#/funds/ledger");
  await expect(page).toHaveURL(/#\/funds\/trends$/);
  await expect(page.locator(".fund-cards")).toBeVisible();
  await expect(page.getByRole("link", { name: /资金与定投/ })).toHaveCount(0);
  await expect(page.getByText("你的原计划")).toHaveCount(0);
  await page.getByRole("link", { name: "02 分析依据" }).click();
  await expect(page).toHaveURL(/#\/funds\/evidence$/);
  expect(
    requests.filter((url) =>
      /fund-portfolios|fund-transactions|fund-plans|dca-comparisons/.test(url),
    ),
  ).toEqual([]);
});

test("基金旧页按算法标明同期限两种量化预测，历史缺字段仍为LightGBM", async ({
  page,
}) => {
  const writes = await fixtures(page);
  const old = {
    id: "native-lgb-old",
    fund_code: "007169",
    horizon: 60,
    nature: "REAL",
    evidence_type: "FORWARD_VALIDATION",
    capability: "RETURN_POINT",
    target_basis: "NAV_CHANGE",
    generated_at: "2026-10-28T08:00:00Z",
    anchor_value: "1.25",
    model_version_id: "NATIVE-LGB-MODEL",
    points: [
      { horizon: 60, point_return: "0.01", evaluation_status: "NOT_DUE" },
    ],
  };
  const xgb = {
    ...old,
    id: "native-xgb-new",
    algorithm: "xgboost",
    model_version_id: "NATIVE-XGB-MODEL",
    points: [
      { horizon: 60, point_return: "0.02", evaluation_status: "NOT_DUE" },
    ],
  };
  await page.route("**/api/funds/007169/forecasts?*", (route) =>
    route.fulfill({ json: { forecasts: [xgb, old], models: [] } }),
  );
  await page.route("**/api/forecasts/native-*", (route) =>
    route.fulfill({
      json: route.request().url().endsWith("native-xgb-new") ? xgb : old,
    }),
  );
  await page.goto("/#/funds/trends?fund=007169&horizon=60");
  await expect(
    page.getByText("预测个体：基金 XGBoost", { exact: true }),
  ).toBeVisible();
  await expect(
    page.locator("#fund-run option").filter({ hasText: "LightGBM" }),
  ).toHaveCount(1);
  await expect(
    page.locator("#fund-run option").filter({ hasText: "XGBoost" }),
  ).toHaveCount(1);
  await page.locator("#fund-run").selectOption("native-lgb-old");
  await expect(
    page.getByText("预测个体：基金 LightGBM", { exact: true }),
  ).toBeVisible();
  await page.locator("#fund-run").selectOption("native-xgb-new");
  await page.getByRole("link", { name: "02 分析依据" }).click();
  await expect(
    page.getByText("预测个体：基金 XGBoost", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/NATIVE-XGB-MODEL/)).toBeVisible();
  expect(writes).toEqual([]);
});
