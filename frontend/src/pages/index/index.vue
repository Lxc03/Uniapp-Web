<!-- H5 首页。无 props、emits 或 store。读取 /api/ready 和 /api/auth/me，通过 /api/auth/login 登录。 -->
<template>
  <view class="page">
    <view class="masthead">
      <view class="masthead-content">
        <text class="title" role="heading" aria-level="1">习惯打卡</text>
        <view v-if="authState === 'authenticated'" class="account" aria-label="当前登录用户">
          <text class="account-name">{{ user.displayName }}</text>
          <text class="account-username">{{ user.username }}</text>
        </view>
      </view>
    </view>

    <view class="content" role="main">
      <view v-if="status === '未就绪'" class="service-notice">
        <text role="status">{{ detail }}</text>
        <button class="button button-secondary" role="button" tabindex="0" @click="checkReadiness">重新检查</button>
      </view>

      <HabitPanel v-if="authState === 'authenticated'" :key="user.id" :user-id="user.id" @session-ended="markGuest" />
      <view v-else class="login">
        <text v-if="authState === 'checking'" class="checking" role="status">正在检查登录状态…</text>
        <form v-else-if="authState === 'guest'" class="login-form" @submit="login">
          <text class="login-heading" role="heading" aria-level="2">登录</text>
          <view class="field-group">
            <label for="login-username">用户名</label>
            <input id="login-username" v-model="username" class="field" placeholder="输入用户名" />
          </view>
          <view class="field-group">
            <label for="login-password">密码</label>
            <input id="login-password" v-model="password" class="field" password placeholder="输入密码" />
          </view>
          <text v-if="authMessage" role="alert" class="auth-message">{{ authMessage }}</text>
          <button class="button button-primary" role="button" :tabindex="submitting ? -1 : 0" :aria-disabled="submitting"
            form-type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登录' }}
          </button>
        </form>
        <view v-else class="login-form">
          <text role="alert" class="auth-message">{{ authMessage }}</text>
          <button class="button button-secondary" role="button" tabindex="0" @click="restoreSession">重试登录状态</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { apiRequest } from '../../api-request.js'
import HabitPanel from './HabitPanel.vue'

const status = ref('检查中')
const detail = ref('')
const authState = ref('checking')
const authMessage = ref('')
const username = ref('')
const password = ref('')
const user = ref(null)
const submitting = ref(false)

function activateButtonFromKeyboard(event) {
  // H5 的 UniApp 按钮渲染为 uni-button，键盘操作复用已有点击和表单提交路径。
  if (event.key !== 'Enter' && event.key !== ' ') return
  const button = event.target
  if (!button.matches?.('.page uni-button[role="button"][tabindex="0"]')) return
  event.preventDefault()
  if (!event.repeat) button.click()
}

function markApiUnavailable() {
  status.value = '未就绪'
  detail.value = '暂时无法访问 API，请重试。'
}

function checkReadiness() {
  status.value = '检查中'
  detail.value = ''
  uni.request({
    url: '/api/ready',
    method: 'GET',
    timeout: 10000,
    success({ statusCode, data }) {
      if (statusCode === 200 && data?.status === 'ready') {
        status.value = '已就绪'
      } else if (statusCode === 503 && data?.status === 'not_ready') {
        status.value = '未就绪'
        const unavailable = []
        if (data.mysql === 'down') unavailable.push('MySQL')
        if (data.redis === 'down') unavailable.push('Redis')
        detail.value = unavailable.length ? unavailable.join('、') + ' 暂不可用' : '服务暂不可用'
      } else {
        markApiUnavailable()
      }
    },
    fail: markApiUnavailable,
  })
}

function markGuest(message = '') {
  // 会话失效时卸载打卡组件，清理页面上的用户数据和组件内令牌。
  user.value = null
  authState.value = 'guest'
  authMessage.value = message
}

