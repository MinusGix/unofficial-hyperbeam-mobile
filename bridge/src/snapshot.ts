import { getClient } from "./client-hook";
import { netLogSnapshot } from "./netlog";
import { BRIDGE_VERSION } from "./protocol";
import { isPinned, reallyHidden } from "./visibility";

const MAX_HTML = 3_000_000;

function describeShadowRoots(): Array<{ host: string; html: string }> {
  const out: Array<{ host: string; html: string }> = [];
  for (const el of Array.from(document.querySelectorAll("*"))) {
    const root = (el as Element).shadowRoot;
    if (root) {
      out.push({
        host: `${el.tagName.toLowerCase()}#${el.id}.${(el as HTMLElement).className}`,
        html: root.innerHTML.slice(0, 50_000),
      });
    }
    if (out.length >= 20) break;
  }
  return out;
}

/** Everything useful for figuring out how the live site is built. */
export function takeSnapshot(): unknown {
  const client = getClient();
  return {
    bridgeVersion: BRIDGE_VERSION,
    takenAt: new Date().toISOString(),
    url: location.href,
    title: document.title,
    userAgent: navigator.userAgent,
    viewport: { w: innerWidth, h: innerHeight, dpr: devicePixelRatio },
    visibility: { pinned: isPinned(), reallyHidden: reallyHidden() },
    client: client ? { width: client.width, height: client.height, userId: client.userId } : null,
    scripts: Array.from(document.scripts).map((s) => s.src || `inline:${(s.textContent || "").slice(0, 300)}`),
    stylesheets: Array.from(document.styleSheets).map((s) => s.href || "inline"),
    resources: performance.getEntriesByType("resource").map((e) => e.name).slice(-300),
    frames: Array.from(document.querySelectorAll("iframe")).map((f) => f.src),
    shadowRoots: describeShadowRoots(),
    net: netLogSnapshot(),
    html: document.documentElement.outerHTML.slice(0, MAX_HTML),
  };
}
