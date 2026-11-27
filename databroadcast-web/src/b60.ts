import type { AribMediaPlane, AribMediaPlaneUnmountReason, RuntimeWindow } from 'libaribhtml5';
import { TvAribReceiverHost } from './tvReceiverHost';

interface B60NativeBridge {
  currentMediaTimeSeconds?: () => number;
  inputSource?: () => string;
  allowExternalNetwork?: () => boolean;
  receiverInfoJson?: () => string;
  onStatus?: (value: string) => void;
  onUrlChanged?: (value: string) => void;
  onMediaPlane?: (json: string) => void;
  onMediaPlaneUnmounted?: (reason: string) => void;
  onCaptionSubscription?: (tagsJson: string) => void;
  onApplicationExited?: () => void;
  openProgramGuide?: (requestJson: string) => boolean;
}

declare global {
  interface Window {
    __ARIB_HTML5_INSTALL__?: (target: RuntimeWindow) => void;
    KomorebiB60?: {
      start: (json: string) => boolean;
      updateProgramInfo: (json: string) => boolean;
      updateBroadcastClock: (json: string) => boolean;
      updateLctBackgroundColor: (value: number | null) => boolean;
      setCaptionTracks: (tags: number[]) => boolean;
      pushCaption: (json: string) => boolean;
      resourceChanged: (path: string, updated: boolean) => boolean;
      exit: () => boolean;
    };
  }
}

const nativeBridge = (): B60NativeBridge | undefined =>
  (window as unknown as { ARIBNative?: B60NativeBridge }).ARIBNative;

const iframe = document.querySelector<HTMLIFrameElement>('#broadcast')!;
const viewport = document.querySelector<HTMLDivElement>('#viewport')!;
const statusEl = document.querySelector<HTMLDivElement>('#status')!;

let currentEntry = '';
let currentUrlIsBlank = false;
let lastVisibleUrl = '';
let lastPlaneJson = '';
let lastStatusText = 'データ放送を受信中…';
let runtimeInstalled = false;
let autoEnterData = false;
const pendingKeys: number[] = [];

const keyCodes: Record<string, number> = {
  left: 37, up: 38, right: 39, down: 40, enter: 13, back: 461, data: 457,
  red: 403, green: 404, yellow: 405, blue: 406,
  '0': 48, '1': 49, '2': 50, '3': 51, '4': 52,
  '5': 53, '6': 54, '7': 55, '8': 56, '9': 57,
};

function setOverlayMessage(displayText: string, nativeLogText: string = displayText): void {
  const text = String(displayText || '');
  if (text !== lastStatusText) {
    lastStatusText = text;
    statusEl.textContent = text;
  }
  if (nativeLogText && nativeBridge()?.onStatus) {
    nativeBridge()!.onStatus!(String(nativeLogText));
  }
}

function reportMediaPlane(plane: AribMediaPlane): void {
  if (!nativeBridge()?.onMediaPlane) return;
  const serialized = JSON.stringify(plane);
  if (serialized === lastPlaneJson) return;
  lastPlaneJson = serialized;
  nativeBridge()!.onMediaPlane!(serialized);
}

const mediaPlaneAdapter = {
  renderMode: 'external' as const,
  mountMediaPlane(_object: HTMLElement, plane: AribMediaPlane): void {
    reportMediaPlane(plane);
  },
  updateMediaPlane(_object: HTMLElement, plane: AribMediaPlane): void {
    reportMediaPlane(plane);
  },
  unmountMediaPlane(reason: AribMediaPlaneUnmountReason): void {
    lastPlaneJson = '';
    nativeBridge()?.onMediaPlaneUnmounted?.(String(reason));
  },
};

