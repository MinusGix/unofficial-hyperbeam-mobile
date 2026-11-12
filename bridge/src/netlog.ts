// A small in-memory ring buffer of WebSocket traffic.
//
// Hyperbeam's room and chat protocol is undocumented. When the user exports a
// debug snapshot, these samples show which socket carries chat and how it is
// encoded, which is what the chat-reading feature will be built on. Nothing
// here leaves the page unless the user exports a snapshot.

const MAX_FRAMES = 300;
const MAX_TEXT = 600;

export type FrameSample = {
  t: number;
  dir: "in" | "out";
  socket: number;
  kind: "text" | "binary";
  size: number;
  sample: string;
};

const frames: FrameSample[] = [];
const sockets: Array<{ id: number; url: string; opened: number }> = [];

function sampleOf(data: unknown): { kind: "text" | "binary"; size: number; sample: string } {
  if (typeof data === "string") {
    return { kind: "text", size: data.length, sample: data.slice(0, MAX_TEXT) };
  }
  let bytes: Uint8Array | null = null;
  if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
  else if (ArrayBuffer.isView(data)) bytes = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  if (bytes) {
    const head = bytes.subarray(0, 256);
    let b64 = "";
    try {
      b64 = btoa(String.fromCharCode(...head));
    } catch {
      /* ignore */
    }
    return { kind: "binary", size: bytes.byteLength, sample: b64 };
  }
  if (data instanceof Blob) return { kind: "binary", size: data.size, sample: "(blob)" };
  return { kind: "binary", size: 0, sample: "" };
}

function record(dir: "in" | "out", socket: number, data: unknown): void {
  const s = sampleOf(data);
  frames.push({ t: Date.now(), dir, socket, ...s });
  if (frames.length > MAX_FRAMES) frames.splice(0, frames.length - MAX_FRAMES);
}

export function installNetLog(): void {
  const Orig = window.WebSocket;
  if (!Orig) return;
  let nextId = 1;
  class TappedWebSocket extends Orig {
    constructor(url: string | URL, protocols?: string | string[]) {
      super(url, protocols);
      const id = nextId++;
      sockets.push({ id, url: String(url), opened: Date.now() });
      if (sockets.length > 50) sockets.shift();
      this.addEventListener("message", (e: MessageEvent) => record("in", id, e.data));
      (this as unknown as { __hbId: number }).__hbId = id;
    }
    send(data: string | ArrayBufferLike | Blob | ArrayBufferView): void {
      record("out", (this as unknown as { __hbId: number }).__hbId, data);
      super.send(data);
    }
  }
  window.WebSocket = TappedWebSocket as unknown as typeof WebSocket;
}

// HTTP traffic: the site's API (e.g. alpha.hyperbeam.com/p/...) runs over
// fetch, including what looks like a long-lived subscription. We keep the URL,
// status, type and the first few KB of each response body.

export type HttpSample = {
  t: number;
  via: "fetch" | "xhr" | "eventsource";
  method: string;
  url: string;
  status?: number;
  contentType?: string;
  sample?: string;
  bytes?: number;
  ms?: number;
};

const MAX_HTTP = 200;
const MAX_BODY = 4000;
const http: HttpSample[] = [];

function pushHttp(h: HttpSample): HttpSample {
  http.push(h);
  if (http.length > MAX_HTTP) http.splice(0, http.length - MAX_HTTP);
  return h;
}

function interesting(url: string): boolean {
  // Skip media and static assets; they're noise for figuring out the protocol.
  return !/\.(woff2?|png|jpe?g|svg|gif|webp|mp4|webm|opus|css|ico)(\?|$)/i.test(url);
}

async function sampleBody(res: Response, h: HttpSample): Promise<void> {
  const body = res.body;
  if (!body) return;
  const reader = body.getReader();
  const dec = new TextDecoder();
  let text = "";
  let bytes = 0;
  const started = Date.now();
  try {
    while (text.length < MAX_BODY && Date.now() - started < 15000) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      text += dec.decode(value, { stream: true });
      h.sample = text.slice(0, MAX_BODY);
      h.bytes = bytes;
    }
  } catch {
    /* aborted by the page */
  } finally {
    reader.cancel().catch(() => {});
  }
}

export function installHttpLog(): void {
  const origFetch = window.fetch;
  window.fetch = async function (this: unknown, ...a: Parameters<typeof fetch>) {
    const started = Date.now();
    const req = a[0];
    const url = typeof req === "string" ? req : req instanceof URL ? req.href : (req as Request).url;
    const method = (a[1]?.method ?? (req instanceof Request ? req.method : "GET")).toUpperCase();
    const res = await origFetch.apply(this, a);
    try {
      if (interesting(url)) {
        const h = pushHttp({
          t: started,
          via: "fetch",
          method,
          url: res.url || url,
          status: res.status,
          contentType: res.headers.get("content-type") ?? undefined,
          ms: Date.now() - started,
        });
        const ct = h.contentType ?? "";
        if (!/javascript|octet-stream|image|video|audio|font/.test(ct)) {
          sampleBody(res.clone(), h);
        }
      }
    } catch {
      /* never interfere */
    }
    return res;
  };

  const xo = XMLHttpRequest.prototype.open;
  const xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (this: XMLHttpRequest & { __hb?: HttpSample }, method: string, url: string | URL, ...rest: unknown[]) {
    this.__hb = { t: Date.now(), via: "xhr", method: String(method).toUpperCase(), url: String(url) };
    return (xo as (...x: unknown[]) => void).call(this, method, url, ...rest);
  } as typeof XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.send = function (this: XMLHttpRequest & { __hb?: HttpSample }, body?: Document | XMLHttpRequestBodyInit | null) {
    const h = this.__hb;
    if (h && interesting(h.url)) {
      pushHttp(h);
      this.addEventListener("loadend", () => {
        h.status = this.status;
        h.contentType = this.getResponseHeader("content-type") ?? undefined;
        h.ms = Date.now() - h.t;
        if (this.responseType === "" || this.responseType === "text") h.sample = this.responseText.slice(0, MAX_BODY);
      });
    }
    return xs.call(this, body);
  };

  const OrigES = window.EventSource;
  if (OrigES) {
    class TappedEventSource extends OrigES {
      constructor(url: string | URL, init?: EventSourceInit) {
        super(url, init);
        const h = pushHttp({ t: Date.now(), via: "eventsource", method: "GET", url: String(url), sample: "" });
        this.addEventListener("message", (e: MessageEvent) => {
          if ((h.sample ?? "").length < MAX_BODY) h.sample += `${String(e.data).slice(0, 500)}\n`;
        });
      }
    }
    window.EventSource = TappedEventSource as unknown as typeof EventSource;
  }
}

export function netLogSnapshot(): { sockets: typeof sockets; frames: FrameSample[]; http: HttpSample[] } {
  return { sockets: sockets.slice(), frames: frames.slice(), http: http.slice() };
}
