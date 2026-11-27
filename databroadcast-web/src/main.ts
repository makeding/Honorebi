import { Buffer } from 'buffer';
import { AribKeyCode, BMLBrowser, type BMLBrowserFontFace } from 'web-bml';
import type { ResponseMessage } from 'web-bml/worker';
import DecoderWorker from './worker?worker';

(globalThis as unknown as { Buffer: typeof Buffer }).Buffer = Buffer;

interface ARIBNativeBridge {
  allowExternalNetwork?: () => boolean;
  receiverInfoJson?: () => string;
  onStatus?: (value: string) => void;
  onMediaPlane?: (json: string) => void;
  onMediaPlaneUnmounted?: (reason: string) => void;
  onBlankModeChanged?: (isBlank: boolean) => void;
  onDataAvailabilityChanged?: (available: boolean) => void;
  onNumericKeyUsageChanged?: (active: boolean) => void;
  onApplicationExited?: () => void;
  internetGet?: (uri: string) => string;
  internetPost?: (uri: string, bodyBase64: string) => string;
  confirmIPNetwork?: (destination: string, isIcmp: boolean, timeoutMillis: number) => string;
}

declare global {
  interface Window {
    ARIBNative?: ARIBNativeBridge;
    HonorebiB24?: {
      start: (json: string) => boolean;
      pushChunkBase64: (base64: string) => boolean;
      stop: () => boolean;
    };
    KomorebiDataBroadcastingRemote?: {
      press: (key: string) => boolean;
    };
  }
}

const STORAGE_PREFIX = 'Honorebi-BMLBrowser_';
const NVRAM_PREFIX = 'nvram_';
const RECEIVER_INFO_KEY_PREFIX = `${STORAGE_PREFIX}${NVRAM_PREFIX}prefix=receiverinfo%2F`;
const GREG_STORAGE_KEY = 'Honorebi-BMLBrowser-Greg';

const ROUND_GOTHIC: BMLBrowserFontFace = {
  source: 'url("/databroadcast/fonts/kosugi_maru_regular.ttf"), local("sans-serif")',
};
const SQUARE_GOTHIC: BMLBrowserFontFace = {
  source: 'url("/databroadcast/fonts/rounded-mplus-1m-arib.ttf"), local("sans-serif")',
};

const REMOTE_KEY_TO_ARIB: Record<string, AribKeyCode> = {
  up: AribKeyCode.Up,
  down: AribKeyCode.Down,
  left: AribKeyCode.Left,
  right: AribKeyCode.Right,
  enter: AribKeyCode.Enter,
  back: AribKeyCode.Back,
  data: AribKeyCode.DataButton,
  blue: AribKeyCode.BlueButton,
  red: AribKeyCode.RedButton,
  green: AribKeyCode.GreenButton,
  yellow: AribKeyCode.YellowButton,
  '0': AribKeyCode.Digit0,
  '1': AribKeyCode.Digit1,
  '2': AribKeyCode.Digit2,
  '3': AribKeyCode.Digit3,
  '4': AribKeyCode.Digit4,
  '5': AribKeyCode.Digit5,
  '6': AribKeyCode.Digit6,
  '7': AribKeyCode.Digit7,
  '8': AribKeyCode.Digit8,
  '9': AribKeyCode.Digit9,
};

const viewport = document.querySelector<HTMLDivElement>('#viewport')!;
const container = document.querySelector<HTMLDivElement>('#bml-container')!;
const statusEl = document.querySelector<HTMLDivElement>('#status')!;

let bmlBrowser: BMLBrowser | null = null;
let worker: Worker | null = null;
let videoResizeObserver: ResizeObserver | null = null;
let bmlWidth = 960;
let bmlHeight = 540;
let isInvisible = false;
let hasLoadedOnce = false;
let lastPlaneJson = '';
let lastStatusText = 'データ放送を受信中…';
let dataPort: MessagePort | null = null;

function setStatus(displayText: string, nativeLog: string = displayText): void {
  if (displayText !== lastStatusText) {
    lastStatusText = displayText;
    statusEl.textContent = displayText;
  }
  if (nativeLog && window.ARIBNative?.onStatus) {
    window.ARIBNative.onStatus(nativeLog);
  }
}

