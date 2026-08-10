<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchCurrentView, listTenantOptions, switchTenantView, type TenantVO } from '@/api/tenant'

// 顶栏租户切换器：只有平台运营方看得到。租户管理员的视角恒等于自己所属租户，
// 给他渲染一个只有一项的下拉毫无意义，故直接不渲染。

const PLATFORM = '__platform__'

const platformOperator = ref(false)
const effectiveTenant = ref<string | null>(null)
const options = ref<TenantVO[]>([])
const switching = ref(false)

/** 平台视角在下拉里是一个显式选项，而不是"空值"——空值看起来像没选，容易误以为出了问题。 */
const selected = computed({
  get: () => effectiveTenant.value ?? PLATFORM,
  set: (value: string) => void handleSwitch(value),
})

async function load() {
  const view = await fetchCurrentView()
  platformOperator.value = view.platformOperator === true
  effectiveTenant.value = view.effectiveTenantId
  if (platformOperator.value) {
    options.value = await listTenantOptions()
  }
}

async function handleSwitch(tenantCode: string) {
  if (switching.value || tenantCode === (effectiveTenant.value ?? PLATFORM)) return
  switching.value = true
  try {
    await switchTenantView(tenantCode === PLATFORM ? undefined : tenantCode)
    ElMessage.success(tenantCode === PLATFORM ? '已回到平台视角' : `已切换到租户 ${tenantCode}`)
    // 整页重载：当前页面上的列表、详情、缓存都属于上一个租户，逐个刷新既繁琐又容易漏掉一处
    window.location.reload()
  } finally {
    switching.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-select
    v-if="platformOperator"
    v-model="selected"
    class="tenant-switcher"
    size="default"
    :loading="switching"
    filterable
  >
    <template #prefix>
      <el-icon><OfficeBuilding /></el-icon>
    </template>
    <el-option label="平台视角（全部）" :value="PLATFORM" />
    <el-option
      v-for="item in options"
      :key="item.tenantCode"
      :label="`${item.tenantName}（${item.tenantCode}）`"
      :value="item.tenantCode"
    />
  </el-select>
</template>

<style scoped>
.tenant-switcher {
  width: 220px;
  margin-right: 8px;
}
</style>
