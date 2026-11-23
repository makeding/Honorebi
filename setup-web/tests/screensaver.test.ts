import { describe, expect, it } from "vitest";
import {
  DEFAULT_MAX_FILES_PER_UPLOAD,
  DEFAULT_MAX_IMAGE_BYTES,
  chunkFiles,
  formatBytes,
  formatErrorWithCode,
  formatUploadProgress,
  isScreensaverPath,
  normalizeInventory,
  parseApiError,
  validateUploadFiles,
} from "../src/screensaver";

describe("isScreensaverPath", () => {
  it("matches /screensaver with or without trailing slash", () => {
    expect(isScreensaverPath("/screensaver")).toBe(true);
    expect(isScreensaverPath("/screensaver/")).toBe(true);
    expect(isScreensaverPath("/screensaver///")).toBe(true);
  });

  it("matches ?mode=screensaver query parameter", () => {
    expect(isScreensaverPath("/connections/", "?mode=screensaver")).toBe(true);
    expect(isScreensaverPath("/", "?foo=1&mode=screensaver")).toBe(true);
  });

  it("does not match /connections/ or unrelated paths", () => {
    expect(isScreensaverPath("/connections/")).toBe(false);
    expect(isScreensaverPath("/connections/", "?source=KONOMITV")).toBe(false);
    expect(isScreensaverPath("/screensaver/extra")).toBe(false);
  });
});

describe("normalizeInventory", () => {
  it("falls back to safe defaults for missing or malformed inputs", () => {
    expect(normalizeInventory(null)).toEqual({
      images: [],
      maxImageBytes: DEFAULT_MAX_IMAGE_BYTES,
      maxFilesPerUpload: DEFAULT_MAX_FILES_PER_UPLOAD,
    });
  });

  it("normalizes valid items and ignores invalid entries", () => {
    const result = normalizeInventory({
      images: [
        { id: "0123456789abcdef01234567", index: 0, byteSize: 4096, owned: true },
        { id: "fedcba9876543210fedcba98", byteSize: null, owned: false },
        { invalid: true },
      ],
      maxImageBytes: 1024,
      maxFilesPerUpload: 10,
    });
    expect(result).toEqual({
      images: [
        { id: "0123456789abcdef01234567", index: 0, byteSize: 4096, owned: true },
        { id: "fedcba9876543210fedcba98", index: 1, byteSize: null, owned: false },
      ],
      maxImageBytes: 1024,
      maxFilesPerUpload: 10,
    });
  });
});

describe("validateUploadFiles", () => {
  it("rejects empty selection, 0-byte files, oversized files, and non-image MIME types", () => {
    expect(validateUploadFiles([])).toMatchObject({
      valid: false,
      code: "NO_IMAGES",
    });
    expect(
      validateUploadFiles([{ name: "empty.png", size: 0, type: "image/png" }]),
    ).toMatchObject({ valid: false, code: "EMPTY_IMAGE" });
    expect(
      validateUploadFiles([
        {
          name: "huge.jpg",
          size: DEFAULT_MAX_IMAGE_BYTES + 1,
          type: "image/jpeg",
        },
      ]),
    ).toMatchObject({ valid: false, code: "IMAGE_TOO_LARGE" });
    expect(
      validateUploadFiles([
        { name: "doc.pdf", size: 1024, type: "application/pdf" },
      ]),
    ).toMatchObject({ valid: false, code: "UNSUPPORTED_IMAGE_TYPE" });
  });

  it("accepts standard images, octet-stream, and empty MIME type for mobile photos", () => {
    expect(
      validateUploadFiles([
        { name: "a.jpg", size: 2048, type: "image/jpeg" },
        { name: "b.heic", size: 4096, type: "application/octet-stream" },
        { name: "c.avif", size: 1024, type: "" },
      ]),
    ).toEqual({ valid: true });
  });
});

describe("batching and formatting helpers", () => {
  it("chunks files into batches", () => {
    expect(chunkFiles([1, 2, 3, 4, 5, 6, 7], 3)).toEqual([
      [1, 2, 3],
      [4, 5, 6],
      [7],
    ]);
    expect(chunkFiles([], 3)).toEqual([]);
  });

  it("formats upload progress cleanly for single and multi-file batches", () => {
    expect(formatUploadProgress(0, 1, 1)).toBe("1 / 1 枚目を送信中…");
    expect(formatUploadProgress(0, 8, 3)).toBe("1〜3 / 8 枚目を送信中…");
    expect(formatUploadProgress(6, 8, 2)).toBe("7〜8 / 8 枚目を送信中…");
    expect(formatUploadProgress(7, 8, 1)).toBe("8 / 8 枚目を送信中…");
  });

  it("formats byte sizes", () => {
    expect(formatBytes(null)).toBe("");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2.0 KB");
    expect(formatBytes(3 * 1024 * 1024)).toBe("3.0 MB");
  });
});

describe("parseApiError", () => {
  it("parses JSON error payloads and preserves stable error codes", () => {
    const parsed = parseApiError(
      400,
      JSON.stringify({
        code: "INVALID_IMAGE",
        message: "選択したファイルを画像として読み取れません。",
      }),
      "UPLOAD_FAILED",
      "fallback",
    );
    expect(parsed).toEqual({
      code: "INVALID_IMAGE",
      message: "選択したファイルを画像として読み取れません。 (INVALID_IMAGE)",
      expired: false,
    });
  });

  it("marks 401 and SETUP_EXPIRED as expired", () => {
    const parsed = parseApiError(
      401,
      JSON.stringify({ code: "SETUP_EXPIRED", message: "期限切れです。" }),
      "UPLOAD_FAILED",
      "fallback",
    );
    expect(parsed.expired).toBe(true);
    expect(parsed.code).toBe("SETUP_EXPIRED");
    expect(parsed.message).toContain("(SETUP_EXPIRED)");
  });

  it("does not duplicate error code suffix", () => {
    expect(formatErrorWithCode("失敗しました (ERR_1)", "ERR_1")).toBe(
      "失敗しました (ERR_1)",
    );
  });
});
