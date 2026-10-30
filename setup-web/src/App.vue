<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  blankSnapshot,
  canSave as canSaveConnection,
  connectionPlaceholder,
  isConfigured,
  normalizeSnapshot,
  preferredSourceIds,
  resolveMainSystem,
  saveBody,
  setupTokenFromHash,
  sourceIds,
  validSmbServer,
  validAccess,
  validSource,
  type ConnectionSnapshot,
  type SmbServer,
  type SourceId,
} from "./connection";
import { draftKey, editableSettings, createDraft, applyDraft } from "./draft";
import { pairingConnectionChanged, pairingInputError, preparePairingConnection } from './pairing';
import honoRebiMark from "../../app/src/main/res/drawable/honorebi_mark.png";

const token = setupTokenFromHash(window.location.hash);
const sourceParam = new URLSearchParams(window.location.search).get("source");
const initialSource = sourceIds.includes(sourceParam as SourceId)
  ? (sourceParam as SourceId)
  : null;
const snapshot = ref<ConnectionSnapshot>(blankSnapshot());
const state = ref<"loading" | "ready" | "failure">("loading"),
  saving = ref(false),
  saved = ref(false),
  dirty = ref(false),
  restoredDraft = ref(false),
  error = ref(""),
  expired = ref(false);
const openId = ref<SourceId | null>(initialSource),
  removeTarget = ref<SmbServer | null>(null);
const accountState = ref<
    "idle" | "pairing" | "waiting" | "success" | "expired" | "error"
  >("idle"),
  accountError = ref(""),
  verificationUrl = ref(""),
  userCode = ref("");
let polling: number | undefined;
let baselineSnapshot = blankSnapshot();
let baseline = JSON.stringify(editableSettings(baselineSnapshot));
let applyingSnapshot = false;
const reopenSetupMessage =
  "テレビで「スマホで設定」を開き直し、表示されたQRコードからこのページを開いてください。";
