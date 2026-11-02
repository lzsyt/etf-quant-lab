<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
import { api, guarded, money, statuses, time, type Row } from "../api";
import { ElMessage, ElMessageBox } from "element-plus";
import DataTable from "../components/DataTable.vue";
import ForecastExecution from "../components/ForecastExecution.vue";
import AiModelSettings from "../components/AiModelSettings.vue";
const system = ref<Row>({}),
  tasks = ref<Row[]>([]),
  audit = ref<Row[]>([]),
  busy = ref(false),
  detail = ref<Row | null>(null),
  drawer = ref(false);
const agentTokens = ref<Row[]>([]);
const tokenName = ref("");
const tokenDays = ref(30);
const createdToken = ref("");
/** 只加载令牌名称、期限和撤销状态。 */
async function loadTokens() {
  agentTokens.value = await api("/agent-tokens");
}
/** 明文只在创建响应中出现，关闭页面后无法再次查看。 */
async function createToken() {
  await guarded(async () => {
    const result = await api("/agent-tokens", {
      name: tokenName.value,
      days: tokenDays.value,
    });
    createdToken.value = result.token;
    tokenName.value = "";
    await loadTokens();
  });
}
/** 撤销后立即阻断对应令牌。 */
async function revokeToken(id: string) {
  await ElMessageBox.confirm(
    "撤销后使用该令牌的应用将立即失去访问权限。",
    "撤销令牌",
  );
  await guarded(async () => {
    await api(`/agent-tokens/${id}/revoke`, {});
    await loadTokens();
    ElMessage.success("令牌已撤销");
  });
}
let timer: ReturnType<typeof setInterval> | undefined;
async function load() {
  busy.value = true;
  await guarded(async () => {
    [system.value, tasks.value, audit.value] = await Promise.all([
      api("/system"),
      api("/tasks"),
      api("/system/audit"),
    ]);
  });
  busy.value = false;
}
async function view(id: string) {
  await guarded(async () => {
    detail.value = await api("/tasks/" + id);
    drawer.value = true;
  });
}
onMounted(() => {
  load();
  guarded(loadTokens);
  timer = setInterval(async () => {
    try {
      tasks.value = await api("/tasks");
    } catch {}
  }, 10000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>
<template>
  <div v-loading="busy">
    <ForecastExecution @refreshed="load" />
    <section class="panel">
      <div class="panel-head"><h2>资料来源与调用管理</h2></div>
      <p class="small muted">
        统一预测读取相同资产目录与任务队列。来源质量、外部调用和预算明细可在来源管理中核对；业务分析与最新资讯在预测工作台阅读。
      </p>
      <div class="toolbar">
        <router-link :to="{ path: '/intelligence', query: $route.query }"
          >数据源与调用记录</router-link
        >
        <router-link :to="{ path: '/forecast', query: $route.query }"
          >返回当前资产预测</router-link
        >
      </div>
    </section>
    <section class="panel">
      <div class="panel-head"><h2>Agent 访问令牌</h2></div>
      <p class="muted">
        令牌可读取研究数据，不能修改数据或管理系统。请像密码一样保管。
      </p>
      <el-form inline @submit.prevent="createToken">
        <el-form-item label="名称"
          ><el-input
            v-model="tokenName"
            maxlength="100"
            placeholder="例如：研究助手"
        /></el-form-item>
        <el-form-item label="有效天数"
          ><el-input-number v-model="tokenDays" :min="1" :max="365"
        /></el-form-item>
        <el-form-item
          ><el-button type="primary" @click="createToken"
            >生成令牌</el-button
          ></el-form-item
        >
      </el-form>
      <el-alert
        v-if="createdToken"
        type="warning"
        :closable="false"
        title="令牌仅显示一次，请立即复制并妥善保存。"
      />
      <el-input
        v-if="createdToken"
        v-model="createdToken"
        readonly
        aria-label="新生成的令牌"
      />
      <el-button v-if="createdToken" @click="createdToken = ''"
        >已保存，关闭令牌</el-button
      >
      <el-table :data="agentTokens">
        <el-table-column prop="name" label="名称" />
        <el-table-column label="创建时间"
          ><template #default="scope">{{
            time(scope.row.created_at)
          }}</template></el-table-column
        >
        <el-table-column label="到期时间"
          ><template #default="scope">{{
            time(scope.row.expires_at)
          }}</template></el-table-column
        >
        <el-table-column label="状态"
          ><template #default="scope">{{
            scope.row.revoked_at
              ? "已撤销"
              : new Date(scope.row.expires_at).getTime() <= Date.now()
                ? "已过期"
                : "有效"
          }}</template></el-table-column
        >
        <el-table-column label="操作"
          ><template #default="scope"
            ><el-button
              v-if="!scope.row.revoked_at"
              link
              type="danger"
              @click="revokeToken(scope.row.id)"
              >撤销</el-button
            ></template
          ></el-table-column
        >
      </el-table>
    </section>
    <div class="stats">
      <div class="stat">
        <div class="stat-label">Java后端 / 数据库</div>
        <div class="stat-value accent">
          {{ system.database === "UP" ? "运行正常" : "检查中" }}
        </div>
        <div class="stat-note">
          已运行 {{ Math.floor((system.uptime_ms || 0) / 60000) }} 分钟
        </div>
      </div>
      <div class="stat">
        <div class="stat-label">Python分析服务</div>
        <div class="stat-value">
          {{ system.quant === "UP" ? "运行正常" : system.quant || "检查中" }}
        </div>
        <div class="stat-note">CPU模型训练 · 串行处理</div>
      </div>
      <div class="stat">
        <div class="stat-label">JVM内存 / MB</div>
        <div class="stat-value">
          {{ system.jvm_used_mb || 0 }}
          <span class="small muted">/ {{ system.jvm_max_mb || 0 }}</span>
        </div>
        <div class="stat-note">{{ system.cpu_count || "—" }} 个逻辑核心</div>
      </div>
      <div class="stat">
        <div class="stat-label">累计模型费用占用 / USD</div>
        <div class="stat-value">
          {{ Number(system.cost_total_usd || 0).toFixed(5) }}
        </div>
        <div class="stat-note">含未确定扣费的预算预留</div>
      </div>
    </div>
    <div class="grid-equal">
      <section class="panel">
        <div class="panel-head">
          <h2>模型API配置</h2>
        </div>
        <AiModelSettings
          :providers="system.llm?.providers || []"
          @saved="load"
        />
        <div class="info-row">
          <span>DeepSeek 每日费用上限（USD）</span
          ><strong>{{ system.llm?.daily_limit_usd ?? "—" }}</strong>
        </div>
        <div class="info-row">
          <span>GPT 每日费用上限（USD）</span
          ><strong>{{ system.llm?.gpt_daily_limit_usd ?? "—" }}</strong>
        </div>
        <p class="small muted" style="margin-top: 15px">
          两家额度独立，按北京时间每日重置，预留和扣费未确定的请求也占用各自额度。GPT仅进行独立预测，分歧分析已关闭；历史费用仍按原账本计入额度。所有GPT请求使用专用代理。密钥通过服务器受限配置管理。
        </p>
      </section>
      <section class="panel">
        <div class="panel-head">
          <h2>调度与恢复</h2>
          <el-tag :type="system.scheduling_enabled ? 'success' : 'warning'">{{
            system.scheduling_enabled
              ? "既有定时任务已启用"
              : "既有定时任务未启用"
          }}</el-tag>
        </div>
        <div class="info-row">
          <span>运行方式</span><strong>按资产目标组织预测与到期核验</strong>
        </div>
        <div class="info-row">
          <span>重启恢复</span><strong>数据库队列 · 业务键去重</strong>
        </div>
        <div class="info-row">
          <span>数据依赖</span><strong>行情、净值、资讯与模型按资产核验</strong>
        </div>
        <div class="info-row">
          <span>访问方式</span><strong>HTTPS管理台 · 业务服务回环监听</strong>
        </div>
        <div class="toolbar" style="margin-top: 18px">
          <el-button @click="load">刷新状态</el-button>
        </div>
      </section>
    </div>
    <section class="panel">
      <div class="panel-head">
        <h2>持久任务与恢复状态</h2>
        <span class="small muted">每10秒刷新</span>
      </div>
      <el-table :data="tasks.slice(0, 30)" stripe empty-text="暂无任务"
        ><el-table-column
          prop="task_type"
          label="任务"
          width="120"
        /><el-table-column label="状态" width="110"
          ><template #default="scope"
            ><el-tag
              :type="
                scope.row.status === 'FAILED'
                  ? 'danger'
                  : scope.row.status === 'SUCCEEDED'
                    ? 'success'
                    : 'info'
              "
              >{{ statuses[scope.row.status] || scope.row.status }}</el-tag
            ></template
          ></el-table-column
        ><el-table-column
          prop="attempts"
          label="尝试"
          width="70"
        /><el-table-column label="更新时间" min-width="180"
          ><template #default="scope">{{
            time(scope.row.updated_at)
          }}</template></el-table-column
        ><el-table-column label="异常与恢复" min-width="280"
          ><template #default="scope">
            <template v-if="scope.row.status === 'FAILED'">
              <div>{{ scope.row.error_message || scope.row.error_code }}</div>
              <div
                v-if="scope.row.task_type === 'FORWARD_EOD'"
                class="small muted"
              >
                当日交易时段结束后按小时自动重试，数据补齐后继续
              </div>
            </template>
          </template></el-table-column
        ><el-table-column label="结果" width="90"
          ><template #default="scope"
            ><el-button text type="primary" @click="view(scope.row.id)"
              >查看</el-button
            ></template
          ></el-table-column
        ></el-table
      >
    </section>
    <section class="panel">
      <el-tabs
        ><el-tab-pane label="API费用"
          ><DataTable
            :rows="system.costs || []"
            :columns="[
              { key: 'created_at', label: '时间', format: time, width: 180 },
              { key: 'status', label: '状态', format: (v) => statuses[v] || v },
              { key: 'reserved_cost', label: '预留USD' },
              { key: 'actual_cost', label: '确认估算USD' },
              { key: 'input_tokens', label: '输入Token' },
              { key: 'output_tokens', label: '输出Token' },
              { key: 'duration_ms', label: '耗时ms' },
            ]" /></el-tab-pane
        ><el-tab-pane label="操作审计"
          ><DataTable
            :rows="audit"
            :columns="[
              { key: 'created_at', label: '时间', format: time, width: 180 },
              { key: 'actor', label: '操作人' },
              { key: 'action', label: '操作', width: 170 },
              {
                key: 'details',
                label: '详情',
                format: (v) => JSON.stringify(v),
                width: 360,
              },
            ]" /></el-tab-pane
        ><el-tab-pane label="数据覆盖"
          ><DataTable
            :rows="system.market || []"
            :columns="[
              { key: 'symbol', label: 'ETF' },
              { key: 'nature', label: '性质', format: (v) => statuses[v] || v },
              { key: 'source', label: '来源' },
              { key: 'count', label: '条数' },
              { key: 'first_date', label: '开始日期' },
              { key: 'last_date', label: '最新日期' },
            ]" /></el-tab-pane
      ></el-tabs>
    </section>
    <el-drawer v-model="drawer" title="任务执行记录" size="65%"
      ><div class="text-output">
        {{ JSON.stringify(detail, null, 2) }}
      </div></el-drawer
    >
  </div>
</template>
