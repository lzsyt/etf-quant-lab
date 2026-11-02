import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "127.0.0.1",
    port: 15173,
    proxy: {
      "/api": process.env.ETF_DEV_API_TARGET || "http://127.0.0.1:18080",
      "/actuator": "http://127.0.0.1:18080",
    },
  },
  build: {
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          charts: ["echarts"],
          ui: ["element-plus"],
          vue: ["vue", "vue-router"],
        },
      },
    },
  },
});
