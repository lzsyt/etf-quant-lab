<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { api, changePassword, guarded, login, logout, signedIn } from "./api";
const route = useRoute();
const router = useRouter();
const mainItems = [
  ["/forecast", "预测工作台", "01"],
  ["/validation", "预测验证", "02"],
  ["/system", "系统管理", "03"],
];
const username = ref("admin"),
  password = ref(""),
  busy = ref(false),
  ready = ref(false);
const changingPassword = ref(false),
  savingPassword = ref(false);
const passwordForm = reactive({ current: "", next: "", confirmation: "" });

/** 关闭弹窗后清除敏感输入，避免下一次操作复用旧密码。 */
function clearPasswordForm() {
  passwordForm.current = "";
  passwordForm.next = "";
  passwordForm.confirmation = "";
}

/** 先检查两次输入，再交给服务端验证当前密码并使旧会话失效。 */
async function savePassword() {
  if (passwordForm.next !== passwordForm.confirmation) {
    ElMessage.error("两次输入的新密码不一致");
    return;
  }
  if (passwordForm.next.length < 8 || passwordForm.next.length > 64) {
    ElMessage.error("新密码需为8至64个字符");
    return;
  }
  savingPassword.value = true;
  await guarded(async () => {
    await changePassword(
      passwordForm.current,
      passwordForm.next,
      passwordForm.confirmation,
    );
    changingPassword.value = false;
    clearPasswordForm();
    ElMessage.success("密码已修改，请使用新密码重新登录");
  });
  savingPassword.value = false;
}
/** 统一工作台与验证页共享筛选；系统页往返也保留已选择的资产。 */
function navigation(path: string) {
  const query = route.path.startsWith("/funds/")
    ? {
        asset: route.query.fund || "460300",
        target: "FUND_NAV_CHANGE",
        horizon: route.query.horizon || "20",
        mode: "REAL",
      }
    : route.path === "/gold" && !route.query.asset
      ? {
          asset: "Au99.99",
          target: "GOLD_BENCHMARK_RETURN",
          horizon: route.query.horizon || "63",
          mode: "REAL",
        }
      : route.query;
  return { path, query };
}
onMounted(async () => {
  try {
    await api("/auth/me");
    signedIn.value = true;
  } catch {
    signedIn.value = false;
  } finally {
    ready.value = true;
  }
});
async function signIn() {
  busy.value = true;
  await guarded(async () => {
    await login(username.value, password.value);
    password.value = "";
    await router.push("/forecast");
  });
  busy.value = false;
}
</script>
<template>
  <div v-if="!ready" class="loading-screen">正在建立安全会话…</div>
  <main v-else-if="!signedIn" class="login-page">
    <div class="login-intro">
      <div class="brand-mark">Q<span>·</span></div>
      <div class="eyebrow">ETF QUANT LAB / V1.1</div>
      <h1>让预测<br />接受时间的检验。</h1>
      <p>
        研究基金的走势、风险与分析依据。<br />用可复现的实验，验证AI的真实价值。
      </p>
      <div class="login-note">预测与分析研究，不执行真实交易</div>
    </div>
    <section class="login-card">
      <div class="eyebrow">RESEARCH CONSOLE</div>
      <h2>登录研究平台</h2>
      <p class="muted">通过安全连接访问你的量化实验室</p>
      <el-form @submit.prevent="signIn" label-position="top"
        ><el-form-item label="管理员账号"
          ><el-input
            v-model="username"
            autocomplete="username"
            name="username" /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            name="password" /></el-form-item
        ><el-button
          native-type="submit"
          type="primary"
          :loading="busy"
          class="full"
          >进入工作台</el-button
        ></el-form
      >
      <p class="small muted">账号由受保护的部署配置创建，无内置默认密码。</p>
    </section>
  </main>
  <div v-else class="shell">
    <aside class="sidebar">
      <router-link :to="navigation('/forecast')" class="brand"
        ><span class="brand-mark small-mark">Q<span>·</span></span
        ><span>ETF Quant Lab<small>预测与分析研究</small></span></router-link
      >
      <div class="nav-caption">工作空间</div>
      <nav aria-label="主导航">
        <router-link
          v-for="[path, label, index] in mainItems"
          :key="path"
          :to="navigation(path)"
          :aria-label="label"
          :class="{ active: route.path === path }"
          ><span class="nav-index">{{ index }}</span
          >{{ label }}</router-link
        >
      </nav>
      <div class="sidebar-bottom">
        <span class="live-dot"></span> 预测与分析研究<small
          >预测结果与验证证据可追溯<br />北京时间 · Asia/Shanghai</small
        >
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span
          >研究工作台 <span class="slash">/</span> {{ route.meta.title }}</span
        >
        <div class="top-actions">
          <el-tag type="success" effect="plain">无真实下单</el-tag
          ><el-button text @click="changingPassword = true">修改密码</el-button
          ><el-button text @click="guarded(logout)">退出登录</el-button>
        </div>
      </header>
      <main class="page">
        <div class="page-heading">
          <div>
            <div class="eyebrow">ETF QUANT LAB</div>
            <h1>{{ route.meta.title }}</h1>
            <p>{{ route.meta.subtitle }}</p>
          </div>
          <span class="version-label">FORECAST RESEARCH</span>
        </div>
        <router-view />
      </main>
      <footer>数据来源与成交假设均可追溯。历史表现不代表未来结果。</footer>
    </div>
  </div>
  <el-dialog
    v-model="changingPassword"
    title="修改密码"
    width="420px"
    style="max-width: calc(100vw - 32px)"
    :close-on-click-modal="!savingPassword"
    :show-close="!savingPassword"
    @closed="clearPasswordForm"
  >
    <p class="muted">修改后所有已登录设备都需要重新登录。新密码至少8个字符。</p>
    <el-form label-position="top" @submit.prevent="savePassword">
      <el-form-item label="当前密码">
        <el-input
          v-model="passwordForm.current"
          type="password"
          show-password
          autocomplete="current-password"
          name="currentPassword"
        />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input
          v-model="passwordForm.next"
          type="password"
          show-password
          autocomplete="new-password"
          name="newPassword"
          :maxlength="64"
        />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="passwordForm.confirmation"
          type="password"
          show-password
          autocomplete="new-password"
          name="confirmPassword"
          :maxlength="64"
        />
      </el-form-item>
      <el-button native-type="submit" type="primary" :loading="savingPassword"
        >保存新密码</el-button
      >
      <el-button :disabled="savingPassword" @click="changingPassword = false"
        >取消</el-button
      >
    </el-form>
  </el-dialog>
</template>
