// Message protocol between the native shell (Android now, iOS later) and the
// bridge script running inside the Hyperbeam page. Both directions are JSON
// strings carried over a WebMessage channel named `HbNative`.

export const BRIDGE_VERSION = 2;

export type Modifiers = {
  ctrl?: boolean;
  alt?: boolean;
  shift?: boolean;
  meta?: boolean;
};

/** Native -> page */
export type NativeCommand =
  | ({ type: "key"; key: string; phase?: "down" | "up" | "press" } & Modifiers)
  | { type: "text"; text: string }
  | { type: "backspace"; count: number }
  | { type: "pinVisibility"; on: boolean }
  | { type: "videoPaused"; on: boolean }
  | { type: "volume"; value: number }
  | { type: "ping" }
  | { type: "chatSend"; id: number; text: string }
  | { type: "snapshot"; id: number };

export type ClientInfo = {
  width?: number;
  height?: number;
  userId?: string;
};

/** Page -> native */
export type BridgeEvent =
  | { type: "ready"; version: number; top: boolean; url: string }
  | ({ type: "client"; state: "hooked" | "connected" | "destroyed" } & ClientInfo)
  | { type: "connection"; state: string }
  | { type: "disconnect"; reason: string }
  | { type: "resize"; width: number; height: number }
  | { type: "location"; url: string; title: string }
  | { type: "inputPath"; path: "client" | "synthetic" }
  | { type: "pageFocus"; editable: boolean; inClient: boolean }
  | { type: "snapshot"; id: number; data: unknown }
  | { type: "chat"; initial: boolean; messages: Array<{ author: string; text: string; time: string }> }
  | { type: "chatSent"; id: number; ok: boolean }
  | { type: "log"; level: "info" | "warn" | "error"; msg: string };
