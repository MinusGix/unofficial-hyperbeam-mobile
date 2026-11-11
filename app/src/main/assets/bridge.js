/* Generated from bridge/src by bridge/build.mjs. Do not edit by hand. */
"use strict";
(() => {
  // src/channel.ts
  var port = globalThis.HbNative;
  var hasNative = !!port;
  function send(ev) {
    if (!port) return;
    try {
      port.postMessage(JSON.stringify(ev));
    } catch {
    }
  }
  function log(level, msg) {
    send({ type: "log", level, msg });
  }
  function onCommand(handler) {
    if (!port) return;
    port.onmessage = (ev) => {
      let cmd;
      try {
        cmd = JSON.parse(ev.data);
      } catch {
        log("warn", "bad command json");
        return;
      }
      try {
        handler(cmd);
      } catch (e) {
        log("error", `command ${cmd.type} failed: ${String(e)}`);
      }
    };
  }

  // src/client-hook.ts
  var current = null;
  var listeners = [];
  function getClient() {
    return current;
  }
  var SESSION_HOST = /^[a-z0-9]{25}\.hyperbeam\.com$/;
  var JS_TYPE = /javascript|ecmascript/i;
  function isHyperbeamHost(host) {
    return host === "hyperbeam.com" || host.endsWith(".hyperbeam.com") || host === "jquery.tutturu.workers.dev";
  }
  function looksLikeClientModule(res) {
    let host;
    try {
      host = new URL(res.url).hostname;
    } catch {
      return false;
    }
    if (!isHyperbeamHost(host)) return false;
    const ct = res.headers.get("content-type") || "";
    return JS_TYPE.test(ct) || SESSION_HOST.test(host);
  }
  var tagged = /* @__PURE__ */ new WeakSet();
  function observe(m) {
    const data = m.data;
    switch (m.type) {
      case "connection":
        if (current && data) {
          current.width = data.width;
          current.height = data.height;
          current.userId = data.user_id;
        }
        send({
          type: "client",
          state: "connected",
          width: current == null ? void 0 : current.width,
          height: current == null ? void 0 : current.height,
          userId: current == null ? void 0 : current.userId
        });
        break;
      case "resize":
        if (current && data) {
          current.width = data.width;
          current.height = data.height;
          send({ type: "resize", width: current.width, height: current.height });
        }
        break;
      case "connection_state":
        send({ type: "connection", state: String((data == null ? void 0 : data.state) ?? "unknown") });
        break;
      case "disconnect":
        send({ type: "disconnect", reason: String((data == null ? void 0 : data.type) ?? "unknown") });
        break;
    }
    for (const fn of listeners) {
      try {
        fn(m);
      } catch {
      }
    }
  }
  function wrapClient(factory, args) {
    const origCb = args[2];
    if (typeof origCb === "function") {
      args[2] = function(m) {
        try {
          observe(m);
        } catch {
        }
        return origCb.call(this, m);
      };
    }
    const adopt = (r) => {
      if (!r || typeof r.handleMessage !== "function") return r;
      const client = { handleMessage: r.handleMessage };
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
          return origDestroy == null ? void 0 : origDestroy.call(r);
        }
      };
    };
    const result = factory(...args);
    if (result && typeof result.then === "function") {
      return result.then(adopt);
    }
    return adopt(result);
  }
  function installClientHook() {
    globalThis.__hbBridgeWrap = wrapClient;
    const origFetch = window.fetch;
    window.fetch = async function(...a) {
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
      }
      return res;
    };
    const origCreate = URL.createObjectURL;
    URL.createObjectURL = function(obj) {
      const url = origCreate.call(URL, obj);
      if (obj instanceof Blob && tagged.has(obj)) {
        const u = JSON.stringify(url);
        const wrapper = `import * as m from ${u};
export * from ${u};
export default function (...a) { return globalThis.__hbBridgeWrap(m.default, a); }
`;
        return origCreate.call(URL, new Blob([wrapper], { type: "text/javascript" }));
      }
      return url;
    };
  }

  // src/focus.ts
  function isEditable(el) {
    if (!el) return false;
    if (el instanceof HTMLTextAreaElement) return !el.readOnly && !el.disabled;
    if (el instanceof HTMLInputElement) {
      const nonText = ["button", "checkbox", "color", "file", "hidden", "image", "radio", "range", "reset", "submit"];
      return !nonText.includes(el.type) && !el.readOnly && !el.disabled;
    }
    return el.isContentEditable === true;
  }
  function inShadow(el) {
    return el.getRootNode() instanceof ShadowRoot;
  }
  var last = null;
  function report(el) {
    const editable = isEditable(el);
    const inClient = !!el && inShadow(el);
    const key = `${editable}:${inClient}`;
    if (key === last) return;
    last = key;
    send({ type: "pageFocus", editable, inClient });
  }
  function installFocusTracking() {
    document.addEventListener(
      "focusin",
      (e) => report(e.composedPath()[0] ?? e.target),
      true
    );
    document.addEventListener(
      "focusout",
      () => queueMicrotask(() => report(deepActiveElement())),
      true
    );
  }
  function deepActiveElement() {
    let el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
    return el;
  }

  // src/input.ts
  var lastPath = null;
  function reportPath(p) {
    if (p !== lastPath) {
      lastPath = p;
      send({ type: "inputPath", path: p });
    }
  }
  function emit(type, key, mods) {
    const client = getClient();
    if (client) {
      reportPath("client");
      client.handleMessage({
        type,
        key,
        ctrlKey: !!mods.ctrl,
        metaKey: !!mods.meta,
        altKey: !!mods.alt,
        shiftKey: !!mods.shift
      });
      return;
    }
    reportPath("synthetic");
    const target = document.activeElement ?? window;
    target.dispatchEvent(
      new KeyboardEvent(type, {
        key,
        ctrlKey: !!mods.ctrl,
        metaKey: !!mods.meta,
        altKey: !!mods.alt,
        shiftKey: !!mods.shift,
        bubbles: true,
        cancelable: true,
        composed: true
      })
    );
  }
  function sendKey(key, mods = {}, phase = "press") {
    if (phase !== "up") emit("keydown", key, mods);
    if (phase !== "down") emit("keyup", key, mods);
  }
  function keyForChar(ch) {
    switch (ch) {
      case "\n":
      case "\r":
        return "Enter";
      case "	":
        return "Tab";
      case "\b":
        return "Backspace";
      default:
        return ch;
    }
  }
  function sendText(text) {
    let prevCR = false;
    for (const ch of text) {
      if (ch === "\n" && prevCR) {
        prevCR = false;
        continue;
      }
      prevCR = ch === "\r";
      sendKey(keyForChar(ch));
    }
  }
  function sendBackspace(count) {
    const n = Math.max(0, Math.min(count | 0, 1e4));
    for (let i = 0; i < n; i++) sendKey("Backspace");
  }
  function sendClientMessage(m) {
    const client = getClient();
    if (!client) return false;
    client.handleMessage(m);
    return true;
  }

  // src/location.ts
  var last2 = "";
  function report2() {
    const url = location.href;
    if (url === last2) return;
    last2 = url;
    send({ type: "location", url, title: document.title });
  }
  function installLocationTracking() {
    for (const name of ["pushState", "replaceState"]) {
      const orig = history[name];
      history[name] = function(...a) {
        const r = orig.apply(this, a);
        queueMicrotask(report2);
        return r;
      };
    }
    window.addEventListener("popstate", report2);
    window.addEventListener("hashchange", report2);
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", report2, { once: true });
    } else {
      report2();
    }
  }

  // src/netlog.ts
  var MAX_FRAMES = 300;
  var MAX_TEXT = 600;
  var frames = [];
  var sockets = [];
  function sampleOf(data) {
    if (typeof data === "string") {
      return { kind: "text", size: data.length, sample: data.slice(0, MAX_TEXT) };
    }
    let bytes = null;
    if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
    else if (ArrayBuffer.isView(data)) bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    if (bytes) {
      const head = bytes.subarray(0, 256);
      let b64 = "";
      try {
        b64 = btoa(String.fromCharCode(...head));
      } catch {
      }
      return { kind: "binary", size: bytes.byteLength, sample: b64 };
    }
    if (data instanceof Blob) return { kind: "binary", size: data.size, sample: "(blob)" };
    return { kind: "binary", size: 0, sample: "" };
  }
  function record(dir, socket, data) {
    const s = sampleOf(data);
    frames.push({ t: Date.now(), dir, socket, ...s });
    if (frames.length > MAX_FRAMES) frames.splice(0, frames.length - MAX_FRAMES);
  }
  function installNetLog() {
    const Orig = window.WebSocket;
    if (!Orig) return;
    let nextId = 1;
    class TappedWebSocket extends Orig {
      constructor(url, protocols) {
        super(url, protocols);
        const id = nextId++;
        sockets.push({ id, url: String(url), opened: Date.now() });
        if (sockets.length > 50) sockets.shift();
        this.addEventListener("message", (e) => record("in", id, e.data));
        this.__hbId = id;
      }
      send(data) {
        record("out", this.__hbId, data);
        super.send(data);
      }
    }
    window.WebSocket = TappedWebSocket;
  }
  function netLogSnapshot() {
    return { sockets: sockets.slice(), frames: frames.slice() };
  }

  // src/protocol.ts
  var BRIDGE_VERSION = 1;

  // src/visibility.ts
  var pinned = true;
  var docProto = Document.prototype;
  var hiddenDesc = Object.getOwnPropertyDescriptor(docProto, "hidden");
  var stateDesc = Object.getOwnPropertyDescriptor(docProto, "visibilityState");
  function reallyHidden() {
    const get = stateDesc == null ? void 0 : stateDesc.get;
    return get ? get.call(document) === "hidden" : false;
  }
  function setPinned(on) {
    pinned = on;
  }
  function isPinned() {
    return pinned;
  }
  function installVisibilityPin() {
    if (hiddenDesc == null ? void 0 : hiddenDesc.get) {
      const orig = hiddenDesc.get;
      Object.defineProperty(docProto, "hidden", {
        configurable: true,
        enumerable: hiddenDesc.enumerable,
        get() {
          return pinned ? false : orig.call(this);
        }
      });
    }
    if (stateDesc == null ? void 0 : stateDesc.get) {
      const orig = stateDesc.get;
      Object.defineProperty(docProto, "visibilityState", {
        configurable: true,
        enumerable: stateDesc.enumerable,
        get() {
          return pinned ? "visible" : orig.call(this);
        }
      });
    }
    const webkitHidden = Object.getOwnPropertyDescriptor(docProto, "webkitHidden");
    if (webkitHidden == null ? void 0 : webkitHidden.get) {
      const orig = webkitHidden.get;
      Object.defineProperty(docProto, "webkitHidden", {
        configurable: true,
        get() {
          return pinned ? false : orig.call(this);
        }
      });
    }
    const swallow = (e) => {
      if (pinned) e.stopImmediatePropagation();
    };
    window.addEventListener("visibilitychange", swallow, true);
    window.addEventListener("webkitvisibilitychange", swallow, true);
    const origRaf = window.requestAnimationFrame.bind(window);
    const origCancel = window.cancelAnimationFrame.bind(window);
    window.requestAnimationFrame = (cb) => {
      if (pinned && reallyHidden()) {
        const id = window.setTimeout(() => cb(performance.now()), 100);
        return -id;
      }
      return origRaf(cb);
    };
    window.cancelAnimationFrame = (id) => {
      if (id < 0) window.clearTimeout(-id);
      else origCancel(id);
    };
  }

  // src/snapshot.ts
  var MAX_HTML = 3e6;
  function describeShadowRoots() {
    const out = [];
    for (const el of Array.from(document.querySelectorAll("*"))) {
      const root = el.shadowRoot;
      if (root) {
        out.push({
          host: `${el.tagName.toLowerCase()}#${el.id}.${el.className}`,
          html: root.innerHTML.slice(0, 5e4)
        });
      }
      if (out.length >= 20) break;
    }
    return out;
  }
  function takeSnapshot() {
    const client = getClient();
    return {
      bridgeVersion: BRIDGE_VERSION,
      takenAt: (/* @__PURE__ */ new Date()).toISOString(),
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
      html: document.documentElement.outerHTML.slice(0, MAX_HTML)
    };
  }

  // src/index.ts
  function handle(cmd) {
    switch (cmd.type) {
      case "key":
        sendKey(cmd.key, cmd, cmd.phase ?? "press");
        break;
      case "text":
        sendText(cmd.text);
        break;
      case "backspace":
        sendBackspace(cmd.count);
        break;
      case "pinVisibility":
        setPinned(cmd.on);
        break;
      case "videoPaused":
        sendClientMessage({ type: "video_paused", data: cmd.on });
        break;
      case "volume":
        sendClientMessage({ type: "volume", data: cmd.value });
        break;
      case "ping":
        sendClientMessage({ type: "ping" });
        break;
      case "snapshot":
        send({ type: "snapshot", id: cmd.id, data: takeSnapshot() });
        break;
    }
  }
  function install() {
    if (window.__hbBridgeInstalled) return;
    window.__hbBridgeInstalled = BRIDGE_VERSION;
    const top = window === window.top;
    installVisibilityPin();
    installClientHook();
    installNetLog();
    installFocusTracking();
    if (top) installLocationTracking();
    onCommand(handle);
    send({ type: "ready", version: BRIDGE_VERSION, top, url: location.href });
    if (!hasNative) {
      return;
    }
    log("info", `bridge v${BRIDGE_VERSION} installed in ${top ? "top" : "sub"} frame`);
  }
  install();
})();
