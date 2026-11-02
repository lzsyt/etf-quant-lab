import { expect, test, type Page } from "@playwright/test";
import { mkdirSync } from "node:fs";
const evidenceDir =
  process.env.ETF_UI_EVIDENCE_DIR ||
  "/home/developer/.codex/workspaces/etf-quant-lab/output/forecast-v2-ui-20261029";

const fundTarget = {
  id: "FUND_NAV_CHANGE",
  label: "单位净值变化（不含完整分红）",
  horizons: [20, 60],
  horizon_unit: "FUND_NAV_OBSERVATIONS",
  output_type: "RETURN_POINT",
};
const fundShortTarget = {
  id: "FUND_NAV_UP",
  label: "基金短期净值上涨概率",
  horizons: [5],
  horizon_unit: "FUND_NAV_OBSERVATIONS",
  output_type: "PROBABILITY",
};
const goldTarget = {
  id: "GOLD_BENCHMARK_RETURN",
  label: "Au99.99基准收益",
  horizons: [63, 126],
  horizon_unit: "TRADING_DAYS",
  output_type: "RETURN_INTERVAL_PROBABILITY",
};
const forwardTarget = {
  id: "ETF_FORWARD_OPEN",
  label: "下一开盘到再下一开盘",
  horizons: [1],
  horizon_unit: "TRADING_DAYS",
  output_type: "PROBABILITY",
};
const legacyTarget = {
  id: "ETF_LEGACY_CLOSE",
  label: "旧ETF收盘协议",
  horizons: [1, 5],
  horizon_unit: "TRADING_DAYS",
  output_type: "PROBABILITY",
  data_modes: ["REAL", "DEMO"],
};
const etfMediumTarget = {
  id: "ETF_CLOSE_RETURN",
  label: "ETF中期收盘价格变化",
  horizons: [20, 60],
  horizon_unit: "TRADING_DAYS",
  output_type: "RETURN_POINT",
};
const assets = [
  ...[
    ["460300", "华泰柏瑞沪深300ETF联接A"],
    ["007169", "易方达中债1–3年国开行债券指数A"],
    ["008701", "华夏黄金ETF联接A"],
  ].map(([id, name]) => ({
    id,
    code: id,
    name,
    asset_type: "FUND",
    targets: [fundTarget, fundShortTarget],
    data_modes: ["REAL"],
  })),
  ...[
    ["510300", "沪深300ETF"],
    ["510500", "中证500ETF"],
    ["159915", "创业板ETF"],
  ].map(([id, name]) => ({
    id,
    code: id,
    name,
    asset_type: "ETF",
    targets: [forwardTarget, legacyTarget, etfMediumTarget],
    data_modes: ["REAL", "DEMO"],
  })),
  {
    id: "Au99.99",
    code: "Au99.99",
    name: "上金所Au99.99",
    asset_type: "GOLD",
    targets: [goldTarget],
    data_modes: ["REAL"],
  },
];

