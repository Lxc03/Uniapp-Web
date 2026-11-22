<!--
  当前用户的打卡项和待核实动作。props: userId。emits: session-ended（会话失效消息）。
  无全局 store。使用 /api/habits、/api/habits/{id}/check-ins 与 /api/auth/csrf。
-->
<template>
  <view class="habits">
    <view class="progress-card">
      <view class="progress-header">
        <text class="progress-title" role="heading" aria-level="2">今日已完成</text>
        <text v-if="businessDate" class="business-date">{{ businessDate }}</text>
      </view>
      <template v-if="hasLoaded">
        <view class="progress-value" role="status" aria-live="polite">
          <text class="completed-count">{{ completedCount }}</text>
          <text class="total-count">/ {{ items.length }} 项</text>
        </view>
        <view class="progress-track" role="progressbar" aria-label="今日打卡进度" :aria-valuenow="completionPercent"
          aria-valuemin="0" aria-valuemax="100" :aria-valuetext="`已完成 ${completedCount} 项，共 ${items.length} 项`">
          <view class="progress-fill" :style="{ width: completionPercent + '%' }" />
        </view>
      </template>
      <text v-else class="progress-placeholder" role="status">{{ loading ? '正在读取今日进度…' : '暂时无法获取今日进度' }}</text>
    </view>

    <view class="list-section">
      <view class="list-header">
        <text class="heading" role="heading" aria-level="2">打卡项</text>
        <view class="actions">
          <button class="button button-quiet" role="button" aria-label="刷新打卡项" :tabindex="loading || creating ? -1 : 0"
            :aria-disabled="loading || creating" :disabled="loading || creating" @click="loadHabits">
            {{ loading ? '刷新中…' : '刷新' }}
          </button>
          <button v-if="items.length && !createOpen" class="button button-secondary" role="button" tabindex="0" @click="openCreate">新建打卡项</button>
        </view>
      </view>

      <view v-if="listError" class="list-notice" role="alert">
        <text>{{ listError }}</text>
        <text v-if="hasLoaded">当前显示上次加载的数据。</text>
      </view>
      <text v-else-if="loading && !hasLoaded" class="list-notice list-loading" role="status">正在读取打卡项…</text>

      <form v-if="createOpen" class="form" @submit="createHabit">
        <view class="form-field">
          <label for="habit-name">打卡项名称</label>
          <input id="habit-name" v-model="name" class="field" :maxlength="-1" placeholder="例如，阅读 20 分钟" />
        </view>
        <text v-if="createMessage" class="feedback feedback-error" role="alert">{{ createMessage }}</text>
        <view class="actions form-actions">
          <button class="button button-quiet" role="button" :tabindex="creating ? -1 : 0" :aria-disabled="creating" :disabled="creating"
            @click="closeCreate">取消</button>
          <button class="button button-primary" role="button" :tabindex="creating || loading ? -1 : 0" :aria-disabled="creating || loading"
            form-type="submit" :disabled="creating || loading">
            {{ creating ? '创建中…' : '创建打卡项' }}
          </button>
        </view>
      </form>
      <text v-else-if="createMessage" class="feedback feedback-success" role="status">{{ createMessage }}</text>

      <view v-if="hasLoaded && !items.length && !createOpen" class="empty-state">
        <text class="empty-title">暂无打卡项</text>
        <button class="button button-primary" role="button" tabindex="0" @click="openCreate">新建打卡项</button>
      </view>

      <view v-if="items.length" class="list" role="list">
        <view v-for="habit in items" :key="habit.id" class="item" :class="{ 'item-complete': habit.checkedInToday }" role="listitem">
          <view class="item-overview">
            <view class="item-label">
              <text class="item-name" role="heading" aria-level="3">{{ habit.name }}</text>
              <text class="item-status">{{ todayStatus(habit) }}</text>
            </view>
            <view class="streak">
              <text class="streak-label">连续</text>
              <view class="streak-value"><text class="streak-days">{{ habit.streakDays }}</text><text>天</text></view>
            </view>
          </view>

          <button v-if="!habit.checkedInToday && (!isPending(habit.id, businessDate) || busy[pendingKey(habit.id, businessDate)] === 'submit')"
            class="button button-primary check-in-button" role="button" :tabindex="busy[pendingKey(habit.id, businessDate)] ? -1 : 0"
            :aria-disabled="!!busy[pendingKey(habit.id, businessDate)]" :disabled="!!busy[pendingKey(habit.id, businessDate)]"
            @click="checkIn(habit.id, businessDate)">{{ busy[pendingKey(habit.id, businessDate)] ? '提交中…' : '打今日卡' }}</button>

          <view v-for="intent in pendingFor(habit.id)" :key="intent.date" class="pending"
            v-show="busy[pendingKey(habit.id, intent.date)] !== 'submit'">
            <text>{{ intent.date }} 打卡待核实</text>
            <view class="actions pending-actions">
              <button class="button button-secondary" role="button" :tabindex="busy[pendingKey(habit.id, intent.date)] ? -1 : 0"
                :aria-disabled="!!busy[pendingKey(habit.id, intent.date)]" :disabled="!!busy[pendingKey(habit.id, intent.date)]"
                @click="checkIn(habit.id, intent.date)">{{ busy[pendingKey(habit.id, intent.date)] ? '核实中…' : '核实原打卡' }}</button>
              <button class="button button-quiet" role="button" :tabindex="busy[pendingKey(habit.id, intent.date)] ? -1 : 0"
                :aria-disabled="!!busy[pendingKey(habit.id, intent.date)]" :disabled="!!busy[pendingKey(habit.id, intent.date)]"
                @click="setPending(habit.id, intent.date, false)">清除待核实</button>
            </view>
          </view>
          <text v-if="checkInMessages[habit.id]" class="feedback" :class="'feedback-' + checkInMessages[habit.id].kind"
            role="status">{{ checkInMessages[habit.id].text }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { apiRequest } from '../../api-request.js'

const emit = defineEmits(['session-ended'])
const props = defineProps({ userId: { type: String, required: true } })
const name = ref('')
const items = ref([])
const loading = ref(true)
const hasLoaded = ref(false)
const createOpen = ref(false)
const creating = ref(false)
const listError = ref('')
const createMessage = ref('')
const businessDate = ref('')
const completedCount = computed(() => items.value.filter(habit => habit.checkedInToday).length)
const completionPercent = computed(() => items.value.length ? Math.round(completedCount.value / items.value.length * 100) : 0)
const busy = reactive({})
const checkInMessages = reactive({})
// [D11-A] 用户、项和原业务日共同区分待核实动作，见 docs/decisions(决策).md#D11。
const storageKey = 'habit-check-in-pending:' + props.userId
const pending = ref(readPending())
// 每个用户有自己的组件实例。这份确认状态只用于同一实例中对应项和观察日的列表合并。
const confirmedSnapshots = new Map()
let csrfToken = ''
let listRequest = 0
let writeEpoch = 0

function readPending() {
  try {
    const stored = JSON.parse(sessionStorage.getItem(storageKey) || '[]')
    return Array.isArray(stored) ? stored.filter(item => item &&
      /^[1-9][0-9]*$/.test(item.habitId) && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(item.date)) : []
  } catch {
    return []
  }
}

function pendingFor(habitId) {
  return pending.value.filter(intent => intent.habitId === habitId)
}

function pendingKey(habitId, date) {
  return habitId + ':' + date
}

function isPending(habitId, date) {
  return pending.value.some(intent => intent.habitId === habitId && intent.date === date)
}

function todayStatus(habit) {
  if (habit.checkedInToday) return '今日已打卡'
  if (busy[pendingKey(habit.id, businessDate.value)] === 'submit') return '正在打卡'
  return isPending(habit.id, businessDate.value) ? '今日待核实' : '今日待打卡'
}

function setPending(habitId, date, retain) {
  const other = pending.value.filter(item => item.habitId !== habitId || item.date !== date)
  pending.value = retain ? [...other, { habitId, date }] : other
  try {
    sessionStorage.setItem(storageKey, JSON.stringify(pending.value))
  } catch {
    // 浏览器禁用会话存储时，本页仍可用内存里的原日期核实。
  }
}

function endSession({ statusCode, data }) {
  if (statusCode !== 401) return false
  if (data?.code !== 'AUTH_SESSION_REQUIRED' && data?.code !== 'AUTH_SESSION_INVALID_OR_EXPIRED') return false
  csrfToken = ''
  emit('session-ended', data.message)
  return true
}

function coversSnapshot(candidate, confirmed) {
  return (!confirmed.checkedInToday || candidate.checkedInToday) && candidate.streakDays >= confirmed.streakDays
}

function withSnapshot(habit, snapshot) {
  return { ...habit, checkedInToday: snapshot.checkedInToday, streakDays: snapshot.streakDays }
}

function mergeListSnapshots(date, incoming) {
  for (const [key, snapshot] of confirmedSnapshots) {
    if (snapshot.businessDate < date) confirmedSnapshots.delete(key)
  }
  return incoming.map(habit => {
    const key = pendingKey(habit.id, date)
    const confirmed = confirmedSnapshots.get(key)
    if (!confirmed) return habit
    // [D12-A][D14-A] GET 可能命中同日旧值。按整组比较，用同等或更新的快照结束保护。
    if (coversSnapshot(habit, confirmed)) {
      confirmedSnapshots.delete(key)
      return habit
    }
    return withSnapshot(habit, confirmed)
  })
}

function rememberConfirmation(habitId, state) {
  // [D11-A] 回包的记录日期可确认旧动作，只有匹配观察日的状态能更新该日卡片。
  if (state.businessDate < businessDate.value) return
  const key = pendingKey(habitId, state.businessDate)
  const previous = confirmedSnapshots.get(key)
  if (previous && !coversSnapshot(state, previous)) return
  confirmedSnapshots.set(key, state)
  if (state.businessDate === businessDate.value) {
    items.value = items.value.map(habit =>
      habit.id === habitId && coversSnapshot(state, habit) ? withSnapshot(habit, state) : habit)
  }
}

async function loadHabits() {
  const request = ++listRequest
  const epoch = writeEpoch
  loading.value = true
  listError.value = ''
  try {
    const response = await apiRequest('/habits')
    // [D11-A] 丢弃在本轮成功写入前发出的晚到查询，见 docs/decisions(决策).md#D11。
    if (request !== listRequest || epoch !== writeEpoch) return
    if (endSession(response)) return
    const { statusCode, data } = response
    if (statusCode === 200 && data?.code === 'OK' && Array.isArray(data.data?.items)) {
      if (data.data.businessDate >= businessDate.value) {
        businessDate.value = data.data.businessDate
        items.value = mergeListSnapshots(data.data.businessDate, data.data.items)
        hasLoaded.value = true
      }
    } else {
      listError.value = data?.message || '暂时无法读取打卡项，请刷新重试。'
    }
  } catch {
    if (request === listRequest && epoch === writeEpoch) {
      listError.value = '暂时无法读取打卡项，请刷新重试。'
    }
  } finally {
    if (request === listRequest) loading.value = false
  }
}

async function write(path, data) {
  // [D8-A] 令牌只留在组件内存，丢失时按当前 Cookie 重新领取，见 docs/decisions(决策).md#D8。
  if (!csrfToken) {
    const response = await apiRequest('/auth/csrf', {
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: {},
    })
    if (response.statusCode !== 200 || response.data?.code !== 'OK' || !response.data.data?.csrfToken) return response
    csrfToken = response.data.data.csrfToken
  }
  const response = await apiRequest(path, {
    method: 'POST',
    header: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
    data,
  })
  if (response.data?.code === 'AUTH_CSRF_REQUIRED' || response.data?.code === 'AUTH_CSRF_INVALID') csrfToken = ''
  return response
}

async function checkIn(habitId, date) {
  const key = pendingKey(habitId, date)
  if (!date || busy[key]) return
  const wasPending = isPending(habitId, date)
  busy[key] = wasPending ? 'verify' : 'submit'
  // 刷新期间也要保留点击时的日期。跨日核实仍提交原日期，由 API 判断是否已有记录。
  setPending(habitId, date, true)
  delete checkInMessages[habitId]
  try {
    const response = await write('/habits/' + habitId + '/check-ins', { expectedBusinessDate: date })
    const { statusCode, data } = response
    if (endSession(response)) {
      if (!wasPending) setPending(habitId, date, false)
      return
    }
    const result = data?.data
    const created = statusCode === 201 && result?.outcome === 'CREATED'
    const alreadyRecorded = statusCode === 200 && result?.outcome === 'ALREADY_RECORDED'
    if ((created || alreadyRecorded) && data?.code === 'OK' && result.checkIn?.checkInDate === date && result.state) {
      writeEpoch++
      setPending(habitId, date, false)
      checkInMessages[habitId] = {
        kind: 'success',
        text: date + (created ? ' 打卡成功' : ' 打卡已记录'),
      }
      rememberConfirmation(habitId, result.state)
      await loadHabits()
    } else if (statusCode === 409 && data?.code === 'CHECKIN_BUSINESS_DATE_CHANGED') {
      checkInMessages[habitId] = { kind: 'warning', text: date + ' 打卡尚未核实，请确认当前日期。' }
      await loadHabits()
    } else if (statusCode >= 400 && statusCode < 500 && statusCode !== 408) {
      // 这次请求已明确被拒绝，但要保留之前尚未核实的同日动作。
      if (!wasPending) setPending(habitId, date, false)
      const csrfRejected = data?.code === 'AUTH_CSRF_REQUIRED' || data?.code === 'AUTH_CSRF_INVALID'
      checkInMessages[habitId] = {
        kind: 'error',
        text: csrfRejected ? '请重试以获取写请求令牌。' : data?.message || '打卡未提交，请重试。',
      }
    } else {
      checkInMessages[habitId] = { kind: 'warning', text: '打卡结果待核实，请核实原打卡。' }
    }
  } catch {
    checkInMessages[habitId] = { kind: 'warning', text: '网络连接异常，恢复后请核实原打卡。' }
  } finally {
    delete busy[key]
  }
}

function openCreate() {
  createMessage.value = ''
  createOpen.value = true
}

function closeCreate() {
  createMessage.value = ''
  createOpen.value = false
}

async function createHabit() {
  if (creating.value || loading.value) return
  creating.value = true
  createMessage.value = ''
  try {
    const response = await write('/habits', { name: name.value })
    if (endSession(response)) return
    const { statusCode, data } = response
    if (statusCode === 201 && data?.code === 'OK') {
      name.value = ''
      createOpen.value = false
      createMessage.value = '打卡项已创建'
      await loadHabits()
    } else if (data?.code === 'AUTH_CSRF_REQUIRED' || data?.code === 'AUTH_CSRF_INVALID') {
      createMessage.value = '请重新提交以获取写请求令牌。'
    } else if (data?.code === 'REQUEST_INVALID') {
      createMessage.value = '名称去掉首尾空白后应为 1–50 个字符，且不能包含控制字符。'
    } else {
      createMessage.value = data?.message || '暂时无法创建打卡项，请重试。'
    }
  } catch {
    createMessage.value = '暂时无法确认创建结果，请刷新列表后重试。'
  } finally {
    creating.value = false
  }
}

function refreshVisible() {
  if (document.visibilityState === 'visible') loadHabits()
}

onMounted(() => {
  loadHabits()
  document.addEventListener('visibilitychange', refreshVisible)
})
onBeforeUnmount(() => document.removeEventListener('visibilitychange', refreshVisible))
</script>

<style scoped>
.habits,
.list-section,
.form,
.list,
.item {
  display: flex;
  flex-direction: column;
}

.habits {
  box-sizing: border-box;
  min-width: 0;
  gap: 20px;
}

.progress-card {
  padding: clamp(20px, 5vw, 28px);
  border-radius: 20px;
  background: radial-gradient(ellipse at right top, #236e59, transparent 70%), #104a3d;
  color: #fff;
}

.progress-header,
.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
}

.progress-title,
.business-date {
  color: #e2f3ea;
  font-size: 14px;
}

.progress-title {
  font-weight: 650;
}

.business-date {
  font-variant-numeric: tabular-nums;
}

.progress-value {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 8px;
  font-variant-numeric: tabular-nums;
}

.completed-count {
  font-size: 48px;
  font-weight: 740;
  line-height: 1.05;
}

.total-count {
  color: #d7eee4;
  font-size: 18px;
}

.progress-track {
  height: 7px;
  margin-top: 15px;
  overflow: hidden;
  border-radius: 8px;
  background: #ffffff39;
}

.progress-fill {
  height: 100%;
  border-radius: inherit;
  background: #a4edc8;
}

.progress-placeholder {
  display: block;
  margin-top: 20px;
  color: #e2f3ea;
  font-size: 15px;
}

.list-section,
.list {
  gap: 12px;
}

.heading {
  font-size: 19px;
  font-weight: 730;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
}

.button {
  box-sizing: border-box;
  display: inline-flex;
  justify-content: center;
  align-items: center;
  min-height: 44px;
  margin: 0;
  padding: 7px 12px;
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
  background: var(--accent);
  color: #fff;
}

.button-primary:not([disabled]):hover {
  background: var(--accent-hover);
}

.button-secondary {
  border-color: #bdcfc5;
  background: var(--surface);
  color: var(--accent);
}

.button-quiet {
  background: transparent;
  color: var(--muted);
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

.item,
.form,
.empty-state {
  padding: clamp(14px, 3.8vw, 20px);
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--surface);
}

.item,
.form {
  gap: 12px;
}

.item-complete {
  border-color: #cce1d4;
  background: #f6fbf7;
}

.item-overview {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
}

.item-label,
.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.item-name {
  font-size: 18px;
  font-weight: 700;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.item-status,
.streak-label {
  color: var(--muted);
  font-size: 13px;
}

.item-complete .item-status {
  color: var(--accent);
  font-weight: 650;
}

.streak {
  display: grid;
  justify-items: end;
  gap: 2px;
  min-width: 45px;
  color: var(--accent);
  text-align: right;
}

.streak-value {
  display: flex;
  align-items: baseline;
  gap: 3px;
  font-size: 13px;
}

.streak-days {
  font-size: 29px;
  font-weight: 740;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.check-in-button {
  width: 100%;
  min-height: 48px;
}

.field {
  box-sizing: border-box;
  height: 48px;
  padding: 0 12px;
  border: 1px solid #b7c5bd;
  border-radius: 10px;
  font-size: 16px;
}

.form-field {
  font-size: 14px;
}

.form-actions {
  justify-content: flex-end;
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding-block: 28px;
  text-align: center;
}

.empty-title {
  font-weight: 650;
}

.pending,
.list-notice {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--notice);
  color: #783f2b;
  font-size: 14px;
  overflow-wrap: anywhere;
}

.list-loading {
  background: var(--surface);
  color: var(--muted);
}

.feedback {
  font-size: 14px;
  overflow-wrap: anywhere;
}

.feedback-success {
  color: var(--accent);
}

.feedback-warning {
  color: #783f2b;
}

.feedback-error {
  color: var(--error);
}
</style>
