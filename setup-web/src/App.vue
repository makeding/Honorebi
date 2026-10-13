<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { blankSnapshot, canSave as canSaveConnection, connectionPlaceholder, isConfigured, normalizeSnapshot, resolveMainSystem, saveBody, sourceIds, validSource, type ConnectionSnapshot, type SourceId } from './connection'
import honoRebiMark from '../../app/src/main/res/drawable/honorebi_mark.png'

const params = new URLSearchParams(window.location.search)
const token = params.get('token') ?? ''
const sourceParam = params.get('source')
const scope = sourceIds.includes(sourceParam as SourceId) ? sourceParam as SourceId : null

const snapshot = ref<ConnectionSnapshot>(blankSnapshot(scope))
const state = ref<'loading' | 'ready' | 'failure'>('loading')
const saving = ref(false)
const saved = ref(false)
const error = ref('')
const expired = ref(false)
const reopenSetupMessage = 'テレビで「スマホで設定」を開き直し、表示されたQRコードからこのページを開いてください。'
const openId = ref<SourceId | null>(scope)

const visibleSources = computed(() => snapshot.value.scope ? snapshot.value.sources.filter((source) => source.id === snapshot.value.scope) : snapshot.value.sources)
const configuredSources = computed(() => snapshot.value.sources.filter(isConfigured))
const canChooseMain = computed(() => configuredSources.value.length >= 2)
const mainSystem = computed({
  get: () => resolveMainSystem(snapshot.value.sources, snapshot.value.mainSystem),
  set: (value: SourceId | null) => { snapshot.value.mainSystem = value },
})
const controlsLocked = computed(() => saving.value || saved.value || expired.value)

watch(() => snapshot.value.sources.map((source) => `${source.id}:${source.host}:${source.port}`).join('|'), () => {
  snapshot.value.mainSystem = resolveMainSystem(snapshot.value.sources, snapshot.value.mainSystem)
})

function apiError(response: Response, payload: unknown): string {
  if (typeof payload === 'object' && payload !== null) {
    const body = payload as { message?: string; error?: string; code?: string }
    if (body.code === 'SETUP_EXPIRED') { expired.value = true; return `${reopenSetupMessage} (SETUP_EXPIRED)` }
    const message = body.message ?? body.error
    if (message && body.code) return `${message} (${body.code})`
    if (message) return message
    if (body.code) return `設定を保存できませんでした (${body.code})`
  }
  return `設定を保存できませんでした (HTTP ${response.status})`
}

async function request(method: 'GET' | 'POST', body?: unknown) {
  const response = await fetch('/api/connections', {
    method,
    headers: { 'X-Setup-Token': token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new Error(apiError(response, payload))
  return payload
}

async function load() {
  state.value = 'loading'
  error.value = ''
  saved.value = false
  expired.value = false
  if (!token) {
    expired.value = true
    error.value = reopenSetupMessage
    state.value = 'failure'
    return
  }
  try {
    const result = await request('GET') as Partial<ConnectionSnapshot>
    snapshot.value = normalizeSnapshot(result, scope)
    if (snapshot.value.scope) openId.value = snapshot.value.scope
    snapshot.value.mainSystem = resolveMainSystem(snapshot.value.sources, snapshot.value.mainSystem)
    state.value = 'ready'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '設定を読み込めませんでした。'
    state.value = 'failure'
  }
}

const invalidSource = computed(() => snapshot.value.sources.find((source) => !validSource(source)) ?? null)
const canSave = computed(() => canSaveConnection(snapshot.value.sources, mainSystem.value) && !controlsLocked.value)
const mainPrompt = computed(() => {
  if (configuredSources.value.length === 0) return '未設定'
  if (configuredSources.value.length === 1) return `${configuredSources.value[0].label} を自動で利用します`
  return 'テレビ視聴に使う接続先を選択してください'
})

async function save() {
  if (!canSave.value) return
  saving.value = true
  error.value = ''
  saved.value = false
  try {
    const result = await request('POST', saveBody(snapshot.value.sources, resolveMainSystem(snapshot.value.sources, snapshot.value.mainSystem))) as Partial<ConnectionSnapshot>
    snapshot.value = normalizeSnapshot(result, scope)
    if (snapshot.value.scope) openId.value = snapshot.value.scope
    snapshot.value.mainSystem = resolveMainSystem(snapshot.value.sources, snapshot.value.mainSystem)
    saved.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '設定を保存できませんでした。'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header>
      <div class="brand"><img :src="honoRebiMark" alt="HonoRebi" /><span>HonoRebi</span></div>
      <h1>接続設定</h1>
    </header>

    <section class="panel" aria-live="polite">
      <div v-if="state === 'loading'" class="state-slot"><span class="spinner" /> 接続設定を読み込んでいます…</div>
      <div v-else-if="state === 'failure'" class="state-slot failure">
        <strong>{{ expired ? '設定ページを開き直してください' : '設定を読み込めませんでした' }}</strong><span>{{ error }}</span>
        <button v-if="!expired" class="secondary" type="button" @click="load">再試行</button>
      </div>
      <template v-else>
        <section class="section-heading">
          <h2>視聴システム</h2>
          <p>使う接続先を選び、必要な情報を入力してください。</p>
        </section>

        <div class="source-list">
          <article v-for="source in visibleSources" :key="source.id" class="source-card" :class="{ expanded: openId === source.id }">
            <button class="source-summary" type="button" :aria-expanded="openId === source.id" :disabled="controlsLocked" @click="openId = openId === source.id ? null : source.id">
              <span class="source-icon">{{ source.id === 'KONOMITV' ? '▦' : source.id === 'EDCB' ? '◫' : '◉' }}</span>
              <span><strong>{{ source.label }}</strong><small>{{ isConfigured(source) ? `${source.host}:${source.port}` : '未設定' }}</small></span>
              <span class="chevron">⌄</span>
            </button>
            <div v-if="openId === source.id" class="source-fields">
              <label>サーバーのアドレス
                <input v-model="source.host" :disabled="controlsLocked" inputmode="url" autocomplete="url" :placeholder="connectionPlaceholder" />
              </label>
              <label>ポート番号
                <input v-model="source.port" :disabled="controlsLocked" type="text" inputmode="numeric" />
              </label>
              <label v-if="source.id === 'EDCB'">EDCB HTTP ポート
                <input v-model="source.httpPort" :disabled="controlsLocked" type="text" inputmode="numeric" />
              </label>
          <p v-if="!validSource(source)" class="field-error">アドレスとポート番号を確認してください。</p>
              <p v-if="source.id === 'KONOMITV'" class="detail-note">HonomiTV アカウントの設定はテレビで行えます。</p>
            </div>
          </article>
        </div>

        <section class="main-picker" :class="{ disabled: !canChooseMain }">
          <div><h2>メインシステム</h2><p>{{ mainPrompt }}</p></div>
          <div class="radio-list">
            <label v-for="source in configuredSources" :key="source.id" class="radio-option">
              <input v-model="mainSystem" type="radio" name="main-system" :value="source.id" :disabled="!canChooseMain || controlsLocked" />
              <span>{{ source.label }}</span>
            </label>
          </div>
        </section>

        <div class="feedback-slot" aria-live="polite">
          <p v-if="error" class="save-error" role="alert">{{ error }}</p>
          <p v-else-if="saved" class="success" role="status">テレビの接続設定を更新しました。</p>
        </div>
        <button class="save" type="button" :disabled="!canSave" @click="save">
          {{ saving ? '保存しています…' : 'テレビに保存' }}
        </button>
      </template>
    </section>
  </main>
</template>