/** 固定接口夹具只验证浏览器交互，明确不构成线上行情或模型有效性证据。 */
async function fixture(
  page: Page,
  options: { slowFund?: boolean; failRecords?: boolean; empty?: boolean } = {},
) {
  const writes: string[] = [],
    reads: string[] = [];
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname;
    reads.push(url.pathname + url.search);
    if (route.request().method() !== "GET") writes.push(path);
    if (path === "/api/auth/me") {
      await route.fulfill({ json: { username: "admin" } });
      return;
    }
    if (path === "/api/auth/csrf") {
      await route.fulfill({
        json: { header: "X-CSRF-TOKEN", token: "FIXTURE_ONLY" },
      });
      return;
    }
    if (
      ["/api/system/audit", "/api/tasks", "/api/agent-tokens"].includes(path)
    ) {
      await route.fulfill({ json: [] });
      return;
    }
    if (path === "/api/system") {
      await route.fulfill({ json: { database: "UP", quant: "UP", llm: {} } });
      return;
    }
    if (path === "/api/forecast/assets") {
      await route.fulfill({
        json: {
          assets,
          participants: [
            {
              id: "deepseek",
              name: "DeepSeek",
              role: "PREDICTOR",
              status: "INDEPENDENT_FORECAST",
              reason: "统一批次独立读取行情与资讯预测；数据不足可弃权",
            },
            {
              id: "gpt",
              name: "GPT",
              role: "INDEPENDENT",
              status: "NOT_CONNECTED",
              reason: "尚未接入",
            },
          ],
        },
      });
      return;
    }
    const id = url.searchParams.get("asset") || "460300",
      horizon = Number(url.searchParams.get("horizon") || 20);
    const target = url.searchParams.get("target") || "FUND_NAV_CHANGE",
      mode = url.searchParams.get("mode") || "REAL";
    if (path === "/api/forecast/runs") {
      await route.fulfill({
        json: { runs: [], enabled: true, automatic_enabled: false },
      });
      return;
    }
    if (path === "/api/forecast/news") {
      await route.fulfill({
        json: {
          analysis: {
            status: "VALID",
            summary: `最新资料-${id}-${horizon}`,
            generated_at: "2026-10-29T09:00:00Z",
            input_cutoff: "2026-10-29T08:00:00Z",
            documents: [
              {
                id: "FIXTURE-LATEST",
                title: "最新公告夹具",
                excerpt: "仅测试最新资料展示，与历史预测分开。",
              },
            ],
          },
        },
      });
      return;
    }
    const source = ["FUND_NAV_UP", "ETF_CLOSE_RETURN"].includes(target)
      ? "unified"
      : id === "Au99.99"
        ? "gold"
        : id.startsWith("5") || id === "159915"
          ? target === "ETF_LEGACY_CLOSE"
            ? "legacy"
            : "forward"
          : "fund";
    const probabilityTarget = [
      "FUND_NAV_UP",
      "ETF_FORWARD_OPEN",
      "ETF_LEGACY_CLOSE",
    ].includes(target);
    const record = (old = false, scheme = "lightgbm") => ({
      id: `${old ? "old" : "latest"}-${id}-${horizon}`,
      source,
      asset: id,
      target,
      target_version: `${target}_V1`,
      horizon,
      mode,
      scheme,
      model_version: "FIXTURE-v1",
      generated_at: old ? "2026-10-20T08:00:00Z" : "2026-10-28T08:00:00Z",
      data_cutoff: old ? "2026-10-19T15:00:00Z" : "2026-10-27T15:00:00Z",
      anchor_date: "2026-10-27",
      target_date: null,
      output_type: probabilityTarget ? "PROBABILITY" : "RETURN_POINT",
      role: "INDEPENDENT",
      output: probabilityTarget
        ? { probability_up: "0.65" }
        : source === "gold"
          ? {
              point_return: "0.02",
              interval_lower: "970",
              interval_upper: "1020",
              interval_unit: "CNY_PER_GRAM",
            }
          : { point_return: old ? "0.01" : "0.02" },
      status: "FROZEN",
      reason_codes: [],
      evidence_status: "FROZEN",
      evaluation: { status: "PENDING" },
    });
    if (path === "/api/forecast/records") {
      if (options.slowFund && id === "460300")
        await new Promise((resolve) => setTimeout(resolve, 650));
      if (options.failRecords) {
        await route.fulfill({
          status: 503,
          json: { message: "统一预测读取暂不可用" },
        });
        return;
      }
      const rows = options.empty ? [] : [record(), record(true)];
      if (source === "forward")
        rows.push({
          ...record(false, "FUSION_EXPERIMENT"),
          id: "score-fixture",
          output_type: "STRATEGY_SCORE",
          output: { score: "0.27" },
        } as any);
      await route.fulfill({
        json: {
          asset: id,
          target,
          horizon,
          mode,
          records: rows,
          pagination: { limit: 50, offset: 0, has_more: false },
        },
      });
      return;
    }
    if (path.startsWith("/api/forecast/records/")) {
      // 真实详情接口同时校验记录身份与筛选，夹具也应拒绝旧记录配上新资产的请求。
      const requestedId = decodeURIComponent(path.split("/").at(-1) || "");
      if (
        ![`latest-${id}-${horizon}`, `old-${id}-${horizon}`].includes(
          requestedId,
        )
      ) {
        await route.fulfill({
          status: 400,
          json: { message: "预测不存在或不属于当前筛选" },
        });
        return;
      }
      const old = path.includes("old-");
      await route.fulfill({
        json: {
          ...record(old),
          analysis: {
            status: "VALID",
            summary: `${old ? "历史" : "最近"}冻结判断-${id}-${horizon}`,
            positive_factors: ["可追溯的支持因素夹具"],
            mechanisms: ["中性影响机制，不预设利好或利空"],
            counter_factors: ["可追溯的反向因素夹具"],
            uncertainties: ["研究样本有限"],
            documents: [
              {
                id: "FIXTURE-FROZEN",
                title: "冻结公告夹具",
                excerpt: "这段材料来自当时保存的证据。",
              },
            ],
          },
          evidence: {
            snapshot_id: old ? "OLD-FROZEN-EVIDENCE" : "LATEST-FROZEN-EVIDENCE",
            input: "FIXTURE_ONLY",
          },
        },
      });
      return;
    }
    if (path === "/api/forecast/evaluations") {
      await route.fulfill({
        json: {
          asset: id,
          target,
          horizon,
          mode,
          groups: options.empty
            ? []
            : [
                {
                  target_version: `${target}_V1`,
                  output_type: probabilityTarget
                    ? "PROBABILITY"
                    : "RETURN_POINT",
                  matched_samples: 0,
                  recommendation: null,
                  reason: "尚不能判断哪个方案更可靠",
                  individuals: [
                    {
                      scheme: "lightgbm",
                      model_version: "FIXTURE-v1",
                      samples: 2,
                      evaluated_samples: 0,
                      excluded_samples: 2,
                      metrics: {},
                      matched_samples: 0,
                      matched_metrics: {},
                    },
                  ],
                },
              ],
          truncated: false,
        },
      });
      return;
    }
    if (path.endsWith("/nav")) {
      await route.fulfill({
        json: {
          observations: [
            {
              nav_date: "2026-10-23",
              unit_nav: "1.24",
              provider: "FIXTURE_ONLY",
              quality_status: "VALID",
            },
            {
              nav_date: "2026-10-26",
              unit_nav: "1.23",
              provider: "FIXTURE_ONLY",
              quality_status: "VALID",
            },
            {
              nav_date: "2026-10-27",
              unit_nav: "1.25",
              provider: "FIXTURE_ONLY",
              quality_status: "VALID",
            },
          ],
        },
      });
      return;
    }
    if (path === "/api/gold/overview") {
      await route.fulfill({
        json: {
          history: [
            { date: "2026-10-27", price: "980", quality_status: "VALID" },
          ],
        },
      });
      return;
    }
    if (path === "/api/market/bars") {
      await route.fulfill({
        json: [
          {
            trade_date: "2026-10-27",
            close: "4.5",
            source: "FIXTURE_ONLY",
            quality_status: "VALID",
          },
        ],
      });
      return;
    }
    await route.fulfill({
      status: 404,
      json: { message: "此测试未定义该接口" },
    });
  });
  return { writes, reads };
}

test("统一首页覆盖七资产与原生目标期限，主导航保留同一上下文", async ({
  page,
}) => {
  const { writes, reads } = await fixture(page);
  await page.goto("/#/");
  await expect(page).toHaveURL(/#\/forecast$/);
  await expect(
    page.getByRole("navigation", { name: "主导航" }).getByRole("link"),
  ).toHaveCount(3);
  await expect(
    page.getByRole("combobox", { name: "研究资产" }).locator("option"),
  ).toHaveCount(7);
  await page.getByRole("combobox", { name: "研究资产" }).selectOption("007169");
  await page.getByRole("combobox", { name: "预测期限" }).selectOption("60");
  await expect(
    page.getByRole("region", { name: "冻结预测与依据" }),
  ).toContainText("latest-007169-60");
  mkdirSync(evidenceDir, { recursive: true });
  await page.screenshot({
    path: `${evidenceDir}/forecast-desktop-fixture.png`,
    fullPage: true,
  });
  await page.getByRole("link", { name: "预测验证", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "研究资产" })).toHaveValue(
    "007169",
  );
  await expect(page.getByRole("combobox", { name: "预测期限" })).toHaveValue(
    "60",
  );
  await expect(page.getByText("尚不能判断哪个方案更可靠")).toBeVisible();
  await expect(page.getByText("暂无到期评分")).toBeVisible();
  expect(
    reads.some(
      (r) =>
        r.includes("evaluations?") &&
        r.includes("asset=007169") &&
        r.includes("horizon=60"),
    ),
  ).toBeTruthy();
  expect(writes).toEqual([]);
});

test("历史依据与回最新绑定正确，切资产清除旧记录且拒绝迟到响应", async ({
  page,
}) => {
  const { reads, writes } = await fixture(page, { slowFund: true });
  await page.goto("/#/forecast");
  await page.getByRole("combobox", { name: "研究资产" }).selectOption("008701");
  await expect(
    page.getByRole("region", { name: "冻结预测与依据" }),
  ).toContainText("latest-008701-20");
  await page
    .getByRole("combobox", { name: "选择预测记录" })
    .selectOption("fund:old-008701-20");
  await expect(page.locator(".forecast-input-identity")).toContainText(
    "OLD-FROZEN-EVIDENCE",
  );
  await page.getByRole("button", { name: "返回最新", exact: true }).click();
  await expect(page).not.toHaveURL(/record=/);
  await expect(page.locator(".forecast-input-identity")).toContainText(
    "LATEST-FROZEN-EVIDENCE",
  );
  await expect(page.locator(".forecast-input-identity")).not.toContainText(
    "OLD-FROZEN-EVIDENCE",
  );
  await page
    .getByRole("combobox", { name: "研究资产" })
    .selectOption("Au99.99");
  await expect(page.getByRole("combobox", { name: "预测期限" })).toHaveValue(
    "63",
  );
  await expect(
    page.getByRole("region", { name: "冻结预测与依据" }),
  ).toContainText("latest-Au99.99-63");
  await expect(page.locator(".forecast-result")).toContainText(
    "价格区间 970.00 ～ 1,020.00 元/克",
  );
  await expect(page.locator(".forecast-result")).not.toContainText("97000.00%");
  await page.waitForTimeout(750);
  await expect(page.getByRole("combobox", { name: "研究资产" })).toHaveValue(
    "Au99.99",
  );
  expect(reads.some((r) => r.startsWith("/api/forecast/news?"))).toBeTruthy();
  expect(
    reads.some((r) => r.startsWith("/api/funds/") && r.includes("/news")),
  ).toBeFalsy();
  expect(writes).toEqual([]);
});

