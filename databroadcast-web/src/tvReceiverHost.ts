import { AribReceiverHost, type AribReceiverHostOptions } from 'libaribhtml5';

const frameKeyTokens = new WeakMap<Document, string>();

function configureTvIframe(iframe: HTMLIFrameElement): void {
  iframe.inert = false;
  iframe.style.setProperty('pointer-events', 'none', 'important');
  iframe.tabIndex = -1;
}

function dispatchKeyIntoIframe(iframe: HTMLIFrameElement, code: number): boolean {
  const contentWindow = iframe.contentWindow;
  if (!contentWindow || !Number.isInteger(code)) return false;
  try {
    const doc = contentWindow.document;
    let token = frameKeyTokens.get(doc);
    if (token === undefined) {
      token = crypto.randomUUID();
      const script = doc.createElement('script');
      script.textContent = `(() => {
        const token = ${JSON.stringify(token)};
        window.addEventListener('message', event => {
          if (event.source !== parent || event.origin !== location.origin ||
              event.data?.type !== 'arib-receiver-key' || event.data.token !== token ||
              !Number.isInteger(event.data.code)) return;
          const eventTarget = document.activeElement ?? document.body ?? document.documentElement ?? document;
          for (const type of ['keydown', 'keyup']) {
            const key = new KeyboardEvent(type, {bubbles: true, cancelable: true, view: window});
            Object.defineProperties(key, {
              keyCode: {value: event.data.code}, which: {value: event.data.code}
            });
            eventTarget.dispatchEvent(key);
          }
        });
      })()`;
      doc.documentElement.appendChild(script);
      script.remove();
      frameKeyTokens.set(doc, token);
    }
    contentWindow.postMessage(
      { type: 'arib-receiver-key', token, code },
      window.location.origin,
    );
    return true;
  } catch {
    return false;
  }
}

/**
 * Android TV WebView adapter over standard `libaribhtml5`'s `AribReceiverHost`.
 * Keeps D-pad focus owned by the native TV overlay while dispatching key events
 * cleanly inside the broadcast iframe's own realm.
 */
export class TvAribReceiverHost extends AribReceiverHost {
  private tvDestroyed = false;
  private readonly tvIframe: HTMLIFrameElement;

  constructor(options: AribReceiverHostOptions) {
    super(options);
    this.tvIframe = options.iframe;
    configureTvIframe(this.tvIframe);
    this.tvIframe.addEventListener('load', this.handleTvFrameLoad);
  }

  override dispatchKey(code: number): boolean {
    if (this.tvDestroyed || !this.getApplicationPresentationState().inputActive) {
      return false;
    }
    return dispatchKeyIntoIframe(this.tvIframe, code);
  }

  override destroy(): void {
    if (this.tvDestroyed) return;
    this.tvDestroyed = true;
    this.tvIframe.removeEventListener('load', this.handleTvFrameLoad);
    super.destroy();
  }

  private readonly handleTvFrameLoad = (): void => {
    configureTvIframe(this.tvIframe);
  };
}
