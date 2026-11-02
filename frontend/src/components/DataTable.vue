<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { Row } from "../api";
const props = defineProps<{
  rows: Row[];
  columns: {
    key: string;
    label: string;
    width?: number;
    format?: (value: any, row: Row) => string;
  }[];
  pageSize?: number;
  empty?: string;
}>();
const emit = defineEmits<{ rowClick: [row: Row] }>();
const page = ref(1);
const visible = computed(() =>
  props.rows.slice(
    (page.value - 1) * (props.pageSize || 12),
    page.value * (props.pageSize || 12),
  ),
);
watch(
  () => props.rows,
  () => {
    page.value = 1;
  },
);
</script>
<template>
  <el-table
    :data="visible"
    :empty-text="empty || '暂无记录'"
    stripe
    @row-click="(row) => emit('rowClick', row)"
    ><el-table-column
      v-for="column in columns"
      :key="column.key + column.label"
      :label="column.label"
      :min-width="column.width || 120"
      ><template #default="scope">{{
        column.format
          ? column.format(scope.row[column.key], scope.row)
          : (scope.row[column.key] ?? "—")
      }}</template></el-table-column
    ></el-table
  ><el-pagination
    v-if="rows.length > (pageSize || 12)"
    v-model:current-page="page"
    :page-size="pageSize || 12"
    :total="rows.length"
    layout="prev, pager, next, total"
    class="pagination"
  />
</template>