test("ETF开盘和旧收盘目标分别读取，实验分数不冒充概率", async ({ page }) => {
  await fixture(page);
  await page.goto(
    "/#/forecast?asset=510300&target=ETF_FORWARD_OPEN&horizon=1&mode=REAL",
  );
  await expect(page.getByText("上涨概率 65.00%", { exact: true })).toBeVisible();
  await expect(page.getByText("实验分数 0.270", { exact: true })).toBeVisible();
  await expect(page.getByText("上涨概率 27.00%")).toHaveCount(0);
  await expect(
    page.getByRole("combobox", { name: "数据模式" }).locator("option"),
  ).toHaveCount(1);
  await page
    .getByRole("combobox", { name: "预测目标" })
    .selectOption("ETF_LEGACY_CLOSE");
  await page.getByRole("combobox", { name: "预测期限" }).selectOption("5");
  await page.getByRole("combobox", { name: "数据模式" }).selectOption("DEMO");
  await expect(page).toHaveURL(/target=ETF_LEGACY_CLOSE/);
  await expect(page).toHaveURL(/horizon=5/);
  await expect(
    page.getByRole("region", { name: "统一预测上下文" }),
  ).toContainText("DEMO · 演示数据");
});

test("预测接口失败有明确错误，不展示假零收益或切换演示", async ({ page }) => {
  await fixture(page, { failRecords: true });
  await page.goto("/#/forecast");
  await expect(page.getByText("统一预测读取暂不可用")).toBeVisible();
  await expect(page.getByText("预期收益 0.00%")).toHaveCount(0);
  await expect(page.getByRole("combobox", { name: "数据模式" })).toHaveValue(
    "REAL",
  );
  await expect(
    page.getByText("统一批次独立读取行情与资讯预测；数据不足可弃权"),
  ).toBeVisible();
});

test("空记录、未接入模型和无成熟样本诚实展示", async ({ page }) => {
  await fixture(page, { empty: true });
  await page.goto("/#/forecast");
  await expect(
    page.getByText("尚无该资产、目标与期限的冻结预测"),
  ).toBeVisible();
  await expect(page.locator(".forecast-participants")).toContainText("GPT");
  await expect(page.locator(".forecast-participants")).toContainText(
    "尚未接入",
  );
  await page.getByRole("link", { name: "预测验证", exact: true }).click();
  await expect(
    page.getByText("尚无可评价记录；等待同目标预测与实际结果积累"),
  ).toBeVisible();
});

test("手机布局无横向溢出，刷新和后退保留历史上下文", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await fixture(page);
  await page.goto(
    "/#/forecast?asset=008701&horizon=60&record=fund:old-008701-60",
  );
  await expect(
    page.getByRole("region", { name: "冻结预测与依据" }),
  ).toContainText("old-008701-60");
  await page.reload();
  await expect(page.getByRole("combobox", { name: "研究资产" })).toHaveValue(
    "008701",
  );
  await page
    .getByRole("combobox", { name: "研究资产" })
    .selectOption("Au99.99");
  await page.goBack();
  await expect(page.getByRole("combobox", { name: "研究资产" })).toHaveValue(
    "008701",
  );
  await expect(
    page.getByRole("region", { name: "冻结预测与依据" }),
  ).toContainText("old-008701-60");
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > innerWidth,
  );
  expect(overflow).toBeFalsy();
  mkdirSync(evidenceDir, { recursive: true });
  await page.screenshot({
    path: `${evidenceDir}/forecast-mobile-fixture.png`,
    fullPage: true,
  });
});

test("换代模型按两两共同样本比较，零变化基线和区间单位保持原义", async ({
  page,
}) => {
  await fixture(page);
  await page.route("**/api/forecast/evaluations?**", (route) =>
    route.fulfill({
      json: {
        groups: [
          {
            target_version: "FIXTURE_TARGET_V1",
            output_type: "RETURN_INTERVAL_PROBABILITY",
            matched_samples: 0,
            individuals: [],
            reason: "仅描述已有样本，暂无推荐",
            comparisons: [
              {
                left: {
                  scheme: "lightgbm",
                  model_version: "OLDER-v1",
                  metrics: {
                    mae: "0.01",
                    zero_return_mae: "0.02",
                    interval_coverage: "0.8",
                    interval_samples: 5,
                    interval_mean_width: "40",
                  },
                },
                right: {
                  scheme: "xgboost",
                  model_version: "CURRENT-v2",
                  metrics: { brier: "0.15", direction_accuracy: "0.6" },
                },
                matched_samples: 5,
              },
            ],
          },
        ],
        truncated: false,
      },
    }),
  );
  await page.goto("/#/validation?asset=Au99.99&horizon=63");
  await expect(
    page.getByRole("heading", { name: "不同方案的两两匹配比较" }),
  ).toBeVisible();
  await expect(page.getByText("OLDER-v1")).toBeVisible();
  await expect(page.getByText("CURRENT-v2")).toBeVisible();
  await expect(page.getByText("零变化基线误差 2.00%")).toBeVisible();
  await expect(page.getByText("平均区间宽度 40.00 元/克")).toBeVisible();
  await expect(page.getByText("Brier 0.150")).toBeVisible();
});

