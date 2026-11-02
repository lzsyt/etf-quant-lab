import { test, expect, Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

async function signIn(page: Page) {
  // 密码只从受限配置读取进入浏览器会话，不录制trace或视频，不写入断言消息。
  const secrets = JSON.parse(
    readFileSync(
      process.env.ETF_E2E_CONFIG || resolve("../.runtime/secrets.json"),
      "utf8",
    ),
  );
  await page.goto("/");
  await page.locator('input[name="username"]').fill("admin");
  await page.locator('input[name="password"]').fill(secrets.admin_password);
  await page.getByRole("button", { name: "进入工作台" }).click();
  await expect(page.locator(".sidebar")).toBeVisible();
}

test("八页实际API、图表和手机布局", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await signIn(page);
  const failures: string[] = [];
  page.on("response", (response) => {
    if (response.url().includes("/api/") && response.status() >= 400)
      failures.push(`${response.status()} ${new URL(response.url()).pathname}`);
  });
  for (const [path, title] of [
    ["/", "研究总览"],
    ["/market", "ETF行情中心"],
    ["/predictions", "AI预测中心"],
    ["/backtests", "策略回测中心"],
    ["/paper", "模拟交易中心"],
    ["/rankings", "模型与策略比较"],
    ["/reports", "每日量化研究报告"],
    ["/system", "系统管理"],
  ]) {
    await page.goto("/#" + path);
    await expect(page.locator(".page-heading h1")).toHaveText(title);
    await page.waitForTimeout(700);
    await expect(page.locator(".el-message--error")).toHaveCount(0);
  }
  await page.goto("/#/market");
  await expect(
    page.locator('[aria-label="研究数据图表"] svg').first(),
  ).toBeVisible();
  await page.screenshot({
    path: "../.runtime/evidence/ui-market-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/#/");
  await expect(page.locator(".stats .stat")).toHaveCount(4);
  await page.waitForTimeout(500);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(391);
  await page.screenshot({
    path: "../.runtime/evidence/ui-dashboard-mobile.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
  expect(failures).toEqual([]);
});

test("回测提交并展示实际运行结果", async ({ page }) => {
  test.skip(
    process.env.ETF_E2E_READ_ONLY === "1",
    "当前仅执行真实接口只读验收，不创建新回测",
  );
  await signIn(page);
  await page.goto("/#/backtests");
  await expect(page.getByRole("button", { name: "启动回测" })).toBeVisible();
  await page.getByRole("button", { name: "启动回测" }).click();
  await expect(page.getByText("回测完成", { exact: true })).toBeVisible({
    timeout: 240000,
  });
  await expect(
    page.locator('[aria-label="研究数据图表"] svg').first(),
  ).toBeVisible();
  await expect(page.locator(".el-message--error")).toHaveCount(0);
  await page.screenshot({
    path: "../.runtime/evidence/ui-backtest-desktop.png",
    fullPage: true,
  });
});

test("退出后业务API拒绝访问", async ({ page }) => {
  await signIn(page);
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(
    page.getByRole("heading", { name: "登录研究平台" }),
  ).toBeVisible();
  const response = await page.request.get("/api/paper/accounts");
  expect(response.status()).toBe(401);
});
