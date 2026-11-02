import { defineConfig } from "@playwright/test";

// 此配置只运行固定接口夹具的浏览器语义测试，独立于真实API与生产验收配置。
export default defineConfig({
  testDir: "./tests",
  testMatch: [
    "v11-ui.spec.ts",
    "forward.spec.ts",
    "intelligence.spec.ts",
    "gold.spec.ts",
    "agent-token.spec.ts",
    "funds.spec.ts",
    "forecast.spec.ts",
  ],
  timeout: 30000,
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:15173",
    launchOptions: {
      executablePath: process.env.ETF_CHROMIUM_PATH || undefined,
    },
    screenshot: "only-on-failure",
    trace: "off",
    video: "off",
  },
  webServer: {
    command: "npm run dev -- --port 15173",
    url: "http://127.0.0.1:15173",
    reuseExistingServer: true,
    timeout: 30000,
  },
  reporter: [["list"]],
});