const allowExternalNetwork = Boolean(nativeBridge()?.allowExternalNetwork?.());
const receiverInfo = (() => {
  try {
    const raw = nativeBridge()?.receiverInfoJson?.();
    if (!raw) return undefined;
    const parsed = JSON.parse(raw) as {
      zipcode?: unknown;
      prefecture?: unknown;
      regioncode?: unknown;
    };
    return {
      zipcode: typeof parsed.zipcode === 'string' && /^\d{7}$/.test(parsed.zipcode) ? parsed.zipcode : null,
      prefecture: typeof parsed.prefecture === 'number' ? parsed.prefecture : null,
      regioncode: typeof parsed.regioncode === 'number' ? parsed.regioncode : null,
    };
  } catch {
    return undefined;
  }
})();

const host = new TvAribReceiverHost({
  iframe,
  viewport,
  mediaPlaneAdapter,
  broadcastBaseUrl: '/data-broadcast/',
  userAgent: 'A-PAB/1.0',
  supportedMedia: ['6', '7'],
  getInputSource() {
    const source = nativeBridge()?.inputSource?.();
    return source === 'onair' || source === 'recorded' ? source : null;
  },
  allowExternalNetwork,
  getDeviceIdentifier: async () => null,
  systemInformation: {
    makerid: 'Honorebi',
    modelname: 'Honorebi Android TV',
    decoder: 'native',
    ...(receiverInfo ?? {}),
  },
  onStatus(value) {
    if (value && nativeBridge()?.onStatus) {
      nativeBridge()!.onStatus!(String(value));
    }
  },
  onLifecycle(event) {
    if (event.type === 'installed') {
      runtimeInstalled = true;
      statusEl.classList.add('ready');
      host.setApplicationInputActive(true);
      for (const code of pendingKeys.splice(0)) host.dispatchKey(code);
      if (autoEnterData) {
        autoEnterData = false;
        host.dispatchKey(keyCodes.data);
      }
    }
    if (event.type === 'loading' || event.type === 'navigating') {
      runtimeInstalled = false;
      statusEl.classList.remove('ready');
      setOverlayMessage('データ放送を読み込み中…', '');
    }
    if (event.type === 'exited') {
      runtimeInstalled = false;
      lastPlaneJson = '';
      pendingKeys.splice(0);
      nativeBridge()?.onApplicationExited?.();
    }
    if (event.type === 'error') {
      console.error('[HonorebiB60] runtime error:', event.message);
      nativeBridge()?.onStatus?.(`runtime-error: ${event.message}`);
    }
    if (event.type === 'frame-blocked' || event.type === 'navigation-blocked') {
      const detail = event.url || event.type;
      console.error('[HonorebiB60] navigation blocked:', detail);
      statusEl.classList.remove('ready');
      setOverlayMessage('データ放送を表示できませんでした', `lifecycle-error: ${detail}`);
    }
  },
  onUrlChange(value) {
    const url = String(value || '');
    const pathSegments = new URL(url || '/', window.location.origin).pathname.split('/');
    currentUrlIsBlank = pathSegments.some((segment) => segment.toLowerCase() === 'startup');
    if (url && !currentUrlIsBlank) lastVisibleUrl = url;
    nativeBridge()?.onUrlChanged?.(url);
  },
  onMediaPlane(plane) {
    reportMediaPlane(plane);
  },
  onCaptionSubscription(subscription) {
    nativeBridge()?.onCaptionSubscription?.(JSON.stringify(subscription.componentTags));
  },
  onOpenProgramGuide(request) {
    if (!nativeBridge()?.openProgramGuide) return false;
    return Boolean(nativeBridge()!.openProgramGuide!(JSON.stringify(request)));
  },
});

window.__ARIB_HTML5_INSTALL__ = (target) => host.installRuntime(target);

function applyProgramInfo(programInfo: Record<string, unknown> | null | undefined): void {
  if (!programInfo) return;
  if (programInfo.start_time !== undefined) {
    programInfo.start_time = new Date(programInfo.start_time as number | string);
  }
  if (programInfo.f_start_time !== undefined) {
    programInfo.f_start_time = new Date(programInfo.f_start_time as number | string);
  }
  host.setProgramInfo(programInfo as Parameters<typeof host.setProgramInfo>[0]);
}