test("统一导航移除第二套菜单，页底系统往返保留资产目标和期限", async ({
  page,
}) => {
  const { writes } = await fixture(page);
  await page.goto(
    "/#/forecast?asset=007169&target=FUND_NAV_CHANGE&horizon=60&mode=REAL",
  );
  await expect(
    page.getByRole("navigation", { name: "历史研究工具" }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "数据与任务管理", exact: true }).click();
  await expect(page).toHaveURL(/#\/system\?/);
  await expect(
    page.getByRole("combobox", { name: "运行资产", exact: true }),
  ).toHaveValue("007169");
  await expect(
    page.getByRole("combobox", { name: "运行期限", exact: true }),
  ).toHaveValue("60");
  await expect(
    page.getByRole("button", { name: "执行日终流程", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "提交训练任务", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: "预测工作台", exact: true }).click();
  await expect(
    page.getByRole("combobox", { name: "研究资产", exact: true }),
  ).toHaveValue("007169");
  await expect(
    page.getByRole("combobox", { name: "预测期限", exact: true }),
  ).toHaveValue("60");
  expect(writes).toEqual([]);
});

test("最新资讯与冻结分析在统一页内切换，不替换历史依据或跳旧基金页", async ({
  page,
}) => {
  const { writes } = await fixture(page);
  await page.goto(
    "/#/forecast?asset=007169&target=FUND_NAV_CHANGE&horizon=60&record=fund:old-007169-60",
  );
  await expect(page.getByText("历史冻结判断-007169-60")).toBeVisible();
  await page
    .getByRole("button", { name: "查看当前资产最新资讯", exact: true })
    .click();
  await expect(page).toHaveURL(/#\/forecast\?/);
  await expect(page).toHaveURL(/asset=007169/);
  await expect(page.getByText("最新资料-007169-60")).toBeVisible();
  await expect(page.getByText("历史冻结判断-007169-60")).toHaveCount(0);
  await page.getByRole("button", { name: "本次冻结依据", exact: true }).click();
  await expect(page.getByText("历史冻结判断-007169-60")).toBeVisible();
  await expect(page.getByText("最新资料-007169-60")).toHaveCount(0);
  await expect(page.locator("pre")).toHaveCount(0);
  expect(writes).toEqual([]);
});

test("基金ETF黄金均展示可读冻结判断、反向因素与引用资料", async ({ page }) => {
  await fixture(page);
  for (const [asset, target, horizon] of [
    ["460300", "FUND_NAV_CHANGE", "20"],
    ["510300", "ETF_FORWARD_OPEN", "1"],
    ["510300", "ETF_LEGACY_CLOSE", "5"],
    ["Au99.99", "GOLD_BENCHMARK_RETURN", "63"],
  ]) {
    await page.goto(
      `/#/forecast?asset=${asset}&target=${target}&horizon=${horizon}&view=analysis`,
    );
    await expect(
      page.getByText(`最近冻结判断-${asset}-${horizon}`),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "反向因素", exact: true }),
    ).toBeVisible();
    await expect(page.getByText("可追溯的反向因素夹具")).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "影响机制", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText("中性影响机制，不预设利好或利空"),
    ).toBeVisible();
    await expect(page.getByText("这段材料来自当时保存的证据。")).toBeVisible();
    await expect(page.locator("pre")).toHaveCount(0);
  }
});

test("旧ETF实际结果已存在时明确到期，不能显示等待到期", async ({ page }) => {
  await fixture(page);
  await page.route("**/api/forecast/records/legacy/**", (route) =>
    route.fulfill({
      json: {
        id: "matured-legacy",
        source: "legacy",
        scheme: "lightgbm",
        output_type: "PROBABILITY",
        output: { probability_up: 0.6 },
        generated_at: "2026-10-20T08:00:00Z",
        data_cutoff: "2026-10-20T07:00:00Z",
        actual_return: "0.02",
        actual_target_date: "2026-10-22",
        evaluation: {
          evaluated_at: "2026-10-23T08:00:00Z",
          actual_return: "0.02",
          actual_up: true,
        },
        evidence: {},
      },
    }),
  );
  await page.goto(
    "/#/forecast?asset=510300&target=ETF_LEGACY_CLOSE&horizon=1&record=legacy:matured-legacy&view=analysis",
  );
  const panel = page.getByRole("region", { name: "冻结预测与依据" });
  await expect(panel).toContainText("已到期核验");
  await expect(panel).toContainText("实际结果：2.00%");
  await expect(panel).not.toContainText("等待到期核验");
});

test("显式运行产生单批次，弃权和复用各自显示，读取不会提交模型任务", async ({
  page,
}) => {
  const { writes } = await fixture(page);
  const submitted: any[] = [];
  await page.route("**/api/forecast/runs**", async (route) => {
    if (route.request().method() === "POST") {
      submitted.push(route.request().postDataJSON());
      await route.fulfill({
        json: {
          run_id: "FIXTURE-RUN",
          task_id: "FIXTURE-TASK",
          status: "QUEUED",
        },
      });
    } else
      await route.fulfill({
        json: {
          runs: submitted.length
            ? [
                {
                  id: "FIXTURE-RUN",
                  status: "PARTIAL",
                  created_at: "2026-10-29T08:00:00Z",
                  cutoff_at: "2026-10-29T07:00:00Z",
                  items: [
                    {
                      id: "ITEM-ML",
                      asset: "007169",
                      target: "FUND_NAV_CHANGE",
                      horizon: 60,
                      scheme: "FUND_LIGHTGBM",
                      status: "REUSED",
                      result_id: "RESULT-ML",
                    },
                    {
                      id: "ITEM-DS",
                      asset: "007169",
                      target: "FUND_NAV_CHANGE",
                      horizon: 60,
                      scheme: "DEEPSEEK",
                      status: "ABSTAIN",
                      error_code: "INSUFFICIENT_DATA",
                    },
                  ],
                },
              ]
            : [],
        },
      });
  });
  await page.goto(
    "/#/forecast?asset=007169&target=FUND_NAV_CHANGE&horizon=60&mode=REAL&view=runs",
  );
  expect(submitted).toHaveLength(0);
  await page
    .getByRole("button", { name: "运行当前组合预测", exact: true })
    .click();
  await expect(page.getByText("复用相同输入结果")).toBeVisible();
  await expect(
    page.getByText("信息不足，未给出预测", { exact: true }),
  ).toBeVisible();
  expect(submitted).toHaveLength(1);
  expect(submitted[0]).toMatchObject({
    asset: "007169",
    target: "FUND_NAV_CHANGE",
    horizon: 60,
    mode: "REAL",
  });
  expect(submitted[0].idempotency_key).toMatch(/^[a-f0-9-]{36}$/);
  await page.getByRole("button", { name: "刷新批次状态", exact: true }).click();
  expect(submitted).toHaveLength(1);
  expect(writes).toEqual([]);
});

test("推荐展示分离的选优与后续验证证据，证据不足不伪造冠军", async ({
  page,
}) => {
  await fixture(page);
  await page.route("**/api/forecast/evaluations?**", (route) =>
    route.fulfill({
      json: {
        groups: [
          {
            output_type: "RETURN_POINT",
            target_version: "FIXTURE_V1",
            individuals: [],
            matched_samples: 50,
            recommendation: {
              status: "AVAILABLE",
              scheme: "lightgbm",
              model_version: "FIXTURE-MODEL",
              reason: "仅使用过去区间选优，并在后续留出区间验证优于基线。",
              selection_samples: 30,
              validation_samples: 20,
              samples: 50,
              window: {
                selection_start: "2026-01-01",
                selection_end: "2026-02-15",
                validation_start: "2026-02-16",
                validation_end: "2026-03-16",
              },
            },
          },
        ],
      },
    }),
  );
  await page.goto("/#/validation?asset=007169&horizon=20");
  const panel = page.getByRole("region", { name: "分组推荐依据" });
  await expect(panel).toContainText("已有后续验证的参考方案");
  await expect(panel).toContainText("选优样本 30");
  await expect(panel).toContainText("后续验证样本 20");
  await expect(panel).toContainText("后续验证区间 2026-02-16 至 2026-03-16");
});