const configuredSources = computed(() =>
  snapshot.value.sources.filter(isConfigured),
);
const canChooseMain = computed(() => configuredSources.value.length >= 2);
const controlsLocked = computed(() => saving.value || expired.value || accountState.value === 'pairing');
const mainSystem = computed({
  get: () =>
    resolveMainSystem(snapshot.value.sources, snapshot.value.mainSystem),
  set: (value: SourceId | null) => {
    snapshot.value.mainSystem = value;
  },
});
const accountPending = ref(false);
const pairingValidation = computed(() => pairingInputError(snapshot.value));
const invalidSmb = computed(
  () =>
    snapshot.value.smbServers.find((server) => !validSmbServer(server)) ?? null,
);
const canSave = computed(
  () =>
    canSaveConnection(snapshot.value.sources, mainSystem.value) &&
    !invalidSmb.value &&
    validAccess(snapshot.value.cloudflareAccess) &&
    !controlsLocked.value,
);
const mainPrompt = computed(() =>
  configuredSources.value.length === 0
    ? "未設定"
    : configuredSources.value.length === 1
      ? `${configuredSources.value[0].label} を自動で利用します`
      : "テレビ視聴に使う接続先を選択してください",
);
function saveDraft() {
  try {
    sessionStorage.setItem(
      draftKey,
      JSON.stringify(createDraft(baselineSnapshot, snapshot.value)),
    );
  } catch {
    /* Storage may be unavailable. Editing still works. */
  }
}
function clearDraft() {
  try {
    sessionStorage.removeItem(draftKey);
  } catch {
    /* Storage may be unavailable. */
  }
}
function restoreDraft() {
  try {
    const raw = sessionStorage.getItem(draftKey);
    if (!raw) return;
    if (raw.length > 65536) {
      clearDraft();
      return;
    }
    applyingSnapshot = true;
    snapshot.value = applyDraft(snapshot.value, JSON.parse(raw));
    snapshot.value.mainSystem = resolveMainSystem(
      snapshot.value.sources,
      snapshot.value.mainSystem,
    );
    dirty.value = JSON.stringify(editableSettings(snapshot.value)) !== baseline;
    restoredDraft.value = dirty.value;
    if (dirty.value) saveDraft();
    else clearDraft();
  } catch {
    clearDraft();
  } finally {
    applyingSnapshot = false;
  }
}
function discardDraft() {
  clearDraft();
  restoredDraft.value = false;
  load();
}
function edited() {
  if (state.value !== "ready" || applyingSnapshot) return;
  dirty.value = JSON.stringify(editableSettings(snapshot.value)) !== baseline;
  if (dirty.value) {
    saved.value = false;
    error.value = "";
    saveDraft();
  } else {
    clearDraft();
    restoredDraft.value = false;
  }
}
watch(() => JSON.stringify(editableSettings(snapshot.value)), edited, {
  flush: "sync",
});
watch(
  () =>
    snapshot.value.sources.map((s) => `${s.id}:${s.host}:${s.port}`).join("|"),
  () => {
    snapshot.value.mainSystem = resolveMainSystem(
      snapshot.value.sources,
      snapshot.value.mainSystem,
    );
  },
);
function apiError(response: Response, payload: unknown): string {
  const body = payload as { message?: string; code?: string } | null;
  if (body?.code === "SETUP_EXPIRED") {
    expired.value = true;
    stopPolling();
    return `${reopenSetupMessage} (SETUP_EXPIRED)`;
  }
  return body?.message
    ? `${body.message}${body.code ? ` (${body.code})` : ""}`
    : `設定を保存できませんでした (HTTP ${response.status})`;
}
async function request(path: string, method: 'GET' | 'POST', body?: unknown) {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 20000)
  try {
    const response = await fetch(path, {
      method, signal: controller.signal,
      headers: { 'X-Setup-Token': token, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })
    const payload = await response.json().catch(() => null)
    if (!response.ok) throw new Error(apiError(response, payload))
    return payload
  } catch (cause) {
    if (controller.signal.aborted) throw new Error('テレビとの接続がタイムアウトしました。接続を確認して再試行してください。 (REQUEST_TIMEOUT)')
    if (cause instanceof TypeError) throw new Error('テレビに接続できませんでした。接続を確認して再試行してください。 (NETWORK_ERROR)')
    throw cause
  } finally { window.clearTimeout(timeout) }
}
async function load() {
  state.value = "loading";
  error.value = "";
  saved.value = false;
  expired.value = false;
  if (!token) {
    expired.value = true;
    error.value = reopenSetupMessage;
    state.value = "failure";
    return;
  }
  try {
    applyingSnapshot = true;
    snapshot.value = normalizeSnapshot(
      await request("/api/connections", "GET"),
    );
    snapshot.value.mainSystem = resolveMainSystem(
      snapshot.value.sources,
      snapshot.value.mainSystem,
    );
    baselineSnapshot = normalizeSnapshot(snapshot.value);
    baseline = JSON.stringify(editableSettings(baselineSnapshot));
    dirty.value = false;
    applyingSnapshot = false;
    restoreDraft();
    state.value = "ready";
    try {
      const account = (await request("/api/connections/account", "GET")) as {
        userName?: string | null;
        status?: "idle" | "waiting" | "success" | "expired";
      };
      snapshot.value.account.userName = account.userName ?? null;
      accountState.value =
        account.status ?? (account.userName ? "success" : "idle");
      if (accountState.value === "waiting") {
        stopPolling();
        polling = window.setInterval(checkPairing, 2000);
      }
    } catch (cause) {
      accountError.value =
        cause instanceof Error
          ? cause.message
          : "アカウント状態を確認できませんでした。";
    }
  } catch (cause) {
    applyingSnapshot = false;
    error.value =
      cause instanceof Error ? cause.message : "設定を読み込めませんでした。";
    state.value = "failure";
  }
}
async function save() {
  if (!canSave.value) return;
  saving.value = true;
  error.value = "";
  try {
    applyingSnapshot = true;
    snapshot.value = normalizeSnapshot(
      await request("/api/connections", "POST", saveBody(snapshot.value)),
    );
    snapshot.value.mainSystem = resolveMainSystem(
      snapshot.value.sources,
      snapshot.value.mainSystem,
    );
    baselineSnapshot = normalizeSnapshot(snapshot.value);
    baseline = JSON.stringify(editableSettings(baselineSnapshot));
    dirty.value = false;
    saved.value = true;
    restoredDraft.value = false;
    clearDraft();
  } catch (cause) {
    error.value =
      cause instanceof Error ? cause.message : "設定を保存できませんでした。";
  } finally {
    applyingSnapshot = false;
    saving.value = false;
  }
}
function newSmbId() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}
function addSmb() {
  snapshot.value.smbServers.push({
    id: newSmbId(),
    name: "",
    ip: "",
    port: "445",
    user: "",
    password: null,
    passwordConfigured: false,
  });
}
const addSmbButton = ref<HTMLButtonElement | null>(null)
const removeCancelButton = ref<HTMLButtonElement | null>(null)
let removeOrigin: HTMLElement | null = null
async function requestRemove(server: SmbServer, event: MouseEvent) {
  removeOrigin = event.currentTarget as HTMLElement
  removeTarget.value = server
  await nextTick()
  removeCancelButton.value?.focus()
}
async function cancelRemove() {
  removeTarget.value = null
  await nextTick()
  removeOrigin?.focus()
}
function handleConfirmKey(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); cancelRemove(); return }
  if (event.key === 'Tab') {
    const buttons = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button'))
    const active = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const target = event.shiftKey ? (active - 1 + buttons.length) % buttons.length : (active + 1) % buttons.length
    event.preventDefault(); buttons[target]?.focus()
  }
}
async function confirmRemove() {
  if (removeTarget.value)
    snapshot.value.smbServers = snapshot.value.smbServers.filter(
      (s) => s.id !== removeTarget.value?.id,
    );
  removeTarget.value = null;
  await nextTick(); addSmbButton.value?.focus();
}
function stopPolling() {
  if (polling !== undefined) window.clearInterval(polling);
  polling = undefined;
}
async function cancelPairing() {
  if (accountPending.value) return;
  accountPending.value = true;
  try {
    await request("/api/connections/account/pair/cancel", "POST", {});
    stopPolling();
    accountState.value = "idle";
    verificationUrl.value = "";
    userCode.value = "";
  } catch (cause) {
    accountError.value =
      cause instanceof Error
        ? cause.message
        : "連携を中止できませんでした。再試行してください。";
  } finally {
    accountPending.value = false;
  }
}
async function startPairing() {
  if (controlsLocked.value || accountPending.value || accountState.value === 'waiting') return;
  if (pairingValidation.value) { accountError.value = pairingValidation.value; return; }
  accountError.value = "";
  accountState.value = "pairing";
  try {
    if (pairingConnectionChanged(snapshot.value, baselineSnapshot)) {
      const prepared = await preparePairingConnection(snapshot.value, baselineSnapshot,
        async () => normalizeSnapshot(await request('/api/connections', 'GET')),
        async settings => normalizeSnapshot(await request('/api/connections', 'POST', saveBody(settings))));
      applyingSnapshot = true;
      baselineSnapshot = prepared.saved;
      baseline = JSON.stringify(editableSettings(baselineSnapshot));
      snapshot.value = prepared.local;
      applyingSnapshot = false;
      edited();
    }
    const result = (await request("/api/connections/account/pair", "POST")) as {
      verificationUrl: string;
      userCode: string;
    };
    if (typeof result.verificationUrl !== 'string' || !result.verificationUrl.trim())
      throw new Error('認証ページを開けませんでした。もう一度連携してください。 (INVALID_VERIFICATION_URL)');
    verificationUrl.value = result.verificationUrl;
    userCode.value = result.userCode;
    accountState.value = "waiting";
    if (dirty.value) saveDraft();
    window.location.assign(result.verificationUrl);
  } catch (cause) {
    accountError.value =
      cause instanceof Error
        ? cause.message
        : "アカウント連携を開始できませんでした。";
    accountState.value = "error";
  }
}
let checkingPairing = false;
async function checkPairing() {
  if (accountState.value !== "waiting" || checkingPairing) return;
  checkingPairing = true;
  try {
    const result = (await request("/api/connections/account/pair", "GET")) as {
      userName?: string | null;
      status: "waiting" | "success" | "expired";
    };
    if (result.status === "success") {
      snapshot.value.account.userName = result.userName ?? null;
      accountState.value = "success";
      stopPolling();
    } else if (result.status === "expired") {
      accountState.value = "expired";
      stopPolling();
    }
  } catch (cause) {
    accountError.value =
      cause instanceof Error
        ? cause.message
        : "アカウント連携の確認に失敗しました。";
    accountState.value = "error";
    stopPolling();
  } finally { checkingPairing = false; }
}
async function logout() {
  if (
    controlsLocked.value ||
    accountPending.value ||
    !window.confirm("HonomiTV からログアウトしますか？")
  )
    return;
  accountPending.value = true;
  accountError.value = "";
  try {
    await request("/api/connections/account/logout", "POST", {});
    snapshot.value.account.userName = null;
    accountState.value = "idle";
  } catch (cause) {
    accountError.value =
      cause instanceof Error ? cause.message : "ログアウトできませんでした。";
  } finally {
    accountPending.value = false;
  }
}
onMounted(load);
onBeforeUnmount(stopPolling);
</script>

