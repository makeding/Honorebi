export const DEFAULT_MAX_IMAGE_BYTES = 64 * 1024 * 1024;
export const DEFAULT_MAX_FILES_PER_UPLOAD = 20;
export const UPLOAD_BATCH_SIZE = 3;

export const reopenScreensaverMessage =
  "テレビで「スマホから画像を管理」を開き直し、表示されたQRコードからこのページを開いてください。";

export interface ScreensaverImageItem {
  id: string;
  index: number;
  byteSize: number | null;
  owned: boolean;
}

export interface ScreensaverInventory {
  images: ScreensaverImageItem[];
  maxImageBytes: number;
  maxFilesPerUpload: number;
}

export interface ScreensaverError {
  code: string;
  message: string;
  expired: boolean;
}

export interface FileLike {
  name: string;
  size: number;
  type: string;
}

export interface FileValidationResult {
  valid: boolean;
  code?: string;
  message?: string;
}

export function isScreensaverPath(
  pathname: string,
  search: string = "",
): boolean {
  return (
    pathname.replace(/\/+$/, "") === "/screensaver" ||
    new URLSearchParams(search).get("mode") === "screensaver"
  );
}

export function blankInventory(): ScreensaverInventory {
  return {
    images: [],
    maxImageBytes: DEFAULT_MAX_IMAGE_BYTES,
    maxFilesPerUpload: DEFAULT_MAX_FILES_PER_UPLOAD,
  };
}

export function normalizeInventory(input: unknown): ScreensaverInventory {
  if (!input || typeof input !== "object") {
    return blankInventory();
  }
  const raw = input as Record<string, unknown>;
  const rawImages = Array.isArray(raw.images) ? raw.images : [];
  const images: ScreensaverImageItem[] = rawImages
    .filter(
      (item): item is Record<string, unknown> =>
        item !== null && typeof item === "object" && typeof (item as Record<string, unknown>).id === "string",
    )
    .map((item, fallbackIndex) => ({
      id: item.id as string,
      index: typeof item.index === "number" ? item.index : fallbackIndex,
      byteSize: typeof item.byteSize === "number" ? item.byteSize : null,
      owned: item.owned === true,
    }));

  return {
    images,
    maxImageBytes:
      typeof raw.maxImageBytes === "number" && raw.maxImageBytes > 0
        ? raw.maxImageBytes
        : DEFAULT_MAX_IMAGE_BYTES,
    maxFilesPerUpload:
      typeof raw.maxFilesPerUpload === "number" && raw.maxFilesPerUpload > 0
        ? raw.maxFilesPerUpload
        : DEFAULT_MAX_FILES_PER_UPLOAD,
  };
}

export function validateUploadFiles(
  files: ReadonlyArray<FileLike>,
  maxImageBytes: number = DEFAULT_MAX_IMAGE_BYTES,
): FileValidationResult {
  if (files.length === 0) {
    return {
      valid: false,
      code: "NO_IMAGES",
      message: "追加する画像を選択してください。",
    };
  }
  const maxMiB = Math.round(maxImageBytes / (1024 * 1024));
  for (const file of files) {
    const label = file.name || "選択したファイル";
    if (file.size === 0) {
      return {
        valid: false,
        code: "EMPTY_IMAGE",
        message: `${label} は空のファイルのため追加できません。別の画像を選んでください。`,
      };
    }
    if (file.size > maxImageBytes) {
      return {
        valid: false,
        code: "IMAGE_TOO_LARGE",
        message: `${label} は ${maxMiB} MiB を超えているため追加できません。1枚 ${maxMiB} MiB 以下の画像を選んでください。`,
      };
    }
    if (
      file.type &&
      !file.type.startsWith("image/") &&
      file.type !== "application/octet-stream"
    ) {
      return {
        valid: false,
        code: "UNSUPPORTED_IMAGE_TYPE",
        message: `${label} は画像ファイルではないため追加できません。JPEG・PNG・WebPなどの画像を選んでください。`,
      };
    }
  }
  return { valid: true };
}

export function chunkFiles<T>(
  files: ReadonlyArray<T>,
  batchSize: number = UPLOAD_BATCH_SIZE,
): T[][] {
  const safeBatch = Math.max(1, Math.floor(batchSize));
  const chunks: T[][] = [];
  for (let i = 0; i < files.length; i += safeBatch) {
    chunks.push(files.slice(i, i + safeBatch));
  }
  return chunks;
}

export function formatBytes(bytes: number | null): string {
  if (bytes === null || !Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function formatUploadProgress(
  completed: number,
  total: number,
  currentBatchCount: number,
): string {
  const start = Math.min(completed + 1, total);
  const end = Math.min(completed + Math.max(1, currentBatchCount), total);
  if (start === end) {
    return `${start} / ${total} 枚目を送信中…`;
  }
  return `${start}〜${end} / ${total} 枚目を送信中…`;
}

export function formatErrorWithCode(message: string, code: string): string {
  if (!code) return message;
  return message.includes(`(${code})`) ? message : `${message} (${code})`;
}

export function parseApiError(
  status: number,
  bodyText: string,
  fallbackCode: string,
  fallbackMessage: string,
): ScreensaverError {
  let code = fallbackCode;
  let message = fallbackMessage;
  if (bodyText) {
    try {
      const parsed = JSON.parse(bodyText) as { code?: unknown; message?: unknown };
      if (typeof parsed.code === "string" && parsed.code.trim()) {
        code = parsed.code.trim();
      }
      if (typeof parsed.message === "string" && parsed.message.trim()) {
        message = parsed.message.trim();
      }
    } catch {
      /* Ignore non-JSON bodies and use fallback. */
    }
  }
  const expired = status === 401 || code === "SETUP_EXPIRED";
  if (expired && message === fallbackMessage) {
    message = reopenScreensaverMessage;
  }
  return {
    code,
    message: formatErrorWithCode(message, code),
    expired,
  };
}
