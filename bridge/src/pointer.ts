// Mouse and wheel input for the shared browser, plus where its video sits on
// the page so the app can map finger positions onto it.
//
// On phones the site sends touches as remote *touch* events (wire types
// "t"/"y"/"T"), so a drag scrolls the remote page and text can't be selected.
// The app's mouse mode sends real mouse events instead. Positions are
// normalised 0..1 over the video, the same space the SDK's sendEvent uses.

import { send } from "./channel";
import { getClient, getVideo } from "./client-hook";

const clamp = (v: number) => Math.min(1, Math.max(0, Number.isFinite(v) ? v : 0));

export function sendMouse(action: "down" | "move" | "up", x: number, y: number, button = 0): boolean {
  const c = getClient();
  if (!c) return false;
  c.handleMessage({ type: `mouse${action}`, x: clamp(x), y: clamp(y), button });
  return true;
}

export function sendWheel(x: number, y: number, deltaX: number, deltaY: number): boolean {
  const c = getClient();
  if (!c) return false;
  // Park the pointer over the spot first so the right element scrolls.
  c.handleMessage({ type: "mousemove", x: clamp(x), y: clamp(y) });
  c.handleMessage({ type: "wheel", deltaX, deltaY });
  return true;
}

let lastRect = "";

function reportRect(): void {
  const v = getVideo();
  let msg = { x: 0, y: 0, w: 0, h: 0 };
  if (v && v.isConnected) {
    const r = v.getBoundingClientRect();
    msg = { x: r.left, y: r.top, w: r.width, h: r.height };
    // A video draws its picture letterboxed inside its box ("contain"), so
    // report the picture itself: that's the area remote coordinates span.
    const vw = v.videoWidth;
    const vh = v.videoHeight;
    if (vw > 0 && vh > 0 && r.width > 0 && r.height > 0) {
      const scale = Math.min(r.width / vw, r.height / vh);
      const w = vw * scale;
      const h = vh * scale;
      msg = { x: r.left + (r.width - w) / 2, y: r.top + (r.height - h) / 2, w, h };
    }
  }
  const key = `${msg.x.toFixed(1)},${msg.y.toFixed(1)},${msg.w.toFixed(1)},${msg.h.toFixed(1)},${devicePixelRatio},${innerWidth}`;
  if (key === lastRect) return;
  lastRect = key;
  send({ type: "videoRect", ...msg, dpr: devicePixelRatio, vw: innerWidth });
}

export function installVideoTracking(): void {
  window.setInterval(reportRect, 400);
  window.addEventListener("resize", reportRect);
  window.addEventListener("scroll", reportRect, true);
}

// --- Theater (fullscreen video) ------------------------------------------

const PAGE_CSS = `
#embed, [data-hb-theater] {
  position: fixed !important; inset: 0 !important; z-index: 2147483646 !important;
  width: 100vw !important; height: 100vh !important; max-width: none !important;
  margin: 0 !important; background: #000 !important;
}
html, body { overflow: hidden !important; }
`;
const SHADOW_CSS = `
video {
  width: auto !important; height: auto !important;
  max-width: 100% !important; max-height: 100% !important;
}
`;

let pageStyle: HTMLStyleElement | null = null;
let shadowStyle: HTMLStyleElement | null = null;

export function setTheater(on: boolean): void {
  const c = getClient();
  const host = c?.root?.host as HTMLElement | undefined;
  if (on) {
    if (host) host.setAttribute("data-hb-theater", "");
    if (!pageStyle) {
      pageStyle = document.createElement("style");
      pageStyle.textContent = PAGE_CSS;
      document.head.appendChild(pageStyle);
    }
    if (c?.root && !shadowStyle) {
      shadowStyle = document.createElement("style");
      shadowStyle.textContent = SHADOW_CSS;
      c.root.appendChild(shadowStyle);
    }
  } else {
    host?.removeAttribute("data-hb-theater");
    pageStyle?.remove();
    shadowStyle?.remove();
    pageStyle = null;
    shadowStyle = null;
  }
  lastRect = "";
  requestAnimationFrame(reportRect);
}