test("从演示预测进入系统后仍为只读，不会隐式切换真实付费预测", async ({
  page,
}) => {
  const { writes, reads } = await fixture(page);
  await page.goto(
    "/#/forecast?asset=510300&target=ETF_LEGACY_CLOSE&horizon=5&mode=DEMO",
  );
  await page.getByRole("link", { name: "数据与任务管理", exact: true }).click();
  await expect(
    page.getByRole("combobox", { name: "运行数据模式", exact: true }),
  ).toHaveValue("DEMO");
  await expect(
    page.getByRole("button", { name: "运行当前组合预测", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "核验当前组合到期结果", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "更新当前资产行情", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "准备当前目标量化模型", exact: true }),
  ).toBeDisabled();
  expect(reads.filter((path) => path.startsWith("/api/forecast/runs"))).toEqual(
    [],
  );
  expect(writes).toEqual([]);
});

test("执行覆盖使用各预测个体真实执行项分母，复用与弃权不混入准确率", async ({
  page,
}) => {
  await fixture(page);
  await page.route("**/api/forecast/evaluations?**", (route) =>
    route.fulfill({
      json: {
        groups: [],
        coverage: [
          {
            scheme: "DEEPSEEK",
            planned: 12,
            available: 5,
            reused: 2,
            abstained: 3,
            failed: 1,
            blocked: 2,
            pending: 1,
          },
        ],
      },
    }),
  );
  await page.goto(
    "/#/validation?asset=007169&target=FUND_NAV_CHANGE&horizon=60",
  );
  const panel = page.getByRole("region", { name: "统一预测执行覆盖" });
  await expect(panel).toContainText("不是准确率");
  await expect(panel.locator("tbody tr td")).toHaveText([
    "DeepSeek",
    "12",
    "5",
    "2",
    "3",
    "1",
    "2",
    "1",
  ]);
});

test("全矩阵批次只显示当前组合，排队批次通过只读轮询自动完成", async ({
  page,
}) => {
  const { writes } = await fixture(page);
  let polls = 0;
  await page.route("**/api/forecast/runs**", (route) => {
    polls++;
    return route.fulfill({
      json: {
        enabled: true,
        automatic_enabled: false,
        runs: [
          {
            id: "GLOBAL-FIXTURE",
            status: polls === 1 ? "QUEUED" : "COMPLETED",
            request: { mode: "REAL" },
            items: [
              {
                id: "CURRENT",
                asset: "007169",
                target: "FUND_NAV_CHANGE",
                horizon: 60,
                scheme: "DEEPSEEK",
                status: polls === 1 ? "QUEUED" : "SUCCEEDED",
                result_id: polls === 1 ? null : "fixture-result",
              },
              {
                id: "OTHER",
                asset: "510300",
                target: "ETF_FORWARD_OPEN",
                horizon: 1,
                scheme: "DEEPSEEK",
                status: "FAILED",
                error_code: "OTHER_ASSET_RESULT",
              },
            ],
          },
        ],
      },
    });
  });
  await page.goto(
    "/#/forecast?asset=007169&target=FUND_NAV_CHANGE&horizon=60&mode=REAL&view=runs",
  );
  await expect(page.locator(".forecast-run tbody tr")).toHaveCount(1);
  await expect(page.locator(".forecast-run")).not.toContainText(
    "OTHER_ASSET_RESULT",
  );
  await expect(page.locator(".forecast-run")).toContainText("全部完成", {
    timeout: 10000,
  });
  await expect(
    page.getByRole("link", { name: "查看本次预测与依据" }),
  ).toHaveAttribute("href", /record=unified(:|%3A)fixture-result/);
  expect(polls).toBeGreaterThanOrEqual(2);
  expect(writes).toEqual([]);
});

test("行情更新和模型准备按当前原生目标排队，不隐式运行预测或付费分析", async ({
  page,
}) => {
  const { writes } = await fixture(page);
  const prepared: any[] = [];
  await page.route("**/api/forecast/prepare", (route) => {
    prepared.push(route.request().postDataJSON());
    return route.fulfill({
      json: { id: `PREPARE-${prepared.length}`, status: "QUEUED" },
    });
  });
  for (const [asset, target, horizon] of [
    ["007169", "FUND_NAV_CHANGE", 60],
    ["008701", "FUND_NAV_UP", 5],
    ["510500", "ETF_FORWARD_OPEN", 1],
    ["159915", "ETF_CLOSE_RETURN", 60],
    ["Au99.99", "GOLD_BENCHMARK_RETURN", 126],
  ] as const) {
    await page.goto(
      `/#/forecast?asset=${asset}&target=${target}&horizon=${horizon}&mode=REAL&view=runs`,
    );
    const syncId = prepared.length + 1;
    await page
      .getByRole("button", { name: "更新当前资产行情", exact: true })
      .click();
    await expect(
      page.getByText(
        `已提交行情更新任务 PREPARE-${syncId}，可在系统管理查看任务进度。`,
      ),
    ).toBeVisible();
    expect(prepared.at(-1)).toMatchObject({
      asset,
      target,
      horizon,
      mode: "REAL",
      action: "SYNC",
    });
    const trainId = prepared.length + 1;
    await page
      .getByRole("button", { name: "准备当前目标量化模型", exact: true })
      .click();
    await expect(
      page.getByText(
        `已提交量化模型准备任务 PREPARE-${trainId}，可在系统管理查看任务进度。`,
      ),
    ).toBeVisible();
    expect(prepared.at(-1)).toMatchObject({
      asset,
      target,
      horizon,
      mode: "REAL",
      action: "TRAIN",
    });
  }
  expect(prepared).toHaveLength(10);
  expect(new Set(prepared.map((item) => item.idempotency_key)).size).toBe(10);
  expect(writes).toEqual([]);
});

test("最新独立分析弃权按真实直返合同展示，不回填旧成功结论", async ({
  page,
}) => {
  await fixture(page);
  await page.route("**/api/forecast/news?**", (route) =>
    route.fulfill({
      json: {
        context: "LATEST_INDEPENDENT_FORECAST",
        status: "ABSTAIN",
        execution_status: "ABSTAIN",
        summary: "本次资料不足，无法独立预测",
        reason_codes: ["INSUFFICIENT_DATA"],
        documents: [],
      },
    }),
  );
  await page.goto(
    "/#/forecast?asset=007169&target=FUND_NAV_CHANGE&horizon=60&view=analysis&evidence=latest",
  );
  const panel = page.getByRole("region", { name: "冻结预测与依据" });
  await expect(panel).toContainText("本次资料不足，无法独立预测");
  await expect(panel).toContainText("资料范围：最新独立预测记录");
  await expect(panel).not.toContainText("可追溯的反向因素夹具");
});

