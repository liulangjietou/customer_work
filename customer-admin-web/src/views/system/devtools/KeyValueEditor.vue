<script setup lang="ts">
import type { HttpKeyValueItem } from '@/api/devtools'

// Params/Headers/Form 三处共用的键值对行编辑器：行内删除 + 底部添加，空行在发送时由调用方过滤
const props = withDefaults(
  defineProps<{
    modelValue: HttpKeyValueItem[]
    namePlaceholder?: string
    valuePlaceholder?: string
  }>(),
  {
    namePlaceholder: '名称',
    valuePlaceholder: '值',
  },
)

const emit = defineEmits<{ (e: 'update:modelValue', value: HttpKeyValueItem[]): void }>()

function updateItem(index: number, patch: Partial<HttpKeyValueItem>) {
  const next = props.modelValue.map((item, i) => (i === index ? { ...item, ...patch } : item))
  emit('update:modelValue', next)
}

function removeItem(index: number) {
  emit(
    'update:modelValue',
    props.modelValue.filter((_, i) => i !== index),
  )
}

function addItem() {
  emit('update:modelValue', [...props.modelValue, { name: '', value: '' }])
}
</script>

<template>
  <div class="kv-editor">
    <div v-for="(item, index) in modelValue" :key="index" class="kv-row">
      <el-input
        :model-value="item.name"
        :placeholder="namePlaceholder"
        class="kv-name"
        @update:model-value="(v: string) => updateItem(index, { name: v })"
      />
      <el-input
        :model-value="item.value"
        :placeholder="valuePlaceholder"
        class="kv-value"
        @update:model-value="(v: string) => updateItem(index, { value: v })"
      />
      <el-button link type="danger" @click="removeItem(index)">
        <el-icon><Delete /></el-icon>
      </el-button>
    </div>
    <el-button link type="primary" @click="addItem">
      <el-icon><Plus /></el-icon>
      添加一行
    </el-button>
  </div>
</template>

<style scoped>
.kv-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kv-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.kv-name {
  width: 260px;
  flex-shrink: 0;
}

.kv-value {
  flex: 1;
}
</style>
