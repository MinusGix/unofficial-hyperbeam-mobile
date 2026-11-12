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

  // src/chat.ts
  var ROW = "div.group.relative.my-2";
  var BODY = ".whitespace-pre-wrap.break-words";
  var MAX_INITIAL = 8;
  var MAX_REMEMBERED = 400;
  var seen = /* @__PURE__ */ new Set();
  var seenOrder = [];
  var initialSent = false;
  function text(el) {
    return ((el == null ? void 0 : el.textContent) ?? "").replace(/\s+/g, " ").trim();
  }
  function mediaLabel(row) {
    const media = row.querySelectorAll("video[src], img[src]");
    for (const m of Array.from(media)) {
      if (m.classList.contains("rounded-full")) continue;
      const src = m.getAttribute("src") ?? "";
      if (/tenor\.com|giphy\.com|\.gif(\?|$)/i.test(src)) return "GIF";
      return m.tagName === "VIDEO" ? "Video" : "Image";
    }
    return "";
  }
  function readChat(root = document) {
    const rows = Array.from(root.querySelectorAll(ROW)).filter((r) => r.querySelector(BODY) || mediaLabel(r));
    const out = [];
    const counts = /* @__PURE__ */ new Map();
    let author = "";
    let time = "";
    for (const row of rows) {
      const name = row.querySelector("span.font-medium");
      if (name) author = text(name);
      const t = row.querySelector(".line-clamp-1.text-xs") ?? row.querySelector(".absolute.left-4.text-xs");
      if (t) time = text(t);
      let body = text(row.querySelector(BODY));
      const media = mediaLabel(row);
      if (media) body = body ? `${body} [${media}]` : `[${media}]`;
      if (!body || !author) continue;
      const base = `${author}\0${time}\0${body}`;
      const n = (counts.get(base) ?? 0) + 1;
      counts.set(base, n);
      out.push({ key: `${base}\0${n}`, author, text: body, time });
    }
    return out;
  }
  function remember(key) {
    seen.add(key);
    seenOrder.push(key);
    if (seenOrder.length > MAX_REMEMBERED) seen.delete(seenOrder.shift());
  }
  function scanChat() {
    const all = readChat();
    if (all.length === 0) return;
    if (!initialSent) {
      initialSent = true;
      for (const m of all) remember(m.key);
      const recent = all.slice(-MAX_INITIAL);
      send({ type: "chat", initial: true, messages: recent.map(strip) });
      return;
    }
    const fresh = all.filter((m) => !seen.has(m.key));
    if (fresh.length === 0) return;
    for (const m of fresh) remember(m.key);
    send({ type: "chat", initial: false, messages: fresh.map(strip) });
  }
  function strip(m) {
    return { author: m.author, text: m.text, time: m.time };
  }
  function installChatReader() {
    let timer;
    const schedule = () => {
      if (timer !== void 0) return;
      timer = window.setTimeout(() => {
        timer = void 0;
        try {
          scanChat();
        } catch {
        }
      }, 250);
    };
    const start = () => {
      new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true, characterData: true });
      schedule();
    };
    if (document.body) start();
    else document.addEventListener("DOMContentLoaded", start, { once: true });
  }
  function sendChat(message) {
    var _a;
    const box = document.querySelector("textarea#message, textarea[name=message]");
    if (!box) return false;
    const setter = (_a = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")) == null ? void 0 : _a.set;
    box.focus();
    setter ? setter.call(box, message) : box.value = message;
    box.dispatchEvent(new InputEvent("input", { bubbles: true, data: message, inputType: "insertText" }));
    const enter = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
    box.dispatchEvent(new KeyboardEvent("keydown", enter));
    box.dispatchEvent(new KeyboardEvent("keypress", enter));
    box.dispatchEvent(new KeyboardEvent("keyup", enter));
    if (box.value === message) {
      const form = box.closest("form");
      if (form) {
        if (typeof form.requestSubmit === "function") form.requestSubmit();
        else form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
      }
    }
    box.blur();
    return true;
  }

  // src/client-hook.ts
  var current = null;
  var listeners = [];
  function getClient() {
    return current;
  }
  function getVideo() {
    var _a;
    return ((_a = current == null ? void 0 : current.root) == null ? void 0 : _a.querySelector("video")) ?? null;
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
      const root = args[0] instanceof ShadowRoot ? args[0] : void 0;
      const client = { handleMessage: r.handleMessage, root };
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
  function sendText(text2) {
    let prevCR = false;
    for (const ch of text2) {
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
  var MAX_HTTP = 200;
  var MAX_BODY = 4e3;
  var http = [];
  function pushHttp(h) {
    http.push(h);
    if (http.length > MAX_HTTP) http.splice(0, http.length - MAX_HTTP);
    return h;
  }
  function interesting(url) {
    return !/\.(woff2?|png|jpe?g|svg|gif|webp|mp4|webm|opus|css|ico)(\?|$)/i.test(url);
  }
  async function sampleBody(res, h) {
    const body = res.body;
    if (!body) return;
    const reader = body.getReader();
    const dec = new TextDecoder();
    let text2 = "";
    let bytes = 0;
    const started = Date.now();
    try {
      while (text2.length < MAX_BODY && Date.now() - started < 15e3) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        text2 += dec.decode(value, { stream: true });
        h.sample = text2.slice(0, MAX_BODY);
        h.bytes = bytes;
      }
    } catch {
    } finally {
      reader.cancel().catch(() => {
      });
    }
  }
  function installHttpLog() {
    const origFetch = window.fetch;
    window.fetch = async function(...a) {
      var _a;
      const started = Date.now();
      const req = a[0];
      const url = typeof req === "string" ? req : req instanceof URL ? req.href : req.url;
      const method = (((_a = a[1]) == null ? void 0 : _a.method) ?? (req instanceof Request ? req.method : "GET")).toUpperCase();
      const res = await origFetch.apply(this, a);
      try {
        if (interesting(url)) {
          const h = pushHttp({
            t: started,
            via: "fetch",
            method,
            url: res.url || url,
            status: res.status,
            contentType: res.headers.get("content-type") ?? void 0,
            ms: Date.now() - started
          });
          const ct = h.contentType ?? "";
          if (!/javascript|octet-stream|image|video|audio|font/.test(ct)) {
            sampleBody(res.clone(), h);
          }
        }
      } catch {
      }
      return res;
    };
    const xo = XMLHttpRequest.prototype.open;
    const xs = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
      this.__hb = { t: Date.now(), via: "xhr", method: String(method).toUpperCase(), url: String(url) };
      return xo.call(this, method, url, ...rest);
    };
    XMLHttpRequest.prototype.send = function(body) {
      const h = this.__hb;
      if (h && interesting(h.url)) {
        pushHttp(h);
        this.addEventListener("loadend", () => {
          h.status = this.status;
          h.contentType = this.getResponseHeader("content-type") ?? void 0;
          h.ms = Date.now() - h.t;
          if (this.responseType === "" || this.responseType === "text") h.sample = this.responseText.slice(0, MAX_BODY);
        });
      }
      return xs.call(this, body);
    };
    const OrigES = window.EventSource;
    if (OrigES) {
      class TappedEventSource extends OrigES {
        constructor(url, init) {
          super(url, init);
          const h = pushHttp({ t: Date.now(), via: "eventsource", method: "GET", url: String(url), sample: "" });
          this.addEventListener("message", (e) => {
            if ((h.sample ?? "").length < MAX_BODY) h.sample += `${String(e.data).slice(0, 500)}
`;
          });
        }
      }
      window.EventSource = TappedEventSource;
    }
  }
  function netLogSnapshot() {
    return { sockets: sockets.slice(), frames: frames.slice(), http: http.slice() };
  }

  // src/pointer.ts
  var clamp = (v) => Math.min(1, Math.max(0, Number.isFinite(v) ? v : 0));
  function sendMouse(action, x, y, button = 0) {
    const c = getClient();
    if (!c) return false;
    c.handleMessage({ type: `mouse${action}`, x: clamp(x), y: clamp(y), button });
    return true;
  }
  function sendWheel(x, y, deltaX, deltaY) {
    const c = getClient();
    if (!c) return false;
    c.handleMessage({ type: "mousemove", x: clamp(x), y: clamp(y) });
    c.handleMessage({ type: "wheel", deltaX, deltaY });
    return true;
  }
  var lastRect = "";
  function reportRect() {
    const v = getVideo();
    let msg = { x: 0, y: 0, w: 0, h: 0 };
    if (v && v.isConnected) {
      const r = v.getBoundingClientRect();
      msg = { x: r.left, y: r.top, w: r.width, h: r.height };
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
  function installVideoTracking() {
    window.setInterval(reportRect, 400);
    window.addEventListener("resize", reportRect);
    window.addEventListener("scroll", reportRect, true);
  }
  var PAGE_CSS = `
#embed, [data-hb-theater] {
  position: fixed !important; inset: 0 !important; z-index: 2147483646 !important;
  width: 100vw !important; height: 100vh !important; max-width: none !important;
  margin: 0 !important; background: #000 !important;
}
html, body { overflow: hidden !important; }
`;
  var SHADOW_CSS = `
video {
  width: auto !important; height: auto !important;
  max-width: 100% !important; max-height: 100% !important;
}
`;
  var pageStyle = null;
  var shadowStyle = null;
  function setTheater(on) {
    var _a;
    const c = getClient();
    const host = (_a = c == null ? void 0 : c.root) == null ? void 0 : _a.host;
    if (on) {
      if (host) host.setAttribute("data-hb-theater", "");
      if (!pageStyle) {
        pageStyle = document.createElement("style");
        pageStyle.textContent = PAGE_CSS;
        document.head.appendChild(pageStyle);
      }
      if ((c == null ? void 0 : c.root) && !shadowStyle) {
        shadowStyle = document.createElement("style");
        shadowStyle.textContent = SHADOW_CSS;
        c.root.appendChild(shadowStyle);
      }
    } else {
      host == null ? void 0 : host.removeAttribute("data-hb-theater");
      pageStyle == null ? void 0 : pageStyle.remove();
      shadowStyle == null ? void 0 : shadowStyle.remove();
      pageStyle = null;
      shadowStyle = null;
    }
    lastRect = "";
    requestAnimationFrame(reportRect);
  }

  // src/protocol.ts
  var BRIDGE_VERSION = 3;

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
      case "chatSend":
        send({ type: "chatSent", id: cmd.id, ok: sendChat(cmd.text) });
        break;
      case "mouse":
        sendMouse(cmd.action, cmd.x, cmd.y, cmd.button ?? 0);
        break;
      case "wheel":
        sendWheel(cmd.x, cmd.y, cmd.deltaX, cmd.deltaY);
        break;
      case "theater":
        setTheater(cmd.on);
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
    installHttpLog();
    installFocusTracking();
    if (top) {
      installLocationTracking();
      installChatReader();
      installVideoTracking();
    }
    onCommand(handle);
    send({ type: "ready", version: BRIDGE_VERSION, top, url: location.href });
    if (!hasNative) {
      return;
    }
    log("info", `bridge v${BRIDGE_VERSION} installed in ${top ? "top" : "sub"} frame`);
  }
  install();
})();