<template>
  <main class="page-shell">
    <header>
      <div class="brand">
        <img :src="honoRebiMark" alt="HonoRebi" /><span>HonoRebi</span>
      </div>
      <h1>接続設定</h1>
    </header>
    <section class="panel" aria-live="polite" :inert="!!removeTarget">
      <div v-if="state === 'loading'" class="state-slot">
        <span class="spinner" /> 接続設定を読み込んでいます…
      </div>
      <div v-else-if="state === 'failure'" class="state-slot failure">
        <strong>{{
          expired
            ? "設定ページを開き直してください"
            : "設定を読み込めませんでした"
        }}</strong
        ><span>{{ error }}</span
        ><button v-if="!expired" class="secondary" type="button" @click="load">
          再試行
        </button>
      </div>
      <template v-else
        ><section class="section-heading">
          <h2>視聴システム</h2>
          <p>各システムを開いて接続先を入力します。</p>
        </section>
        <div class="source-list">
          <article
            v-for="source in snapshot.sources"
            :key="source.id"
            class="source-card"
            :class="{ expanded: openId === source.id }"
          >
            <button
              class="source-summary"
              type="button"
              :aria-expanded="openId === source.id"
              :disabled="controlsLocked"
              @click="openId = openId === source.id ? null : source.id"
            >
              <span
                ><strong>{{ source.label }}</strong
                ><small>{{
                  isConfigured(source)
                    ? `${source.host}:${source.port}`
                    : "未設定"
                }}</small></span
              ><span class="chevron">⌄</span>
            </button>
            <div v-if="openId === source.id" class="source-fields">
              <label
                >サーバーのアドレス<input
                  v-model="source.host"
                  :disabled="controlsLocked"
                  inputmode="url"
                  :placeholder="source.id === 'KONOMITV' ? connectionPlaceholder : source.id === 'EDCB' ? '192.168.1.10' : 'http://192.168.1.10'" /></label
              ><label
                >ポート番号<input
                  v-model="source.port"
                  :disabled="controlsLocked"
                  inputmode="numeric" /></label
              ><label v-if="source.id === 'EDCB'"
                >EDCB HTTP ポート<input
                  v-model="source.httpPort"
                  :disabled="controlsLocked"
                  inputmode="numeric" /></label
              ><label v-if="source.id === 'EDCB'"
                >録画番組の再生方法<select
                  v-model="snapshot.edcbRecordPlayMethod"
                  :disabled="controlsLocked"
                >
                  <option value="DIRECT">直接再生</option>
                  <option value="API">API 経由</option>
                </select></label
              >
              <p class="field-error field-feedback">{{ !validSource(source) ? "アドレスとポート番号を確認してください。" : "" }}</p>
              <section v-if="source.id === 'KONOMITV'" class="account-box">
                <h3>HonomiTV アカウント</h3>
                <p v-if="snapshot.account.userName">
                  ログイン中: {{ snapshot.account.userName }}
                </p>
                <template v-else
                  ><p>
                    HonomiTV を連携すると、アカウントの視聴情報を利用できます。
                  </p>
                  <a
                    v-if="verificationUrl"
                    :href="verificationUrl"
                    target="_blank"
                    rel="noopener"
                    >認証ページを開く</a
                  ><code v-if="userCode">{{ userCode }}</code
                  ><button
                    class="secondary"
                    type="button"
                    :disabled="
                      controlsLocked ||
                      accountPending ||
                      !!pairingValidation ||
                      accountState === 'pairing' ||
                      accountState === 'waiting'
                    "
                    @click="startPairing"
                  >
                    {{
                      accountState === 'pairing'
                        ? '認証ページを開いています…'
                        : accountState === "waiting"
                        ? "連携を確認中…"
                        : accountState === "expired" || accountState === "error"
                          ? "もう一度連携"
                          : "アカウントを連携"
                    }}</button
                  ><button
                    v-if="accountState === 'waiting'"
                    class="secondary"
                    type="button"
                    :disabled="controlsLocked || accountPending"
                    @click="cancelPairing"
                  >
                    連携を中止
                  </button></template
                ><button
                  v-if="snapshot.account.userName"
                  class="secondary danger"
                  type="button"
                  :disabled="controlsLocked || accountPending"
                  @click="logout"
                >
                  ログアウト
                </button>
                <p class="field-error account-feedback" role="alert">
                  {{ accountError || pairingValidation || (accountState === 'expired' ? '連携の有効期限が切れました。もう一度連携してください。' : '') }}
                </p>
              </section>
            </div>
          </article>
        </div>
        <section class="settings-block">
          <h2>Cloudflare Access</h2>
          <p>
            Cloudflare トンネル経由で接続する場合は、テレビ側の Access 認証トークンを入力します。
          </p>
          <label
            >Client ID<input
              v-model="snapshot.cloudflareAccess.clientId"
              :disabled="controlsLocked"
              autocomplete="off" /></label
          ><label
            >Client Secret<input
              v-model="snapshot.cloudflareAccess.clientSecret"
              :disabled="controlsLocked"
              type="password"
              :placeholder="
                snapshot.cloudflareAccess.secretConfigured
                  ? '設定済み（変更する場合のみ入力）'
                  : ''
              "
              autocomplete="new-password"
          /></label>
          <p class="field-error field-feedback">{{ !validAccess(snapshot.cloudflareAccess) ? "Client ID と Client Secret を両方入力するか、両方消去してください。" : "" }}</p>
          <button class="secondary" type="button" :disabled="controlsLocked" @click="snapshot.cloudflareAccess.clientId = ''; snapshot.cloudflareAccess.clientSecret = ''">トークンを消去</button>
        </section>
        <section class="main-picker" :class="{ disabled: !canChooseMain }">
          <div>
            <h2>メインシステム</h2>
            <p>{{ mainPrompt }}</p>
          </div>
          <div class="radio-list">
            <label
              v-for="source in configuredSources"
              :key="source.id"
              class="radio-option"
              ><input
                v-model="mainSystem"
                type="radio"
                name="main-system"
                :value="source.id"
                :disabled="!canChooseMain || controlsLocked"
              /><span>{{ source.label }}</span></label
            >
          </div>
        </section>
        <section class="settings-block">
          <h2>ライブ視聴の優先</h2>
          <p>ライブ視聴を開始する際に優先するシステムです。</p>
          <select v-model="snapshot.preferredSource" :disabled="controlsLocked">
            <option
              v-for="source in snapshot.sources"
              :key="source.id"
              :value="source.id === 'MIRAKURUN_ONLY' ? 'MIRAKURUN' : source.id"
            >
              {{
                source.id === "KONOMITV"
                  ? "メインシステムに従う（トランスコード）"
                  : source.label
              }}
            </option>
          </select>
        </section>
        <section class="settings-block">
          <div class="block-title">
            <div>
              <h2>SMB サーバー</h2>
              <p>録画ファイルを参照するサーバーを追加できます。</p>
            </div>
            <button
              class="secondary"
              type="button"
              :disabled="controlsLocked"
              @click="addSmb"
              ref="addSmbButton"
            >
              追加
            </button>
          </div>
          <div
            v-for="server in snapshot.smbServers"
            :key="server.id"
            class="smb-card"
          >
            <label
              >名前<input
                v-model="server.name"
                :disabled="controlsLocked" /></label
            ><label
              >アドレス<input
                v-model="server.ip"
                :disabled="controlsLocked" /></label
            ><label
              >ポート<input
                v-model="server.port"
                :disabled="controlsLocked"
                inputmode="numeric" /></label
            ><label
              >ユーザー名<input
                v-model="server.user"
                :disabled="controlsLocked"
                autocomplete="username" /></label
            ><label
              >パスワード<input
                v-model="server.password"
                :disabled="controlsLocked"
                type="password"
                :placeholder="
                  server.passwordConfigured
                    ? '設定済み（変更する場合のみ入力）'
                    : ''
                "
                autocomplete="new-password" /></label
            >
            <button
              v-if="server.passwordConfigured && server.password === null"
              class="secondary"
              type="button"
              :disabled="controlsLocked"
              @click="server.password = ''"
            >
              パスワードを消去
            </button>
            <button
              class="secondary danger"
              type="button"
              :disabled="controlsLocked"
              @click="requestRemove(server, $event)"
            >
              削除
            </button>
            <p v-if="!validSmbServer(server)" class="field-error">
              名前、アドレス、ポート番号を確認してください。
            </p>
          </div>
          <p v-if="snapshot.smbServers.length === 0" class="empty-note">
            SMB サーバーは未登録です。
          </p>
        </section>
        <div v-if="restoredDraft" class="draft-notice">
          未保存の設定を復元しました。<button
            class="secondary"
            type="button"
            :disabled="controlsLocked"
            @click="discardDraft"
          >
            破棄
          </button>
        </div>
        <div class="feedback-slot" aria-live="polite">
          <p v-if="error" class="save-error" role="alert">{{ error }}</p>
          <p v-else-if="saved" class="success" role="status">
            テレビの接続設定を更新しました。
          </p>
        </div>
        <button class="save" type="button" :disabled="!canSave" @click="save">
          {{ saving ? "保存しています…" : "テレビに保存" }}
        </button></template
      >
    </section>
    <div v-if="removeTarget" class="modal-backdrop">
      <section class="confirm-dialog" role="dialog" aria-modal="true" @keydown="handleConfirmKey">
        <h2>SMB サーバーを削除しますか？</h2>
        <p>
          {{
            removeTarget.name || removeTarget.ip
          }}
          は、保存時にテレビから削除されます。
        </p>
        <div>
          <button class="secondary" type="button" ref="removeCancelButton" @click="cancelRemove">
            キャンセル</button
          ><button
            class="secondary danger"
            type="button"
            @click="confirmRemove"
          >
            削除する
          </button>
        </div>
      </section>
    </div>
  </main>
</template>
