<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const auth = useAuthStore()
</script>

<template>
  <div class="workspace-empty">
    <section class="empty-panel" aria-labelledby="workspace-empty-title">
      <div class="empty-mark" aria-hidden="true"><span>01</span><span>02</span><span>03</span></div>
      <span class="eyebrow">AGENT WORKSPACE</span>
      <h1 id="workspace-empty-title">还没有可进入的智能体</h1>
      <p>工作区只呈现已经启用并对当前账号可见的智能体，避免把未发布能力误当成可用服务。</p>
      <div class="empty-flow" aria-label="智能体进入工作区的三个阶段">
        <span>装配能力</span><i aria-hidden="true"></i><span>验证运行</span><i aria-hidden="true"></i><span>启用发布</span>
      </div>
      <el-button v-if="auth.hasPermission('agent:add')" class="cw-final-action" type="primary" @click="router.push('/aiconfig/agent')">
        创建并配置智能体
      </el-button>
      <small v-else>请联系有权限的管理员启用并授权智能体。</small>
    </section>
  </div>
</template>

<style scoped>
.workspace-empty {
  box-sizing: border-box;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px;
  background:
    radial-gradient(circle at 85% 16%, color-mix(in srgb, var(--cw-cobalt) 14%, transparent), transparent 30%),
    var(--cw-canvas);
}

.empty-panel {
  width: min(620px, 100%);
  padding: clamp(28px, 6vw, 52px);
  text-align: center;
  border: 1px solid var(--cw-line);
  border-top: 3px solid var(--cw-amber);
  border-radius: var(--cw-radius-lg);
  background: var(--cw-paper);
  box-shadow: var(--cw-shadow-sm);
}

.empty-mark {
  display: inline-grid;
  grid-template-columns: repeat(3, 30px);
  gap: 5px;
  margin-bottom: 18px;
}

.empty-mark span {
  display: grid;
  place-items: center;
  height: 30px;
  color: var(--cw-text-muted);
  border: 1px solid var(--cw-line);
  border-radius: 50%;
  font-size: 9px;
  font-weight: 800;
}

.empty-mark span:last-child {
  color: #1e1608;
  border-color: var(--cw-amber-solid);
  background: var(--cw-amber-solid);
}

.eyebrow {
  display: block;
  color: var(--cw-cobalt);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .16em;
}

.empty-panel h1 {
  margin: 8px 0 10px;
  color: var(--cw-text);
  font-size: clamp(24px, 4vw, 32px);
  letter-spacing: -.03em;
}

.empty-panel p {
  max-width: 470px;
  margin: 0 auto;
  color: var(--cw-text-muted);
  line-height: 1.7;
}

.empty-flow {
  display: grid;
  grid-template-columns: auto 1fr auto 1fr auto;
  align-items: center;
  gap: 10px;
  margin: 28px 0;
  color: var(--cw-text-muted);
  font-size: 12px;
  font-weight: 650;
}

.empty-flow i {
  height: 1px;
  background: linear-gradient(90deg, var(--cw-line), var(--cw-cobalt));
}

.empty-panel small {
  display: block;
  color: var(--cw-text-muted);
}

@media (max-width: 480px) {
  .workspace-empty {
    align-items: flex-start;
    padding: 14px 12px;
  }

  .empty-panel {
    padding: 28px 20px;
  }

  .empty-flow {
    grid-template-columns: 1fr;
    gap: 7px;
    margin: 22px 0;
  }

  .empty-flow i {
    width: 1px;
    height: 12px;
    margin: 0 auto;
    background: linear-gradient(var(--cw-line), var(--cw-cobalt));
  }

  .cw-final-action {
    width: 100%;
  }
}
</style>
