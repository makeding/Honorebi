<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { setupTokenFromHash } from "./connection";
import {
  blankInventory,
  chunkFiles,
  formatBytes,
  formatErrorWithCode,
  formatUploadProgress,
  normalizeInventory,
  parseApiError,
  reopenScreensaverMessage,
  UPLOAD_BATCH_SIZE,
  validateUploadFiles,
  type ScreensaverImageItem,
  type ScreensaverInventory,
} from "./screensaver";
import honoRebiMark from "../../app/src/main/res/drawable/honorebi_mark.png";

type ConfirmMode =
  | { kind: "single"; item: ScreensaverImageItem; displayNumber: number }
  | { kind: "all"; count: number };

const token = setupTokenFromHash(window.location.hash);
const state = ref<"loading" | "ready" | "failure">("loading");
const inventory = ref<ScreensaverInventory>(blankInventory());
const busy = ref(false);
const uploadProgress = ref("");
const statusMessage = ref("");
const errorMessage = ref("");
const expired = ref(false);
const confirmTarget = ref<ConfirmMode | null>(null);

const fileInput = ref<HTMLInputElement | null>(null);
const confirmDialog = ref<HTMLElement | null>(null);
const confirmCancelButton = ref<HTMLButtonElement | null>(null);
let returnFocusEl: HTMLElement | null = null;

const thumbnailUrls = ref<Record<string, string>>({});
const loadingThumbnails = new Set<string>();
const failedThumbnails = ref<Record<string, boolean>>({});
let unmounted = false;

const controlsLocked = computed(() => busy.value || expired.value);
const imageCount = computed(() => inventory.value.images.length);
const maxMiB = computed(() =>
  Math.round(inventory.value.maxImageBytes / (1024 * 1024)),
);

function authHeaders(): HeadersInit {
  return { "X-Setup-Token": token };
}

function revokeUnusedThumbnails(activeIds: Set<string>) {
  const nextUrls = { ...thumbnailUrls.value };
  const nextFailed = { ...failedThumbnails.value };
  let changedUrls = false;
  let changedFailed = false;
  for (const id of Object.keys(nextUrls)) {
    if (!activeIds.has(id)) {
      URL.revokeObjectURL(nextUrls[id]);
      delete nextUrls[id];
      changedUrls = true;
    }
  }
  for (const id of Object.keys(nextFailed)) {
    if (!activeIds.has(id)) {
      delete nextFailed[id];
      changedFailed = true;
    }
  }
  if (changedUrls) thumbnailUrls.value = nextUrls;
  if (changedFailed) failedThumbnails.value = nextFailed;
}

function revokeAllThumbnails() {
  for (const url of Object.values(thumbnailUrls.value)) {
    URL.revokeObjectURL(url);
  }
  thumbnailUrls.value = {};
  failedThumbnails.value = {};
  loadingThumbnails.clear();
}

async function fetchThumbnail(id: string) {
  if (
    unmounted ||
    expired.value ||
    thumbnailUrls.value[id] ||
    loadingThumbnails.has(id)
  ) {
    return;
  }
  loadingThumbnails.add(id);
  try {
    const response = await fetch(
      `/api/screensaver/images/${encodeURIComponent(id)}/content?thumbnail=1`,
      { headers: authHeaders() },
    );
    if (unmounted) return;
    if (response.status === 401) {
      expired.value = true;
      errorMessage.value = formatErrorWithCode(
        reopenScreensaverMessage,
        "SETUP_EXPIRED",
      );
      return;
    }
    if (!response.ok) {
      failedThumbnails.value = { ...failedThumbnails.value, [id]: true };
      return;
    }
    const blob = await response.blob();
    if (unmounted) return;
    if (!inventory.value.images.some((img) => img.id === id)) return;
    const objectUrl = URL.createObjectURL(blob);
    thumbnailUrls.value = { ...thumbnailUrls.value, [id]: objectUrl };
  } catch {
    if (!unmounted) {
      failedThumbnails.value = { ...failedThumbnails.value, [id]: true };
    }
  } finally {
    loadingThumbnails.delete(id);
  }
}

function syncThumbnails(items: ScreensaverImageItem[]) {
  const activeIds = new Set(items.map((item) => item.id));
  revokeUnusedThumbnails(activeIds);
  for (const item of items) {
    if (!thumbnailUrls.value[item.id] && !failedThumbnails.value[item.id]) {
      void fetchThumbnail(item.id);
    }
  }
}

function applyInventory(next: ScreensaverInventory) {
  inventory.value = next;
  syncThumbnails(next.images);
}