test("跨资产进入验证再切换页面时不向新上下文请求旧资产详情", async ({
  page,
}) => {
  const { reads, writes } = await fixture(page);
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.status() >= 400)
      failures.push(new URL(response.url()).pathname);
  });
  await page.goto(
    "/#/forecast?asset=Au99.99&target=GOLD_BENCHMARK_RETURN&horizon=126&mode=REAL&record=gold:old-Au99.99-126&view=analysis",
  );
  await expect(
    page.getByRole("region", { name: "冻结预测与依据" }),
  ).toContainText("old-Au99.99-126");
  await page.goto(
    "/#/validation?asset=007169&target=FUND_NAV_CHANGE&horizon=60&mode=REAL",
  );
  await expect(
    page.getByRole("combobox", { name: "研究资产", exact: true }),
  ).toHaveValue("007169");
  await page.waitForLoadState("networkidle");
  await page.goto(
    "/#/forecast?asset=510300&target=ETF_FORWARD_OPEN&horizon=1&mode=REAL",
  );
  await expect(
    page.getByRole("region", { name: "冻结预测与依据" }),
  ).toContainText("latest-510300-1");
  await page.waitForLoadState("networkidle");
  const mismatches = reads.filter((value) => {
    const url = new URL(value, "http://fixture");
    if (!url.pathname.startsWith("/api/forecast/records/")) return false;
    return !["latest", "old"].some((prefix) =>
      decodeURIComponent(url.pathname).endsWith(
        `${prefix}-${url.searchParams.get("asset")}-${url.searchParams.get("horizon")}`,
      ),
    );
  });
  expect(mismatches).toEqual([]);
  expect(failures).toEqual([]);
  expect(writes).toEqual([]);
});

/** 三个预测个体使用独立身份和结果；缺模型时只阻塞该个体，不复制另一个算法的输出。 */
async function quantVariants(
  page: Page,
  domain: "FUND" | "GOLD" | "FUND_SHORT" | "ETF_MEDIUM",
  missingXgb = false,
) {
  const tracking = await fixture(page);
  const fund = domain === "FUND" || domain === "FUND_SHORT";
  const asset = fund ? "007169" : domain === "GOLD" ? "Au99.99" : "510300";
  const target =
    domain === "FUND_SHORT"
      ? "FUND_NAV_UP"
      : domain === "ETF_MEDIUM"
        ? "ETF_CLOSE_RETURN"
        : domain === "FUND"
          ? "FUND_NAV_CHANGE"
          : "GOLD_BENCHMARK_RETURN";
  const horizon = domain === "FUND_SHORT" ? 5 : domain === "GOLD" ? 126 : 60;
  const schemeDomain = fund ? "FUND" : domain === "GOLD" ? "GOLD" : "CLOSE";
  const outputType = domain === "FUND_SHORT" ? "PROBABILITY" : "RETURN_POINT";
  const selection = { asset, target, horizon, mode: "REAL" };
  const schemes = [
    `${schemeDomain}_LIGHTGBM`,
    `${schemeDomain}_XGBOOST`,
    "DEEPSEEK",
  ];
  const records = schemes
    .filter((scheme) => !missingXgb || !scheme.endsWith("_XGBOOST"))
    .map((scheme) => ({
      ...selection,
      id: `${scheme}-fixture`,
      source: "unified",
      scheme,
      role: "PREDICTOR",
      target_version: "FIXTURE-V1",
      model_version: `${scheme}-MODEL`,
      status: "FROZEN",
      generated_at: "2026-10-30T02:00:00Z",
      data_cutoff: "2026-10-30T01:00:00Z",
      anchor_date: "2026-10-29",
      output_type: outputType,
      probability_validation_status:
        domain === "FUND_SHORT" && scheme !== "DEEPSEEK"
          ? "HISTORICAL_ONLY"
          : null,
      reason_codes:
        domain === "ETF_MEDIUM"
          ? [
              "PRICE_RETURN_NOT_TOTAL_RETURN_OR_EXECUTABLE_PROFIT",
              "HISTORICAL_DATA_REVISIONS_AND_POINT_IN_TIME_AVAILABILITY_UNVERIFIED",
              "CORPORATE_ACTION_COVERAGE_UNVERIFIED",
              "VALIDATION_BASELINES_NOT_BEATEN",
            ]
          : [],
      output:
        domain === "FUND_SHORT"
          ? {
              probability_up: scheme.endsWith("_XGBOOST")
                ? "0.62"
                : scheme === "DEEPSEEK"
                  ? "0.63"
                  : "0.61",
            }
          : {
              point_return: scheme.endsWith("_XGBOOST")
                ? "0.02"
                : scheme === "DEEPSEEK"
                  ? "0.03"
                  : "0.01",
            },
      analysis: {
        status: "QUANT_INPUT_ONLY",
        summary: `${scheme}自身冻结依据夹具`,
        facts: [`模型制品 ${scheme}-MODEL`],
      },
    }));
  await page.route("**/api/forecast/**", async (route) => {
    const url = new URL(route.request().url());
    const endpoint = url.pathname.slice("/api/forecast/".length);
    if (endpoint === "records")
      return route.fulfill({
        json: { ...selection, records, pagination: { has_more: false } },
      });
    if (endpoint.startsWith("records/unified/")) {
      const record = records.find((record) => url.pathname.endsWith(record.id));
      return route.fulfill({
        status: record ? 200 : 400,
        json: record || { message: "不存在的独立预测" },
      });
    }
    if (endpoint === "evaluations")
      return route.fulfill({
        json: {
          coverage: schemes.map((scheme) => ({
            scheme,
            planned: 1,
            available: missingXgb && scheme.endsWith("_XGBOOST") ? 0 : 1,
            reused: 0,
            abstained: 0,
            failed: 0,
            blocked: missingXgb && scheme.endsWith("_XGBOOST") ? 1 : 0,
            pending: 0,
          })),
          groups: [
            {
              output_type: outputType,
              target_version: "FIXTURE-V1",
              matched_samples: 0,
              individuals: records.map((record) => ({
                scheme: record.scheme,
                model_version: record.model_version,
                records: 1,
                eligible_records: 0,
                excluded_records: 1,
                metrics: {},
              })),
              recommendation: {
                status: "INSUFFICIENT_SAMPLES",
                reason: "夹具暂无成熟样本，不推荐",
              },
            },
          ],
        },
      });
    if (endpoint === "runs")
      return route.fulfill({
        json: {
          enabled: true,
          automatic_enabled: false,
          runs: [
            {
              id: "THREE-PREDICTORS",
              status: missingXgb ? "PARTIAL" : "COMPLETED",
              items: schemes.map((scheme) => ({
                ...selection,
                id: scheme,
                scheme,
                status:
                  missingXgb && scheme.endsWith("_XGBOOST")
                    ? "BLOCKED"
                    : "SUCCEEDED",
                result_id:
                  missingXgb && scheme.endsWith("_XGBOOST")
                    ? null
                    : `${scheme}-fixture`,
                error_code:
                  missingXgb && scheme.endsWith("_XGBOOST")
                    ? "MODEL_NOT_READY"
                    : null,
              })),
            },
          ],
        },
      });
    return route.fallback();
  });
  return {
    ...tracking,
    query: new URLSearchParams({
      ...selection,
      horizon: String(horizon),
    }).toString(),
  };
}

