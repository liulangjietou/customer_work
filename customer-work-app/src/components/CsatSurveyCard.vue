<script setup lang="ts">
import { ref, watch } from 'vue'
import { showToast } from 'vant'
import { fetchCsatStatus, submitCsat } from '@/api/csat'

// 会话级满意度评分卡。
//
// 与消息级点赞/点踩是两回事：那个看单句答得好不好，这个看"这次服务整体解决了没有"。
// 一次会话可能每句都答得挺像样，但问题始终没解决——那会拿到一堆点赞和一个 2 分。
//
// 弹出时机由父组件决定（会话结束时），本组件只负责"该不该显示"和"提交"。

const props = defineProps<{
  sessionId: string
  /** 父组件判定会话已结束时置 true，组件据此去查是否有待评价的邀请 */
  sessionEnded: boolean
}>()

/** 评分文案：让用户知道每一档代表什么，避免所有人都点 5 分。 */
const SCORE_LABELS = ['', '很不满意', '不太满意', '一般', '比较满意', '非常满意']

/** 低于这个分数才追问原因——满意的用户不该被额外打扰。 */
const COMMENT_PROMPT_THRESHOLD = 4

const visible = ref(false)
const score = ref(0)
const comment = ref('')
const submitting = ref(false)

/** 只在"已邀请且未评价"时弹卡：没被邀请过说明会话还没结束，已评过就别再打扰。 */
async function checkPending() {
  if (!props.sessionId) return
  try {
    const survey = await fetchCsatStatus(props.sessionId)
    visible.value = !survey.answered
  } catch {
    // 查不到状态（404 或网络问题）就不弹，静默处理——评分是锦上添花，不该给用户报错
    visible.value = false
  }
}

async function handleSubmit() {
  if (score.value < 1) {
    showToast('请先选择评分')
    return
  }
  submitting.value = true
  try {
    await submitCsat(props.sessionId, score.value, comment.value || undefined)
    showToast('感谢您的评价')
    visible.value = false
  } finally {
    submitting.value = false
  }
}

function handleClose() {
  visible.value = false
}

watch(
  () => [props.sessionEnded, props.sessionId],
  ([ended]) => {
    if (ended) {
      score.value = 0
      comment.value = ''
      void checkPending()
    }
  },
  { immediate: true },
)
</script>

<template>
  <van-popup
    v-model:show="visible"
    position="bottom"
    round
    closeable
    :close-on-click-overlay="false"
    @close="handleClose"
  >
    <div class="csat-card">
      <div class="csat-title">本次服务您还满意吗？</div>
      <div class="csat-sub">您的评价会帮我们改进服务</div>

      <van-rate v-model="score" :size="32" gutter="10" color="#ffd21e" void-icon="star" void-color="#eee" />
      <div class="csat-score-label">{{ SCORE_LABELS[score] || '请点击星星评分' }}</div>

      <!-- 只在低分时追问原因：满意的用户不该被额外打扰，而低分留言才是能拿来改进的东西 -->
      <van-field
        v-if="score > 0 && score < COMMENT_PROMPT_THRESHOLD"
        v-model="comment"
        type="textarea"
        rows="2"
        maxlength="200"
        show-word-limit
        placeholder="哪里没帮到您？说说看，我们会改进"
        class="csat-comment"
      />

      <van-button
        type="primary"
        block
        round
        :loading="submitting"
        class="csat-submit"
        @click="handleSubmit"
      >
        提交评价
      </van-button>
    </div>
  </van-popup>
</template>

<style scoped>
.csat-card {
  padding: 28px 20px 24px;
  text-align: center;
}

.csat-title {
  font-size: 17px;
  font-weight: 600;
  color: #323233;
}

.csat-sub {
  font-size: 12px;
  color: #969799;
  margin-top: 6px;
  margin-bottom: 20px;
}

.csat-score-label {
  font-size: 13px;
  color: #646566;
  margin-top: 12px;
  min-height: 18px;
}

.csat-comment {
  margin-top: 16px;
  background: #f7f8fa;
  border-radius: 8px;
}

.csat-submit {
  margin-top: 20px;
}
</style>