async function loadInventory() {
  if (!token) {
    expired.value = true;
    errorMessage.value = formatErrorWithCode(
      reopenScreensaverMessage,
      "SETUP_EXPIRED",
    );
    state.value = "failure";
    return;
  }
  state.value = "loading";
  errorMessage.value = "";
  try {
    const response = await fetch("/api/screensaver/images", {
      headers: authHeaders(),
    });
    if (!response.ok) {
      const parsed = parseApiError(
        response.status,
        await response.text(),
        "INVENTORY_FAILED",
        "テレビの登録画像一覧を読み込めませんでした。接続を確認して再試行してください。",
      );
      expired.value = parsed.expired;
      errorMessage.value = parsed.message;
      state.value = "failure";
      return;
    }
    const data = normalizeInventory(await response.json());
    applyInventory(data);
    state.value = "ready";
  } catch {
    errorMessage.value = formatErrorWithCode(
      "テレビに接続できませんでした。同じネットワークに接続されているか確認して再試行してください。",
      "NETWORK_ERROR",
    );
    state.value = "failure";
  }
}

function openFilePicker() {
  if (controlsLocked.value) return;
  fileInput.value?.click();
}

async function handleFileSelection(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = "";
  if (files.length === 0 || controlsLocked.value) return;

  errorMessage.value = "";
  statusMessage.value = "";

  const validation = validateUploadFiles(files, inventory.value.maxImageBytes);
  if (!validation.valid) {
    errorMessage.value = formatErrorWithCode(
      validation.message ?? "選択した画像を確認してください。",
      validation.code ?? "INVALID_IMAGE",
    );
    return;
  }

  busy.value = true;
  const batches = chunkFiles(
    files,
    Math.min(UPLOAD_BATCH_SIZE, inventory.value.maxFilesPerUpload),
  );
  let uploadedCount = 0;

  try {
    for (const batch of batches) {
      uploadProgress.value = formatUploadProgress(
        uploadedCount,
        files.length,
        batch.length,
      );
      const formData = new FormData();
      for (const file of batch) {
        formData.append("images", file, file.name);
      }
      const response = await fetch("/api/screensaver/images", {
        method: "POST",
        headers: authHeaders(),
        body: formData,
      });
      if (!response.ok) {
        const parsed = parseApiError(
          response.status,
          await response.text(),
          "UPLOAD_FAILED",
          "画像をテレビに送信できませんでした。接続を確認して再試行してください。",
        );
        expired.value = parsed.expired;
        errorMessage.value =
          uploadedCount > 0
            ? `${uploadedCount} 枚を追加した後で中断されました。${parsed.message}`
            : parsed.message;
        return;
      }
      const next = normalizeInventory(await response.json());
      applyInventory(next);
      uploadedCount += batch.length;
    }
    statusMessage.value = `${uploadedCount} 枚の画像をテレビに追加しました。`;
  } catch {
    errorMessage.value = formatErrorWithCode(
      uploadedCount > 0
        ? `${uploadedCount} 枚を追加した後で通信が中断されました。接続を確認して残りの画像を再試行してください。`
        : "テレビに画像を送信できませんでした。同じネットワークに接続されているか確認して再試行してください。",
      "NETWORK_ERROR",
    );
  } finally {
    uploadProgress.value = "";
    busy.value = false;
  }
}

async function openConfirm(target: ConfirmMode, event: Event) {
  if (controlsLocked.value) return;
  returnFocusEl = event.currentTarget as HTMLElement | null;
  confirmTarget.value = target;
  await nextTick();
  confirmCancelButton.value?.focus();
}

function closeConfirm() {
  if (busy.value) return;
  confirmTarget.value = null;
  const el = returnFocusEl;
  returnFocusEl = null;
  void nextTick(() => el?.focus());
}

function handleConfirmKey(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    closeConfirm();
    return;
  }
  if (event.key !== "Tab" || !confirmDialog.value) return;
  const focusables = Array.from(
    confirmDialog.value.querySelectorAll<HTMLElement>(
      "button:not([disabled]), [tabindex]:not([tabindex='-1'])",
    ),
  );
  if (focusables.length === 0) return;
  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

async function executeConfirmDelete() {
  const target = confirmTarget.value;
  if (!target || busy.value || expired.value) return;

  busy.value = true;
  errorMessage.value = "";
  statusMessage.value = "";
  try {
    const endpoint =
      target.kind === "single"
        ? `/api/screensaver/images/${encodeURIComponent(target.item.id)}`
        : "/api/screensaver/images";
    const response = await fetch(endpoint, {
      method: "DELETE",
      headers: authHeaders(),
    });
    if (!response.ok) {
      const parsed = parseApiError(
        response.status,
        await response.text(),
        "DELETE_FAILED",
        "画像を削除できませんでした。一覧を更新して再試行してください。",
      );
      expired.value = parsed.expired;
      errorMessage.value = parsed.message;
      confirmTarget.value = null;
      return;
    }
    const next = normalizeInventory(await response.json());
    applyInventory(next);
    confirmTarget.value = null;
    statusMessage.value =
      target.kind === "single"
        ? `画像 ${target.displayNumber} を削除しました。`
        : "登録画像をすべて削除しました。";
  } catch {
    errorMessage.value = formatErrorWithCode(
      "テレビに接続できず画像を削除できませんでした。ネットワーク接続を確認して再試行してください。",
      "NETWORK_ERROR",
    );
    confirmTarget.value = null;
  } finally {
    busy.value = false;
    const el = returnFocusEl;
    returnFocusEl = null;
    void nextTick(() => {
      if (el && document.contains(el)) el.focus();
    });
  }
}

