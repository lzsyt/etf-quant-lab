import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  // 固定接口夹具仅由 playwright.ui.config.ts 运行，避免混入真实接口验收。
  testIgnore: [
    "v11-ui.spec.ts",
    "forward.spec.ts",
    "intelligence.spec.ts",
    "gold.spec.ts",
  ],
  timeout: 300000,
  workers: 1,
  use: {
    baseURL: process.env.ETF_E2E_URL || "http://127.0.0.1:18080",
    launchOptions: {
      executablePath: process.env.ETF_CHROMIUM_PATH || undefined,
    },
    screenshot: "only-on-failure",
    trace: "off",
    video: "off",
  },
  reporter: [["list"], ["html", { open: "never" }]],
});