function syncReceiverInfoToLocalStorage(): void {
  try {
    const raw = window.ARIBNative?.receiverInfoJson?.();
    if (!raw) return;
    const info = JSON.parse(raw) as {
      zipcode?: string | null;
      prefecture?: number | null;
      regioncode?: number | null;
    };
    if (typeof info.zipcode === 'string' && /^\d{7}$/.test(info.zipcode)) {
      localStorage.setItem(`${RECEIVER_INFO_KEY_PREFIX}zipcode`, btoa(info.zipcode));
    } else {
      localStorage.removeItem(`${RECEIVER_INFO_KEY_PREFIX}zipcode`);
    }
    if (typeof info.prefecture === 'number') {
      localStorage.setItem(
        `${RECEIVER_INFO_KEY_PREFIX}prefecture`,
        btoa(String.fromCharCode(info.prefecture & 0xff)),
      );
    } else {
      localStorage.removeItem(`${RECEIVER_INFO_KEY_PREFIX}prefecture`);
    }
    if (typeof info.regioncode === 'number') {
      localStorage.setItem(
        `${RECEIVER_INFO_KEY_PREFIX}regioncode`,
        btoa(String.fromCharCode((info.regioncode >> 8) & 0xff, info.regioncode & 0xff)),
      );
    } else {
      localStorage.removeItem(`${RECEIVER_INFO_KEY_PREFIX}regioncode`);
    }
  } catch (err) {
    console.warn('[HonorebiB24] Failed to sync receiverInfo:', err);
  }
}

function updateScale(): void {
  const vw = viewport.clientWidth || 1920;
  const vh = viewport.clientHeight || 1080;
  const targetWidth = (bmlHeight * 16) / 9;
  const scale = Math.min(vw / targetWidth, vh / bmlHeight);
  const magnification = targetWidth / bmlWidth;
  container.style.setProperty('--bml-browser-width', `${bmlWidth}px`);
  container.style.setProperty('--bml-browser-height', `${bmlHeight}px`);
  container.style.setProperty('--bml-scale-x', `${scale * magnification}`);
  container.style.setProperty('--bml-scale-y', `${scale}`);
}

function reportMediaPlane(): void {
  if (!bmlBrowser || isInvisible) return;
  const videoEl = bmlBrowser.getVideoElement();
  if (!videoEl) {
    if (lastPlaneJson !== '') {
      lastPlaneJson = '';
      window.ARIBNative?.onMediaPlaneUnmounted?.('no-video-element');
    }
    return;
  }
  const vpRect = viewport.getBoundingClientRect();
  const rect = videoEl.getBoundingClientRect();
  if (vpRect.width <= 0 || vpRect.height <= 0) return;

  const visible = rect.width > 1 && rect.height > 1;
  const plane = {
    visible,
    x: rect.left - vpRect.left,
    y: rect.top - vpRect.top,
    width: rect.width,
    height: rect.height,
    screenWidth: vpRect.width,
    screenHeight: vpRect.height,
  };
  const serialized = JSON.stringify(plane);
  if (serialized !== lastPlaneJson) {
    lastPlaneJson = serialized;
    window.ARIBNative?.onMediaPlane?.(serialized);
  }
}

function observeVideoElement(): void {
  videoResizeObserver?.disconnect();
  videoResizeObserver = null;
  const videoEl = bmlBrowser?.getVideoElement();
  if (!videoEl) {
    reportMediaPlane();
    return;
  }
  videoEl.style.background = 'transparent';
  videoResizeObserver = new ResizeObserver(() => reportMediaPlane());
  videoResizeObserver.observe(videoEl);
  requestAnimationFrame(() => reportMediaPlane());
}

function decodeBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const binary = atob(base64);
  const len = binary.length;
  const bytes = new Uint8Array(new ArrayBuffer(len));
  for (let i = 0; i < len; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function uint8ArrayToBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function readGreg(): string[] {
  try {
    const raw = sessionStorage.getItem(GREG_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed;
    }
  } catch {
    // ignore
  }
  return new Array<string>(64).fill('');
}

function destroySession(): void {
  videoResizeObserver?.disconnect();
  videoResizeObserver = null;
  if (worker) {
    worker.postMessage({ type: 'stop' });
    worker.terminate();
    worker = null;
  }
  if (bmlBrowser) {
    try {
      bmlBrowser.destroy();
    } catch {
      // ignore
    }
    bmlBrowser = null;
  }
  container.innerHTML = '';
  container.classList.remove('invisible');
  lastPlaneJson = '';
  isInvisible = false;
  hasLoadedOnce = false;
}

function startSession(config: {
  serviceId: number;
  networkId?: number | null;
  transportStreamId?: number | null;
  rawTs?: boolean;
}): boolean {
  destroySession();
  syncReceiverInfoToLocalStorage();

  statusEl.classList.remove('ready');
  setStatus('データ放送を受信中…', '');

  const dummyMedia = document.createElement('div');
  dummyMedia.style.width = '100%';
  dummyMedia.style.height = '100%';
  dummyMedia.style.background = 'transparent';

  const browser = new BMLBrowser({
    mediaElement: dummyMedia,
    containerElement: container,
    storagePrefix: STORAGE_PREFIX,
    nvramPrefix: NVRAM_PREFIX,
    broadcasterDatabasePrefix: '',
    videoPlaneModeEnabled: true,
    fonts: {
      roundGothic: ROUND_GOTHIC,
      squareGothic: SQUARE_GOTHIC,
    },
    indicator: {
      setUrl(_name: string, loading: boolean) {
        if (!hasLoadedOnce && loading) {
          statusEl.classList.remove('ready');
          setStatus('データ放送を読み込み中…', '');
        }
      },
      setNetworkingGetStatus() {},
      setNetworkingPostStatus() {},
      setReceivingStatus() {},
      setEventName() {},
    },
    greg: {
      getReg(index: number) {
        return readGreg()[index] ?? '';
      },
      setReg(index: number, value: string) {
        const greg = readGreg();
        greg[index] = value;
        sessionStorage.setItem(GREG_STORAGE_KEY, JSON.stringify(greg));
      },
    },
    ip: {
      getConnectionType() {
        return window.ARIBNative?.allowExternalNetwork?.() ? 403 : 0;
      },
      async get(uri: string) {
        if (!window.ARIBNative?.allowExternalNetwork?.() || !window.ARIBNative.internetGet) {
          return {};
        }
        try {
          const raw = window.ARIBNative.internetGet(uri);
          const parsed = JSON.parse(raw) as {
            ok: boolean;
            statusCode?: number;
            headers?: Record<string, string>;
            bodyBase64?: string;
          };
          if (!parsed.ok) return {};
          return {
            statusCode: parsed.statusCode ?? 200,
            headers: new Headers(parsed.headers ?? {}),
            response: parsed.bodyBase64 ? decodeBase64ToUint8Array(parsed.bodyBase64) : new Uint8Array(),
          };
        } catch {
          return {};
        }
      },
      async transmitTextDataOverIP(uri: string, body: Uint8Array) {
        if (!window.ARIBNative?.allowExternalNetwork?.() || !window.ARIBNative.internetPost) {
          return { resultCode: NaN, statusCode: '', response: new Uint8Array() };
        }
        try {
          const raw = window.ARIBNative.internetPost(uri, uint8ArrayToBase64(body));
          const parsed = JSON.parse(raw) as {
            ok: boolean;
            statusCode?: string;
            bodyBase64?: string;
          };
          if (!parsed.ok) {
            return { resultCode: NaN, statusCode: '', response: new Uint8Array() };
          }
          return {
            resultCode: 1,
            statusCode: parsed.statusCode ?? '200',
            response: parsed.bodyBase64 ? decodeBase64ToUint8Array(parsed.bodyBase64) : new Uint8Array(),
          };
        } catch {
          return { resultCode: NaN, statusCode: '', response: new Uint8Array() };
        }
      },
      async confirmIPNetwork(destination: string, isIcmp: boolean, timeoutMillis: number) {
        if (!window.ARIBNative?.allowExternalNetwork?.() || !window.ARIBNative.confirmIPNetwork) {
          return null;
        }
        try {
          const raw = window.ARIBNative.confirmIPNetwork(destination, isIcmp, timeoutMillis);
          if (!raw) return null;
          return JSON.parse(raw);
        } catch {
          return null;
        }
      },
    },
    showErrorMessage(title: string, message: string, code?: string) {
      console.warn('[HonorebiB24] BML error:', title, message, code);
      window.ARIBNative?.onStatus?.(`bml-error: ${title} ${message} (${code ?? ''})`);
    },
  });

  bmlBrowser = browser;
  bmlWidth = 960;
  bmlHeight = 540;
  updateScale();

  browser.addEventListener('load', (event) => {
    hasLoadedOnce = true;
    bmlWidth = event.detail.resolution.width || 960;
    bmlHeight = event.detail.resolution.height || 540;
    updateScale();
    statusEl.classList.add('ready');
    observeVideoElement();
  });

  browser.addEventListener('invisible', (event) => {
    isInvisible = Boolean(event.detail);
    container.classList.toggle('invisible', isInvisible);
    window.ARIBNative?.onBlankModeChanged?.(isInvisible);
    if (isInvisible) {
      lastPlaneJson = '';
      window.ARIBNative?.onMediaPlaneUnmounted?.('invisible');
    } else {
      observeVideoElement();
    }
  });

  browser.addEventListener('usedkeylistchanged', (event) => {
    const usesNumeric = [...event.detail.usedKeyList].includes('numeric-tuning');
    window.ARIBNative?.onNumericKeyUsageChanged?.(usesNumeric);
  });

  const decWorker = new DecoderWorker();
  worker = decWorker;
  decWorker.onmessage = (event: MessageEvent<ResponseMessage>) => {
    const message = event.data;
    if (message.type === 'pmt') {
      const available = message.components.some((c) => c.bxmlInfo !== undefined);
      window.ARIBNative?.onDataAvailabilityChanged?.(available);
      if (!available && !hasLoadedOnce) {
        setStatus('この番組ではデータ放送を利用できません', 'pmt-unavailable');
      }
    }
    bmlBrowser?.emitMessage(message);
  };
  decWorker.postMessage({
    type: 'start',
    serviceId: config.serviceId,
    rawTs: Boolean(config.rawTs),
  });

  return true;
}

function forwardChunkBase64(base64: string): boolean {
  if (!worker || !base64) return false;
  const bytes = decodeBase64ToUint8Array(base64);
  worker.postMessage({ type: 'chunk', data: bytes.buffer }, [bytes.buffer]);
  return true;
}

window.addEventListener('resize', () => {
  updateScale();
  reportMediaPlane();
});

// Receive dedicated WebMessagePort from Android WebView for low-overhead chunk streaming.
window.addEventListener('message', (event: MessageEvent) => {
  if (event.data === 'honorebi-b24-port' && event.ports.length > 0) {
    dataPort?.close();
    dataPort = event.ports[0];
    dataPort.onmessage = (portEvent: MessageEvent<string>) => {
      if (typeof portEvent.data === 'string') {
        forwardChunkBase64(portEvent.data);
      }
    };
  }
});

window.HonorebiB24 = {
  start(json: string) {
    return startSession(JSON.parse(json));
  },
  pushChunkBase64(base64: string) {
    return forwardChunkBase64(base64);
  },
  stop() {
    destroySession();
    return true;
  },
};

window.KomorebiDataBroadcastingRemote = {
  press(key: string) {
    const code = REMOTE_KEY_TO_ARIB[String(key)];
    if (code === undefined || !bmlBrowser) return false;
    bmlBrowser.content.processKeyDown(code);
    bmlBrowser.content.processKeyUp(code);
    return true;
  },
};

window.addEventListener('error', (event) => {
  console.error('[HonorebiB24] error:', event.message);
  window.ARIBNative?.onStatus?.(`error: ${event.message}`);
});
window.addEventListener('unhandledrejection', (event) => {
  const msg = String(event.reason?.message || event.reason);
  console.error('[HonorebiB24] unhandledrejection:', msg);
  window.ARIBNative?.onStatus?.(`rejection: ${msg}`);
});
