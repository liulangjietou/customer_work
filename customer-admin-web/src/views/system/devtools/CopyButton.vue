<script setup lang="ts">
import { copyText } from './composables/useCopy'

// 每个输出区域右上角统一用这一个小组件承载"一键复制"，样式与交互全工具箱保持一致
const props = withDefaults(defineProps<{ text: string; label?: string }>(), {
  label: '内容',
})

function handleCopy() {
  copyText(props.text, props.label)
}
</script>

<template>
  <el-button link type="primary" size="small" class="copy-button" :disabled="!text" @click="handleCopy">
    <el-icon><DocumentCopy /></el-icon>
    复制
  </el-button>
</template>

<style scoped>
.copy-button {
  padding: 0;
}
</style>
