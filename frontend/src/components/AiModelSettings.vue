<script setup lang="ts">
import { ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { api, guarded, type Row } from "../api";

const props = defineProps<{ providers: Row[] }>();
const emit = defineEmits<{ saved: [] }>();
const edits = ref<Record<string, Row>>({});
const saving = ref<Record<string, boolean>>({});
const effortNames: Row = {
  none: "关闭思考",
  low: "低",
  medium: "中",
  high: "高",
  xhigh: "超高",
  max: "最高",
};

/** 仅在服务器配置刷新时初始化表单，任务轮询不会覆盖正在编辑的选择。 */
watch(
  () => props.providers,
  (providers) => {
    edits.value = Object.fromEntries(
      providers.map((provider) => [provider.id, { ...provider.current }]),
    );
  },
  { immediate: true },
);

/** 只展示所选模型真实支持的档位和价格，不允许输入任意模型或自行修改计费单价。 */
function selectedModel(provider: Row): Row | undefined {
  return provider.models.find(
    (model: Row) => model.id === edits.value[provider.id]?.model,
  );
}

/** 切换模型时移除不支持的档位，明确显示替换后的有效选择。 */
function changeModel(provider: Row) {
  const model = selectedModel(provider);
  if (
    model &&
    !model.efforts.includes(edits.value[provider.id].reasoning_effort)
  ) {
    edits.value[provider.id].reasoning_effort = model.efforts[0];
  }
}

/** 原样保存没有意义；保存期间锁定本表单，避免将后续编辑误当成已生效。 */
function changed(provider: Row): boolean {
  const edit = edits.value[provider.id];
  return (
    !!edit &&
    (edit.model !== provider.current.model ||
      edit.reasoning_effort !== provider.current.reasoning_effort)
  );
}

/** 保存只更新选择；模型调用由后续正常分析任务执行，不在保存时触发测试消费。 */
async function save(provider: Row) {
  saving.value[provider.id] = true;
  await guarded(async () => {
    await api(`/system/model-settings/${provider.id}`, {
      ...edits.value[provider.id],
    });
    ElMessage.success(`${provider.name} 配置已保存，后续分析生效`);
    emit("saved");
  });
  saving.value[provider.id] = false;
}
</script>

<template>
  <p v-if="!providers.length" class="small muted">
    模型设置暂不可用，请稍后刷新。
  </p>
  <form
    v-for="provider in providers"
    :key="provider.id"
    class="model-settings-form"
    @submit.prevent="save(provider)"
  >
    <fieldset
      :aria-label="`${provider.name}模型配置`"
      :disabled="saving[provider.id]"
    >
      <legend>{{ provider.name }}</legend>
      <p class="small muted">
        {{ provider.enabled ? "已启用" : "调用已关闭" }} ·
        {{ provider.configured ? "密钥及连接配置已就绪" : "连接配置未就绪" }}
      </p>
      <label :for="`model-${provider.id}`">{{ provider.name }} 推理模型</label>
      <select
        :id="`model-${provider.id}`"
        v-model="edits[provider.id].model"
        @change="changeModel(provider)"
      >
        <option
          v-for="model in provider.models"
          :key="model.id"
          :value="model.id"
        >
          {{ model.name }}（{{ model.id }}）
        </option>
      </select>
      <label :for="`effort-${provider.id}`">{{ provider.name }} 推理强度</label>
      <select
        :id="`effort-${provider.id}`"
        v-model="edits[provider.id].reasoning_effort"
      >
        <option
          v-for="effort in selectedModel(provider)?.efforts || []"
          :key="effort"
          :value="effort"
        >
          {{ effortNames[effort] || effort }}
        </option>
      </select>
      <p class="small muted">
        输入 ${{ selectedModel(provider)?.input_per_million }} / 输出 ${{
          selectedModel(provider)?.output_per_million
        }}，每百万 token；用于保守费用估算。
      </p>
      <p class="small">
        当前生效：{{ provider.current.model }} ·
        {{
          effortNames[provider.current.reasoning_effort] ||
          provider.current.reasoning_effort
        }}
      </p>
      <el-button
        type="primary"
        native-type="submit"
        :loading="saving[provider.id]"
        :disabled="!changed(provider)"
        >保存 {{ provider.name }} 设置</el-button
      >
    </fieldset>
  </form>
  <p class="small muted">
    保存后用于后续分析，进行中的请求保留原配置。较高强度通常更慢，也可能更快用完每日额度；模型访问以API账号权限为准。GPT仅用于独立预测，分歧分析已关闭。
  </p>
</template>

<style scoped>
.model-settings-form {
  margin-bottom: 18px;
}
fieldset {
  margin: 0;
  padding: 16px;
  border: 1px solid #e3e9ef;
  border-radius: 8px;
  min-width: 0;
}
legend {
  padding: 0 6px;
  font-weight: 600;
}
label {
  display: block;
  margin: 12px 0 6px;
  font-size: 13px;
}
select {
  width: 100%;
  min-width: 0;
  padding: 8px;
  border: 1px solid #cdd8e1;
  border-radius: 6px;
  background: white;
  color: inherit;
  font: inherit;
}
p {
  overflow-wrap: anywhere;
  line-height: 1.7;
}
</style>