test("六只基金新增目标使用各自期限与输出，切换后冻结记录和验证保持同一组合", async ({
  page,
}) => {
  const { writes, reads } = await fixture(page);
  for (const [asset, target, horizon] of [
    ...["460300", "007169", "008701"].map((id) => [id, "FUND_NAV_UP", 5]),
    ...["510300", "510500", "159915"].flatMap((id) => [
      [id, "ETF_CLOSE_RETURN", 20],
      [id, "ETF_CLOSE_RETURN", 60],
    ]),
  ]) {
    const probability = target === "FUND_NAV_UP";
    await page.goto(
      `/#/forecast?asset=${asset}&target=${target}&horizon=${horizon}&mode=REAL`,
    );
    await expect(
      page.getByRole("combobox", { name: "预测目标", exact: true }),
    ).toHaveValue(String(target));
    await expect(
      page.getByRole("combobox", { name: "预测期限", exact: true }),
    ).toHaveValue(String(horizon));
    await expect(
      page.getByRole("combobox", { name: "数据模式", exact: true }),
    ).toHaveValue("REAL");
    await expect(page.locator(".forecast-result")).toContainText(
      probability ? "上涨概率 65.00%" : "预测价格变化 2.00%",
    );
    await expect(page.locator(".forecast-result")).not.toContainText(
      probability ? "预测收益" : "上涨概率",
    );
    await expect(page.getByLabel("预测目标口径")).toContainText(
      probability ? "有效观测数不是自然日" : `第${horizon}个交易日收盘价格变化`,
    );
    await page
      .getByRole("combobox", { name: "选择预测记录", exact: true })
      .selectOption(`unified:old-${asset}-${horizon}`);
    await expect(
      page.getByRole("region", { name: "冻结预测与依据" }),
    ).toContainText(`历史冻结判断-${asset}-${horizon}`);
    await page
      .getByRole("navigation", { name: "主导航" })
      .getByRole("link", { name: "预测验证", exact: true })
      .click();
    await expect(page).toHaveURL(new RegExp(`target=${target}`));
    await expect(page).toHaveURL(new RegExp(`horizon=${horizon}`));
    await expect(page.locator(".forecast-evaluation-group")).toContainText(
      probability ? "上涨概率" : "收益终点",
    );
    expect(
      reads.some(
        (url) =>
          url.includes(`/forecast/records/unified/old-${asset}-${horizon}`) &&
          url.includes(`target=${target}`),
      ),
    ).toBeTruthy();
  }
  expect(writes).toEqual([]);
});

for (const domain of ["FUND_SHORT", "ETF_MEDIUM"] as const) {
  test(`${domain}三预测个体分别展示结果和依据，缺模型仅阻塞对应个体`, async ({
    page,
  }) => {
    const { query, writes } = await quantVariants(page, domain, true);
    const prefix = domain === "FUND_SHORT" ? "基金" : "ETF";
    await page.goto("/#/forecast?" + query + "&view=runs");
    const items = page.locator(".forecast-run tbody tr");
    await expect(items).toHaveCount(3);
    await expect(items.filter({ hasText: `${prefix} XGBoost` })).toContainText(
      "尚无可用量化模型",
    );
    await expect(items.filter({ hasText: `${prefix} LightGBM` })).toContainText(
      "已生成预测",
    );
    await expect(items.filter({ hasText: "DeepSeek" })).toContainText(
      "已生成预测",
    );
    await page.getByRole("button", { name: "走势与预测", exact: true }).click();
    const cards = page.locator(".forecast-result");
    await expect(cards).toHaveCount(2);
    await expect(cards.filter({ hasText: `${prefix} LightGBM` })).toContainText(
      domain === "FUND_SHORT" ? "上涨概率 61.00%" : "预测价格变化 1.00%",
    );
    if (domain === "FUND_SHORT")
      await expect(
        cards.filter({ hasText: `${prefix} LightGBM` }),
      ).toContainText("仅完成历史检验，待前瞻验证");
    else
      for (const note of [
        "仅研究价格变化，不含完整分红总回报，也不代表实际成交收益",
        "历史行情修订及当时可得性尚未完整核验",
        "分红、拆分等公司行动覆盖尚未完整核验",
        "验证阶段尚未优于简单基线",
      ])
        await expect(
          cards.filter({ hasText: `${prefix} LightGBM` }),
        ).toContainText(note);
    await cards
      .filter({ hasText: "DeepSeek" })
      .getByRole("button", { name: "查看冻结依据" })
      .click();
    await expect(
      page.getByRole("region", { name: "冻结预测与依据" }),
    ).toContainText("DEEPSEEK自身冻结依据夹具");
    await page.goto("/#/validation?" + query);
    const coverage = page
      .getByRole("region", { name: "统一预测执行覆盖" })
      .locator("tbody tr")
      .filter({ hasText: `${prefix} XGBoost` });
    await expect(coverage.locator("td")).toHaveText([
      `${prefix} XGBoost`,
      "1",
      "0",
      "0",
      "0",
      "0",
      "1",
      "0",
    ]);
    expect(writes).toEqual([]);
  });
}

test("ETF中期跨年目标日期待确认时保留60交易日口径，不补造日期", async ({
  page,
}) => {
  const { query, writes } = await quantVariants(page, "ETF_MEDIUM");
  await page.route("**/api/forecast/records/unified/**", async (route) => {
    const url = new URL(route.request().url());
    return route.fulfill({
      json: {
        id: url.pathname.split("/").at(-1),
        source: "unified",
        asset: "510300",
        target: "ETF_CLOSE_RETURN",
        horizon: 60,
        mode: "REAL",
        scheme: "CLOSE_LIGHTGBM",
        anchor_date: "2026-10-29",
        target_date: null,
        calendar_status: "UNCONFIRMED",
        output_type: "RETURN_POINT",
        output: { point_return: "0.01" },
        reason_codes: ["FUTURE_CALENDAR_UNAVAILABLE"],
      },
    });
  });
  await page.goto("/#/forecast?" + query + "&view=analysis");
  const detail = page.getByRole("region", { name: "冻结预测与依据" });
  await expect(detail).toContainText("第60个交易日，日期待交易日历确认");
  await expect(detail).toContainText(
    "目标日期待交易日历确认，仍按既定交易日数量研究",
  );
  await expect(
    page.getByRole("combobox", { name: "预测期限", exact: true }),
  ).toHaveValue("60");
  expect(writes).toEqual([]);
});

