// Capture Hyperbeam's streaming client so we can drive it directly.
//
// @hyperbeam/web fetches the session's embed URL, which returns the real
// client as an ES module. It turns the response into a blob URL, imports it,
// and calls `module.default(shadowRoot, url, onMessage, opts)`, which returns
// `{ handleMessage, destroy }`. Everything the public API does (sendEvent,
// ping, video_paused, volume, ...) is a call to that `handleMessage`.
//
// We tag JavaScript responses fetched from Hyperbeam hosts, and when such a
// blob is turned into an object URL we hand back a tiny wrapper module that
// imports the original and routes its default export through `wrapClient`.

import { send } from "./channel";

export type HbMessage = { type?: string; [k: string]: unknown };

type ClientModuleResult = {
  handleMessage: (m: HbMessage) => void;
  destroy?: () => void;
};

type Client = {
  handleMessage: (m: HbMessage) => void;
  /** The shadow root the client renders into (holds the <video>). */
  root?: ShadowRoot;
  width?: number;
  height?: number;
  userId?: string;
};

let current: Client | null = null;
const listeners: Array<(m: HbMessage) => void> = [];

export function getClient(): Client | null {
  return current;
}

/** The <video> showing the shared browser, if a client is running. */
export function getVideo(): HTMLVideoElement | null {
  return current?.root?.querySelector("video") ?? null;
}

/** Observe every message the streaming client reports back to the page. */
export function onClientMessage(fn: (m: HbMessage) => void): void {
  listeners.push(fn);
}

const SESSION_HOST = /^[a-z0-9]{25}\.hyperbeam\.com$/;
const JS_TYPE = /javascript|ecmascript/i;

export function isHyperbeamHost(host: string): boolean {
  return (
    host === "hyperbeam.com" ||
    host.endsWith(".hyperbeam.com") ||
    host === "jquery.tutturu.workers.dev"
  );
}

function looksLikeClientModule(res: Response): boolean {
  let host: string;
  try {
    host = new URL(res.url).hostname;
  } catch {
    return false;
  }
  if (!isHyperbeamHost(host)) return false;
  const ct = res.headers.get("content-type") || "";
  return JS_TYPE.test(ct) || SESSION_HOST.test(host);
}

const tagged = new WeakSet<Blob>();

function observe(m: HbMessage): void {
  const data = (m as { data?: Record<string, unknown> }).data;
  switch (m.type) {
    case "connection":
      if (current && data) {
        current.width = data.width as number;
        current.height = data.height as number;
        current.userId = data.user_id as string;
      }
      send({
        type: "client",
        state: "connected",
        width: current?.width,
        height: current?.height,
        userId: current?.userId,
      });
      break;
    case "resize":
      if (current && data) {
        current.width = data.width as number;
        current.height = data.height as number;
        send({ type: "resize", width: current.width, height: current.height });
      }
      break;
    case "connection_state":
      send({ type: "connection", state: String(data?.state ?? "unknown") });
      break;
    case "disconnect":
      send({ type: "disconnect", reason: String(data?.type ?? "unknown") });
      break;
  }
  for (const fn of listeners) {
    try {
      fn(m);
    } catch {
      /* a listener bug must never break the client */
    }
  }
}

export function wrapClient(
  factory: (...a: unknown[]) => ClientModuleResult | Promise<ClientModuleResult>,
  args: unknown[],
): ClientModuleResult | Promise<ClientModuleResult> {
  const origCb = args[2];
  if (typeof origCb === "function") {
    args[2] = function (this: unknown, m: HbMessage) {
      try {
        observe(m);
      } catch {
        /* ignore */
      }
      return (origCb as (m: HbMessage) => unknown).call(this, m);
    };
  }
  const adopt = (r: ClientModuleResult): ClientModuleResult => {
    if (!r || typeof r.handleMessage !== "function") return r;
    const root = args[0] instanceof ShadowRoot ? args[0] : undefined;
    const client: Client = { handleMessage: r.handleMessage, root };
    current = client;
    send({ type: "client", state: "hooked" });
    const origDestroy = r.destroy;
    return {
      ...r,
      destroy() {
        if (current === client) {
          current = null;
          send({ type: "client", state: "destroyed" });
        }
        return origDestroy?.call(r);
      },
    };
  };
  const result = factory(...args);
  if (result && typeof (result as Promise<ClientModuleResult>).then === "function") {
    return (result as Promise<ClientModuleResult>).then(adopt);
  }
  return adopt(result as ClientModuleResult);
}

export function installClientHook(): void {
  (globalThis as unknown as { __hbBridgeWrap: typeof wrapClient }).__hbBridgeWrap = wrapClient;

  const origFetch = window.fetch;
  window.fetch = async function (this: unknown, ...a: Parameters<typeof fetch>) {
    const res = await origFetch.apply(this, a);
    try {
      if (looksLikeClientModule(res)) {
        const origBlob = res.blob.bind(res);
        res.blob = async () => {
          const b = await origBlob();
          tagged.add(b);
          return b;
        };
      }
    } catch {
      /* never interfere with the page's own fetches */
    }
    return res;
  };

  const origCreate = URL.createObjectURL;
  URL.createObjectURL = function (obj: Blob | MediaSource): string {
    const url = origCreate.call(URL, obj);
    if (obj instanceof Blob && tagged.has(obj)) {
      const u = JSON.stringify(url);
      const wrapper =
        `import * as m from ${u};\n` +
        `export * from ${u};\n` +
        `export default function (...a) { return globalThis.__hbBridgeWrap(m.default, a); }\n`;
      return origCreate.call(URL, new Blob([wrapper], { type: "text/javascript" }));
    }
    return url;
  };
}