function applyBroadcastClock(broadcastClock: Record<string, unknown> | null | undefined): void {
  if (!broadcastClock) return;
  broadcastClock.currentMediaTimeSeconds = () =>
    Number(nativeBridge()?.currentMediaTimeSeconds?.() || 0);
  host.setBroadcastClock(broadcastClock as Parameters<typeof host.setBroadcastClock>[0]);
}

window.KomorebiB60 = {
  start(json: string) {
    const payload = JSON.parse(json) as {
      entryPath?: string;
      autoEnterData?: boolean;
      lctBackgroundColorRgb?: number | null;
      captionComponentTags?: number[];
      application?: Parameters<typeof host.setApplicationInformation>[0];
      programInfo?: Record<string, unknown>;
      broadcastClock?: Record<string, unknown>;
    };
    if (!payload.entryPath) return false;
    host.clearProgramInfo();
    host.clearBroadcastClock();
    host.resetCaptions();
    if (Array.isArray(payload.captionComponentTags) && payload.captionComponentTags.length > 0) {
      host.setCaptionTracks(payload.captionComponentTags);
    }
    host.setLctBackgroundColor(payload.lctBackgroundColorRgb ?? null);
    host.setApplicationInformation(payload.application ?? null);
    applyProgramInfo(payload.programInfo);
    applyBroadcastClock(payload.broadcastClock);
    currentEntry = String(payload.entryPath).replace(/^\/+/, '');
    currentUrlIsBlank = false;
    lastVisibleUrl = '';
    lastPlaneJson = '';
    autoEnterData = Boolean(payload.autoEnterData);
    statusEl.classList.remove('ready');
    setOverlayMessage('データ放送を読み込み中…', '');
    host.loadApplication('/' + currentEntry);
    return true;
  },
  updateProgramInfo(json: string) {
    applyProgramInfo(JSON.parse(json));
    return true;
  },
  updateBroadcastClock(json: string) {
    applyBroadcastClock(JSON.parse(json));
    return true;
  },
  updateLctBackgroundColor(value: number | null) {
    host.setLctBackgroundColor(value ?? null);
    return true;
  },
  setCaptionTracks(tags: number[]) {
    if (Array.isArray(tags)) {
      host.setCaptionTracks(tags);
    }
    return true;
  },
  pushCaption(json: string) {
    const packet = JSON.parse(json) as {
      componentTag: number;
      dataType?: string;
      tmd?: string;
      data?: string;
    };
    host.pushCaption(packet);
    return true;
  },
  resourceChanged(path: string, updated: boolean) {
    if (currentEntry && updated) {
      host.notifyDataResource('/' + String(path).replace(/^\/+/, ''), 'updated');
    }
    return true;
  },
  exit() {
    currentEntry = '';
    lastPlaneJson = '';
    host.exitApplication();
    host.clearProgramInfo();
    host.clearBroadcastClock();
    statusEl.classList.add('ready');
    setOverlayMessage('', '');
    return true;
  },
};

window.KomorebiDataBroadcastingRemote = {
  press(key: string) {
    const name = String(key);
    const code = keyCodes[name];
    if (code === undefined) return false;
    if (name === 'data' && currentUrlIsBlank && lastVisibleUrl) {
      runtimeInstalled = false;
      statusEl.classList.remove('ready');
      setOverlayMessage('データ放送を読み込み中…', '');
      host.loadApplication(lastVisibleUrl);
      return true;
    }
    if (!runtimeInstalled) {
      pendingKeys.push(code);
      return true;
    }
    host.setApplicationInputActive(true);
    host.dispatchKey(code);
    return true;
  },
};

window.addEventListener('error', (event) => {
  console.error('[HonorebiB60] error:', event.message);
  nativeBridge()?.onStatus?.(`error: ${event.message}`);
});
window.addEventListener('unhandledrejection', (event) => {
  const msg = String(event.reason?.message || event.reason);
  console.error('[HonorebiB60] unhandledrejection:', msg);
  nativeBridge()?.onStatus?.(`rejection: ${msg}`);
});
