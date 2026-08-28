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

/** 拉取邀请状态的补拉次数与间隔：覆盖后端异步发邀请的 1~2 秒空窗。 */
const INVITE_FETCH_RETRIES = 2
const INVITE_FETCH_RETRY_DELAY_MS = 1500

const visible = ref(false)
const score = ref(0)
const comment = ref('')
const submitting = ref(false)

/**
 * 只在"已邀请且未评价"时弹卡：没被邀请过说明会话还没结束，已评过就别再打扰。
 *
 * 首次查不到就重试一次：邀请由后端在工单进入终态时经 Outbox 异步发出（扫描间隔 1s），
 * 而这里的查询紧跟着关单请求的响应，正常会跑在邀请落库之前——不重试的话评分卡几乎永远不弹。
 */
async function checkPending(retryLeft = INVITE_FETCH_RETRIES) {
  if (!props.sessionId) return
  try {
    const survey = await fetchCsatStatus(props.sessionId)
    visible.value = !survey.answered
  } catch {
    // 查不到状态（404 或网络问题）就不弹，静默处理——评分是锦上添花，不该给用户报错
    visible.value = false
    if (retryLeft > 0) {
      const sessionAtSchedule = props.sessionId
      window.setTimeout(() => {
        // 期间用户可能已新开会话，别把上一次的评分卡弹到新会话上
        if (props.sessionId === sessionAtSchedule) {
          void checkPending(retryLeft - 1)
        }
      }, INVITE_FETCH_RETRY_DELAY_MS)
    }
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
      <div class="sheet-handle" aria-hidden="true"></div>
      <div class="csat-mark" aria-hidden="true">✓</div>
      <div class="csat-kicker">SERVICE COMPLETE</div>
      <div class="csat-title">本次服务您还满意吗？</div>
      <div class="csat-sub">只需几秒，您的真实感受会帮助我们持续改进</div>

      <div class="rate-shell">
        <van-rate v-model="score" :size="34" gutter="9" color="#f6b73c" void-icon="star" void-color="#e7ecf3" />
      </div>
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
      <div class="privacy-note">评价仅用于服务质量改进</div>
    </div>
  </van-popup>
</template>

<style scoped>
.csat-card {
  position: relative;
  padding: 15px 22px calc(24px + env(safe-area-inset-bottom));
  text-align: center;
  background:
    radial-gradient(circle at 50% 5%, rgba(24, 119, 242, 0.08), transparent 30%),
    #fff;
}

.sheet-handle {
  width: 42px;
  height: 4px;
  margin: 0 auto 18px;
  border-radius: 999px;
  background: #dce3ed;
}

.csat-mark {
  display: grid;
  width: 46px;
  height: 46px;
  margin: 0 auto 11px;
  place-items: center;
  border-radius: 15px;
  color: #fff;
  background: linear-gradient(145deg, #2bd09a, #18a978);
  font-size: 21px;
  font-weight: 800;
  box-shadow: 0 11px 24px rgba(37, 196, 138, 0.23);
}

.csat-kicker {
  margin-bottom: 4px;
  color: #1677ff;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 1.7px;
}

.csat-title {
  font-size: 20px;
  font-weight: 750;
  color: #13233a;
}

.csat-sub {
  max-width: 270px;
  margin: 7px auto 18px;
  font-size: 12px;
  color: #718096;
  line-height: 1.55;
}

.rate-shell {
  display: inline-flex;
  padding: 12px 14px;
  border: 1px solid rgba(19, 35, 58, 0.06);
  border-radius: 18px;
  background: #f8fafc;
}

.csat-score-label {
  font-size: 13px;
  color: #45566d;
  margin-top: 10px;
  min-height: 18px;
}

.csat-comment {
  margin-top: 16px;
  border: 1px solid rgba(19, 35, 58, 0.06);
  border-radius: 14px;
  background: #f7f9fc;
}

.csat-submit {
  margin-top: 20px;
  min-height: 48px;
  box-shadow: 0 11px 24px rgba(24, 119, 242, 0.2);
}

.privacy-note {
  margin-top: 12px;
  color: #a1abba;
  font-size: 10px;
}
</style>
