import { test, expect } from "@playwright/test";

test("管理员生成、关闭和撤销只读令牌", async ({ page }) => {
  let revoked = false;
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    let body: unknown = {};
    if (url.pathname === "/api/auth/me") body = { username: "admin" };
    else if (url.pathname === "/api/auth/csrf")
      body = { header: "X-CSRF-TOKEN", token: "test-csrf" };
    else if (
      url.pathname === "/api/agent-tokens" &&
      route.request().method() === "POST"
    )
      body = { token: "test-only-token" };
    else if (url.pathname.endsWith("/revoke")) {
      revoked = true;
      body = {};
    } else if (url.pathname === "/api/agent-tokens")
      body = [
        {
          id: "test-id",
          name: "研究助手",
          created_at: "2026-10-28T00:00:00Z",
          expires_at: "2099-10-28T00:00:00Z",
          revoked_at: revoked ? "2026-10-28T00:00:00Z" : null,
        },
      ];
    else if (
      url.pathname === "/api/tasks" ||
      url.pathname === "/api/system/audit"
    )
      body = [];
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body),
    });
  });
  await page.goto("/#/system");
  await expect(page.getByText("Agent 访问令牌")).toBeVisible();
  await page.getByPlaceholder("例如：研究助手").fill("研究助手");
  await page.getByRole("button", { name: "生成令牌" }).click();
  await expect(page.getByLabel("新生成的令牌")).toHaveValue("test-only-token");
  await page.getByRole("button", { name: "已保存，关闭令牌" }).click();
  await expect(page.getByLabel("新生成的令牌")).toHaveCount(0);
  await page.getByRole("button", { name: "撤销", exact: true }).click();
  await page.getByRole("button", { name: "确定" }).click();
  await expect(page.getByText("已撤销", { exact: true })).toBeVisible();
});
