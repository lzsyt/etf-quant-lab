<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import * as echarts from "echarts/core";
import { LineChart, CandlestickChart, BarChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  TitleComponent,
} from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
echarts.use([
  LineChart,
  CandlestickChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  TitleComponent,
  SVGRenderer,
]);
const props = defineProps<{ option: Record<string, any>; height?: number }>();
const element = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined, observer: ResizeObserver | undefined;
function render() {
  chart?.setOption(
    {
      color: ["#087f8c", "#4663c5", "#e9a43b", "#94a3b8", "#cf6576"],
      textStyle: { fontFamily: "inherit" },
      ...props.option,
    },
    true,
  );
}
onMounted(() => {
  chart = echarts.init(element.value!, undefined, { renderer: "svg" });
  render();
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(element.value!);
});
watch(() => props.option, render, { deep: true });
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div
    ref="element"
    role="img"
    aria-label="研究数据图表"
    :style="{ height: `${height || 330}px`, width: '100%' }"
  />
</template>