for (const domain of ["FUND", "GOLD"] as const) {
  test(`${domain}同期限LightGBM、XGBoost与DeepSeek分开展示冻结依据和评价`, async ({
    page,
  }) => {
    const { query, writes } = await quantVariants(page, domain);
    const prefix = domain === "FUND" ? "基金" : "黄金";
    await page.goto("/#/forecast?" + query);
    await expect(page.locator(".forecast-result")).toHaveCount(3);
    const lgb = page.locator(".forecast-result").filter({
      has: page.getByRole("heading", {
        name: `${prefix} LightGBM`,
        exact: true,
      }),
    });
    const xgb = page.locator(".forecast-result").filter({
      has: page.getByRole("heading", {
        name: `${prefix} XGBoost`,
        exact: true,
      }),
    });
    await expect(lgb).toContainText("1.00%");
    await expect(xgb).toContainText("2.00%");
    await xgb.getByRole("button", { name: "查看冻结依据" }).click();
    const evidence = page.getByRole("region", { name: "冻结预测与依据" });
    await expect(evidence).toContainText(`${domain}_XGBOOST自身冻结依据夹具`);
    await expect(evidence).not.toContainText(
      `${domain}_LIGHTGBM自身冻结依据夹具`,
    );
    await page
      .getByRole("navigation", { name: "主导航" })
      .getByRole("link", { name: "预测验证", exact: true })
      .click();
    const coverage = page.getByRole("region", { name: "统一预测执行覆盖" });
    await expect(coverage.locator("tbody tr")).toHaveCount(3);
    await expect(coverage).toContainText(`${prefix} LightGBM`);
    await expect(coverage).toContainText(`${prefix} XGBoost`);
    await expect(page.locator(".forecast-evaluation-group")).toContainText(
      `${domain}_XGBOOST-MODEL`,
    );
    expect(writes).toEqual([]);
  });
}

test("基金与黄金缺XGBoost模型时独立阻塞，其他个体结果仍保留", async ({
  page,
}) => {
  for (const domain of ["FUND", "GOLD"] as const) {
    const { query, writes } = await quantVariants(page, domain, true);
    const prefix = domain === "FUND" ? "基金" : "黄金";
    await page.goto("/#/forecast?" + query + "&view=runs");
    await expect(page.locator(".forecast-run tbody tr")).toHaveCount(3);
    const xgb = page
      .locator(".forecast-run tbody tr")
      .filter({ hasText: `${prefix} XGBoost` });
    await expect(xgb).toContainText("暂不可执行");
    await expect(xgb).toContainText("尚无可用量化模型，请先准备模型");
    await expect(xgb.getByRole("link")).toHaveCount(0);
    await expect(
      page
        .locator(".forecast-run tbody tr")
        .filter({ hasText: `${prefix} LightGBM` }),
    ).toContainText("已生成预测");
    await page.getByRole("button", { name: "走势与预测", exact: true }).click();
    await expect(page.locator(".forecast-result")).toHaveCount(2);
    await expect(
      page.locator(".forecast-result").filter({ hasText: `${prefix} XGBoost` }),
    ).toHaveCount(0);
    await page.goto("/#/validation?" + query);
    const coverage = page
      .getByRole("region", { name: "统一预测执行覆盖" })
      .locator("tbody tr")
      .filter({ hasText: `${prefix} XGBoost` });
    await expect(coverage.locator("td")).toHaveText([
      `${prefix} XGBoost`,
      "1",
      "0",
      "0",
      "0",
      "0",
      "1",
      "0",
    ]);
    expect(writes).toEqual([]);
  }
});

/** 分歧功能关闭后仍可读历史说明，独立预测保持原角色且只读操作不触发付费。 */
for (const width of [1280, 390]) {
  test(`GPT独立预测与已停用的历史分歧分析分开显示 ${width}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 920 });
    const { writes } = await fixture(page);
    const selection = {
      asset: "460300",
      target: "FUND_NAV_CHANGE",
      horizon: 20,
      mode: "REAL",
    };
    const base = {
      ...selection,
      source: "unified",
      role: "PREDICTOR",
      status: "FROZEN",
      generated_at: "2026-10-31T08:10:00Z",
      data_cutoff: "2026-10-31T08:00:00Z",
      target_version: "FIXTURE-V1",
      model_version: "GPT-FIXTURE",
      output_type: "RETURN_POINT",
      output: { point_return: "-0.01" },
      evaluation_eligible: true,
      analysis: { status: "VALID", summary: "独立读取行情资讯的测试判断" },
    };
    const records = [
      { ...base, id: "gpt-independent-fixture", scheme: "GPT" },
      {
        ...base,
        id: "gpt-review-fixture",
        scheme: "GPT_DISAGREEMENT",
        role: "REVIEW",
        status: "ANALYZED",
        output_type: "DISAGREEMENT_ANALYSIS",
        output: {},
        evaluation_eligible: false,
        evaluation_status: "NOT_APPLICABLE",
        analysis: {
          status: "VALID",
          context: "DISAGREEMENT_ANALYSIS",
          summary: "方向存在分歧，现有证据不能判定谁更准确",
          facts: ["一个模型负向，另一个正向"],
          inferences: ["窗口差异仅为待核实假设"],
          predictions: [
            {
              id: "gpt-independent-fixture",
              scheme: "GPT",
              generated_at: base.generated_at,
            },
            {
              id: "ds-basis-fixture",
              scheme: "DEEPSEEK",
              generated_at: base.generated_at,
            },
          ],
        },
      },
    ];
    await page.route("**/api/forecast/records**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/forecast/records")
        return route.fulfill({
          json: { ...selection, records, pagination: { has_more: false } },
        });
      return route.fulfill({ json: records.find((r) => path.endsWith(r.id)) });
    });
    await page.goto(
      "/#/forecast?" + new URLSearchParams({ ...selection, horizon: "20" }),
    );
    const cards = page.locator(".forecast-result");
    await expect(cards).toHaveCount(2);
    await expect(cards.filter({ hasText: "GPT 独立预测" })).toContainText(
      "预测净值变化 -1.00%",
    );
    const review = cards.filter({ hasText: "GPT 分歧分析" });
    await expect(review).toContainText("历史记录，已停用");
    await expect(review).toContainText("分歧研究，不参与预测排名");
    await expect(review).not.toContainText("上涨概率");
    await expect(review).not.toContainText("预测收益");
    await review.getByRole("button", { name: "查看冻结依据" }).click();
    const basis = page.getByRole("region", { name: "分歧分析所引用预测" });
    await expect(basis).toContainText("ds-basis-fixture");
    await expect(basis).toContainText("不构成综合预测");
    await page.reload();
    await expect(basis).toContainText("gpt-independent-fixture");
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBeTruthy();
    expect(writes).toEqual([]);
    mkdirSync(evidenceDir, { recursive: true });
    await page.screenshot({
      path: `${evidenceDir}/gpt-review-${width}.png`,
      fullPage: true,
    });
  });
}