onMounted(() => {
  document.title = "スクリーンセーバー画像管理 — Komorebi";
  void loadInventory();
});

onBeforeUnmount(() => {
  unmounted = true;
  revokeAllThumbnails();
});
</script>

<template>
  <main class="page-shell">
    <header>
      <div class="brand">
        <img :src="honoRebiMark" alt="Komorebi" />
        <span>Komorebi</span>
      </div>
      <h1>スクリーンセーバー画像</h1>
    </header>

    <section class="panel" aria-live="polite">
      <div v-if="state === 'loading'" class="state-slot">
        <div class="spinner" aria-hidden="true"></div>
        <p>テレビの登録画像を読み込んでいます…</p>
      </div>

      <div v-else-if="state === 'failure'" class="state-slot failure" role="alert">
        <strong>ページを開けませんでした</strong>
        <span>{{ errorMessage }}</span>
        <button
          v-if="!expired"
          class="secondary"
          type="button"
          @click="loadInventory"
        >
          再試行
        </button>
      </div>

      <template v-else>
        <div class="section-heading">
          <h2>スマホから画像を追加</h2>
          <p>
            選んだ画像はテレビ本体に保存され、スクリーンセーバーに自動で反映されます（1枚 {{ maxMiB }} MiB 以下）。
          </p>
        </div>

        <input
          ref="fileInput"
          class="visually-hidden"
          type="file"
          accept="image/*"
          multiple
          :disabled="controlsLocked"
          @change="handleFileSelection"
        />

        <button
          class="save"
          type="button"
          :disabled="controlsLocked"
          @click="openFilePicker"
        >
          {{ uploadProgress || "画像を選択して追加" }}
        </button>

        <div class="screensaver-feedback" aria-live="polite">
          <p v-if="errorMessage" class="save-error" role="alert">
            {{ errorMessage }}
          </p>
          <p v-else-if="statusMessage" class="success" role="status">
            {{ statusMessage }}
          </p>
        </div>

        <section class="settings-block screensaver-gallery-block">
          <div class="block-title">
            <div>
              <h2>登録済みの画像（{{ imageCount }} 枚）</h2>
              <p>テレビのスクリーンセーバーに表示される画像一覧です。</p>
            </div>
            <button
              class="secondary danger"
              type="button"
              :disabled="controlsLocked || imageCount === 0"
              @click="openConfirm({ kind: 'all', count: imageCount }, $event)"
            >
              すべて削除
            </button>
          </div>

          <p v-if="imageCount === 0" class="empty-note">
            まだ画像が登録されていません。上の「画像を選択して追加」から写真を選んでください。
          </p>

          <div v-else class="screensaver-grid">
            <article
              v-for="(item, idx) in inventory.images"
              :key="item.id"
              class="screensaver-card"
            >
              <div class="screensaver-thumb-slot">
                <img
                  v-if="thumbnailUrls[item.id]"
                  :src="thumbnailUrls[item.id]"
                  :alt="`登録画像 ${idx + 1}`"
                  class="screensaver-thumb"
                />
                <div
                  v-else-if="failedThumbnails[item.id]"
                  class="screensaver-thumb-placeholder"
                >
                  プレビューを読み込めません
                </div>
                <div v-else class="screensaver-thumb-placeholder">
                  <div class="spinner" aria-hidden="true"></div>
                </div>
              </div>
              <div class="screensaver-card-footer">
                <div class="screensaver-meta">
                  <strong>画像 {{ idx + 1 }}</strong>
                  <small v-if="item.byteSize !== null">{{ formatBytes(item.byteSize) }}</small>
                </div>
                <button
                  class="secondary danger screensaver-delete-btn"
                  type="button"
                  :disabled="controlsLocked"
                  @click="
                    openConfirm(
                      { kind: 'single', item, displayNumber: idx + 1 },
                      $event,
                    )
                  "
                >
                  削除
                </button>
              </div>
            </article>
          </div>
        </section>
      </template>
    </section>

    <div v-if="confirmTarget" class="modal-backdrop">
      <section
        ref="confirmDialog"
        class="confirm-dialog"
        role="dialog"
        aria-modal="true"
        @keydown="handleConfirmKey"
      >
        <h2 v-if="confirmTarget.kind === 'single'">
          画像 {{ confirmTarget.displayNumber }} を削除しますか？
        </h2>
        <h2 v-else>登録画像をすべて削除しますか？</h2>
        <p v-if="confirmTarget.kind === 'single'">
          この画像はテレビのスクリーンセーバーから削除されます。
        </p>
        <p v-else>
          登録されている {{ confirmTarget.count }} 枚の画像をすべてテレビから削除します。
        </p>
        <div>
          <button
            ref="confirmCancelButton"
            class="secondary"
            type="button"
            :disabled="busy"
            @click="closeConfirm"
          >
            キャンセル
          </button>
          <button
            class="secondary danger"
            type="button"
            :disabled="busy"
            @click="executeConfirmDelete"
          >
            {{ busy ? "削除中…" : "削除する" }}
          </button>
        </div>
      </section>
    </div>
  </main>
</template>
