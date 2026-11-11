import type { BridgeEvent, NativeCommand } from "./protocol";

type NativePort = {
  postMessage(msg: string): void;
  onmessage: ((ev: { data: string }) => void) | null;
};

const port = (globalThis as unknown as { HbNative?: NativePort }).HbNative;

export const hasNative = !!port;

export function send(ev: BridgeEvent): void {
  if (!port) return;
  try {
    port.postMessage(JSON.stringify(ev));
  } catch {
    // The native side may be gone (e.g. WebView tearing down); nothing to do.
  }
}

export function log(level: "info" | "warn" | "error", msg: string): void {
  send({ type: "log", level, msg });
}

export function onCommand(handler: (cmd: NativeCommand) => void): void {
  if (!port) return;
  port.onmessage = (ev) => {
    let cmd: NativeCommand;
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
