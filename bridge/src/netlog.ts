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

export function netLogSnapshot(): { sockets: typeof sockets; frames: FrameSample[] } {
  return { sockets: sockets.slice(), frames: frames.slice() };
}
