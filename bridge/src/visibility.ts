// Keep the page believing it is visible while the app is in the background.
//
// Android hides the WebView when the app leaves the screen, which flips
// document.visibilityState to "hidden" and stops requestAnimationFrame. Sites
// commonly pause or tear down work on that signal, which is the "stalls in the
// background" problem. While pinned, we report "visible", swallow
// visibilitychange, and run animation-frame callbacks off a timer when the
// real document is hidden.

let pinned = true;

const docProto = Document.prototype;
const hiddenDesc = Object.getOwnPropertyDescriptor(docProto, "hidden");
const stateDesc = Object.getOwnPropertyDescriptor(docProto, "visibilityState");

export function reallyHidden(): boolean {
  const get = stateDesc?.get;
  return get ? get.call(document) === "hidden" : false;
}

export function setPinned(on: boolean): void {
  pinned = on;
}

export function isPinned(): boolean {
  return pinned;
}

export function installVisibilityPin(): void {
  if (hiddenDesc?.get) {
    const orig = hiddenDesc.get;
    Object.defineProperty(docProto, "hidden", {
      configurable: true,
      enumerable: hiddenDesc.enumerable,
      get(this: Document) {
        return pinned ? false : orig.call(this);
      },
    });
  }
  if (stateDesc?.get) {
    const orig = stateDesc.get;
    Object.defineProperty(docProto, "visibilityState", {
      configurable: true,
      enumerable: stateDesc.enumerable,
      get(this: Document) {
        return pinned ? "visible" : orig.call(this);
      },
    });
  }
  const webkitHidden = Object.getOwnPropertyDescriptor(docProto, "webkitHidden");
  if (webkitHidden?.get) {
    const orig = webkitHidden.get;
    Object.defineProperty(docProto, "webkitHidden", {
      configurable: true,
      get(this: Document) {
        return pinned ? false : orig.call(this);
      },
    });
  }

  // Window capture listeners run before anything the page registers on
  // document, so stopping propagation here hides the event from the site.
  const swallow = (e: Event) => {
    if (pinned) e.stopImmediatePropagation();
  };
  window.addEventListener("visibilitychange", swallow, true);
  window.addEventListener("webkitvisibilitychange", swallow, true);

  const origRaf = window.requestAnimationFrame.bind(window);
  const origCancel = window.cancelAnimationFrame.bind(window);
  // Timer-backed frames get negative ids so cancel can tell them apart.
  window.requestAnimationFrame = (cb: FrameRequestCallback): number => {
    if (pinned && reallyHidden()) {
      const id = window.setTimeout(() => cb(performance.now()), 100);
      return -id;
    }
    return origRaf(cb);
  };
  window.cancelAnimationFrame = (id: number): void => {
    if (id < 0) window.clearTimeout(-id);
    else origCancel(id);
  };
}