async function restoreSession() {
  authState.value = 'checking'
  authMessage.value = ''
  user.value = null
  try {
    const { statusCode, data } = await apiRequest('/auth/me')
    if (statusCode === 200 && data?.code === 'OK' && data.data?.user) {
      user.value = data.data.user
      authState.value = 'authenticated'
      return
    }
    if (statusCode === 401 && data?.code === 'AUTH_SESSION_REQUIRED') {
      markGuest()
      return
    }
    if (statusCode === 401 && data?.code === 'AUTH_SESSION_INVALID_OR_EXPIRED') {
      markGuest(data.message)
      return
    }
    authMessage.value = data?.message || '暂时无法查询登录状态，请重试。'
  } catch {
    authMessage.value = '暂时无法查询登录状态，请重试。'
  }
  authState.value = 'unavailable'
}

async function login() {
  if (submitting.value) return
  authMessage.value = ''
  submitting.value = true
  try {
    const { statusCode, data } = await apiRequest('/auth/login', {
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: { username: username.value, password: password.value },
    })
    if (statusCode === 200 && data?.code === 'OK') {
      password.value = ''
      await restoreSession()
      return
    }
    if (data?.code === 'AUTH_INVALID_CREDENTIALS') password.value = ''
    authMessage.value = data?.message || '暂时无法登录，请重试。'
  } catch {
    authMessage.value = '暂时无法登录，请重试。'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  document.addEventListener('keydown', activateButtonFromKeyboard, true)
  checkReadiness()
  restoreSession()
})
onBeforeUnmount(() => document.removeEventListener('keydown', activateButtonFromKeyboard, true))
</script>

<style scoped>
.page {
  --canvas: #f4f6f4;
  --surface: #fff;
  --ink: #172822;
  --muted: #52635b;
  --line: #dce5df;
  --accent: #12624f;
  --accent-hover: #0b4f3e;
  --error: #9b3d28;
  --notice: #fff6ec;
  min-height: 100vh;
  background: var(--canvas);
  color: var(--ink);
  font-family: system-ui, 'Microsoft YaHei', sans-serif;
}

.masthead {
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}

.masthead-content,
.content {
  box-sizing: border-box;
  width: 100%;
  max-width: 696px;
  margin: 0 auto;
  padding-inline: clamp(16px, 5vw, 32px);
}

.masthead-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 72px;
  padding-block: 12px;
}

.title {
  flex-shrink: 0;
  font-size: 23px;
  font-weight: 750;
  line-height: 1.3;
}

.account {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: baseline;
  gap: 2px 8px;
  min-width: 0;
  font-size: 13px;
  text-align: right;
  overflow-wrap: anywhere;
}

.account-name {
  font-weight: 650;
}

.account-username,
.checking {
  color: var(--muted);
}

.content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: clamp(16px, 4vw, 32px);
  padding-bottom: calc(32px + env(safe-area-inset-bottom, 0px));
}

.login {
  box-sizing: border-box;
  width: 100%;
  max-width: 420px;
  margin: 0 auto;
  padding: clamp(20px, 6vw, 32px);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 18px;
}

.login-form,
.field-group {
  display: flex;
  flex-direction: column;
}

.login-form {
  gap: 16px;
}

.login-heading {
  font-size: 25px;
  font-weight: 720;
}

.field-group {
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
}

.field {
  box-sizing: border-box;
  height: 48px;
  padding: 0 12px;
  border: 1px solid #b7c5bd;
  border-radius: 10px;
  font-size: 16px;
  font-weight: 400;
}

.button {
  box-sizing: border-box;
  display: inline-flex;
  justify-content: center;
  align-items: center;
  min-height: 44px;
  margin: 0;
  padding: 8px 14px;
  border: 1px solid transparent;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 650;
  line-height: 1.4;
}

.button::after {
  border: 0;
}

.button-primary {
  min-height: 48px;
  background: var(--accent);
  color: #fff;
}

.button-primary:not([disabled]):hover {
  background: var(--accent-hover);
}

.button-secondary {
  border-color: var(--line);
  background: var(--surface);
  color: var(--accent);
}

.button[disabled] {
  background: #e9efeb;
  color: var(--muted);
}

.button:focus-visible,
.field:focus-within {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.service-notice {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 6px 12px;
  padding: 8px 12px;
  border: 1px solid #eedccb;
  border-radius: 12px;
  background: var(--notice);
  color: #783f2b;
  font-size: 14px;
}

.auth-message {
  color: var(--error);
  font-size: 14px;
}
</style>
