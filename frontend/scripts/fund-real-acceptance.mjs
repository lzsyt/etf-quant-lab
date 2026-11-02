import { chromium } from "playwright";
import { readFileSync, writeFileSync } from "node:fs";
const base = process.env.ETF_ACCEPTANCE_UI || "http://127.0.0.1:15174";
if (!/^http:\/\/127\.0\.0\.1:(15174|18180)$/.test(base))
  throw new Error("仅允许明确的本机隔离验收端口");
const out =
  "/home/developer/.codex/workspaces/etf-quant-lab/output/fund-dca-20261028";
const password = JSON.parse(
  readFileSync("../.runtime/secrets.json", "utf8"),
).admin_password;
const browser = await chromium.launch({
  executablePath:
    "/home/developer/.cache/ms-playwright/chromium-1237/chrome-linux64/chrome",
});
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const jsErrors = [],
  errors = [];
page.on("pageerror", (e) => jsErrors.push(e.message));
page.on("response", (r) => {
  if (r.status() >= 400 && !r.url().includes("/auth/me"))
    errors.push({ path: new URL(r.url()).pathname, status: r.status() });
});
const checks = [];
const field = async (label, value) => {
  const scope = page.getByRole("dialog").last();
  await scope
    .locator(".el-form-item")
    .filter({ has: page.locator("label", { hasText: label }) })
    .locator("input")
    .first()
    .fill(value);
};
const save = async () => {
  await page
    .getByRole("dialog")
    .last()
    .getByRole("button", { name: "确认保存记录", exact: true })
    .click();
  await page.getByRole("dialog").last().waitFor({ state: "hidden" });
  await page.locator(".el-skeleton").waitFor({ state: "hidden" });
};
try {
  await page.goto(base + "/#/funds/trends");
  await page.locator("input[name=username]").fill("fund-acceptance");
  await page.locator("input[name=password]").fill(password);
  await page.getByRole("button", { name: "进入工作台" }).click();
  await page.locator(".fund-cards").waitFor();
  await page.locator(".el-skeleton").waitFor({ state: "hidden" });
  checks.push("真实浏览器会话登录及三基金目录");
  await page.getByRole("link", { name: "03 资金与定投" }).click();
  await page.locator(".el-skeleton").waitFor({ state: "hidden" });
  let accountId;
  if (process.env.ETF_ACCEPTANCE_READ_ONLY !== "true") {
    await page
      .getByRole("button", { name: "建立独立账本", exact: true })
      .click();
    await field("账本名称", "UI验收专用手工账本（隔离测试数据）");
    await save();
    accountId = await page.locator("#fund-account").inputValue();
    checks.push("显式初始化账户与三笔待确认草稿");
    await page.getByRole("button", { name: "记录资金", exact: true }).click();
    await field("金额（元）", "0.00");
    await field("凭据 / 资金来源说明", "ISOLATED_ACCEPTANCE_INITIAL_ZERO");
    await save();
    checks.push("期初零现金显式核实");
    const row = page
      .locator("tr")
      .filter({ hasText: "华泰柏瑞沪深300ETF联接A" })
      .filter({ has: page.getByRole("button", { name: "登记已提交" }) })
      .first();
    await row.getByRole("button", { name: "登记已提交" }).click();
    await field("提交凭据说明", "ISOLATED_ACCEPTANCE_EXTERNAL_1000");
    await save();
    checks.push("外部申购提交，资金在途");
    await page
      .getByRole("button", { name: "确认份额", exact: true })
      .first()
      .click();
    await field("确认成交净值", "1.2500");
    await field("确认份额", "799.2000");
    await field("实际费用", "1.00");
    await field("确认单来源", "ISOLATED_ACCEPTANCE_NOT_USER_TRADE");
    await save();
    checks.push("真实表单799.2000份/1.2500净值/1元费用确认");
    await page
      .getByRole("textbox", { name: "月预算", exact: true })
      .fill("1000.00");
    await page.getByRole("button", { name: "保存预算配置" }).click();
    await page.getByText("预算配置已保存，资金与份额没有变化").waitFor();
    await page.getByRole("button", { name: "生成月度草稿" }).click();
    await field("计划月份", "2026-11");
    await save();
    checks.push("1000元预算及2026-11名义15日草稿");
  } else {
    const accounts = await (
      await page.request.get(`${base}/api/fund-portfolios`)
    ).json();
    accountId = accounts.find(
      (a) => a.name === "UI验收专用手工账本（隔离测试数据）",
    )?.id;
    if (!accountId) throw new Error("只读验收未找到原测试账户");
    await page.locator("#fund-account").selectOption(accountId);
    await page.locator(".el-skeleton").waitFor({ state: "hidden" });
    checks.push("只读复核先前已完成的实际表单记账闭环");
  }
  const policyResponse = await page.request.get(
    `${base}/api/fund-portfolios/${accountId}/summary`,
  );
  const summary = await policyResponse.json();
  if (summary.transactions.filter((t) => t.status === "CONFIRMED").length !== 1)
    throw new Error("确认笔数错误");
  const confirmed = summary.positions.find((p) => p.fund_code === "460300");
  if (Number(confirmed.confirmed_units) !== 799.2)
    throw new Error("确认份额不符");
  if (summary.external_contributions !== "1000.00")
    throw new Error("预算被误算为外部入金");
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 1000 });
    for (const tab of ["trends", "evidence", "ledger"]) {
      await page.goto(`${base}/#/funds/${tab}`);
      await page.locator(".fund-cards").waitFor();
      await page.locator(".el-skeleton").waitFor({ state: "hidden" });
      await page.waitForTimeout(150);
      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth + 1,
      );
      if (overflow) throw new Error(`${width}/${tab} 页面横向溢出`);
      await page.screenshot({
        path: `${out}/real-fund-${tab}-${width}.png`,
        fullPage: true,
      });
    }
  }
  checks.push("真实API三页/1440、390、320无横向溢出");
  writeFileSync(
    `${out}/real-ui-verification.json`,
    JSON.stringify(
      {
        base,
        accountId,
        checks,
        jsErrors,
        httpErrors: errors,
        summary: {
          confirmed_units: confirmed.confirmed_units,
          external_contributions: summary.external_contributions,
          total_assets: summary.total_assets,
          valuation_coverage: summary.valuation_coverage,
        },
        note: "所有账户与交易均在etf_fund_acceptance_20261028内，属于隔离验收夹具，不是用户真实购买或投资收益",
      },
      null,
      2,
    ),
  );
  console.log(
    JSON.stringify({ checks, jsErrors, httpErrors: errors, accountId }),
  );
} catch (e) {
  console.error(e.message);
  console.log(JSON.stringify({ checks, jsErrors, httpErrors: errors }));
  if (await page.locator(".fund-cards").count())
    await page
      .screenshot({ path: `${out}/real-ui-failure.png`, fullPage: true })
      .catch(() => {});
  process.exitCode = 1;
} finally {
  await browser.close();
}
