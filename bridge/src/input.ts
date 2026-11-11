// Deliver keyboard input to the remote browser.
//
// Preferred path: the hooked streaming client's handleMessage, which is what
// the official SDK's sendEvent() calls. Fallback: synthetic KeyboardEvents on
// the page, which the site's own keyboard delegation may or may not accept.

import { send } from "./channel";
import { getClient } from "./client-hook";
import type { Modifiers } from "./protocol";

let lastPath: "client" | "synthetic" | null = null;

function reportPath(p: "client" | "synthetic"): void {
  if (p !== lastPath) {
    lastPath = p;
    send({ type: "inputPath", path: p });
  }
}

function emit(type: "keydown" | "keyup", key: string, mods: Modifiers): void {
  const client = getClient();
  if (client) {
    reportPath("client");
    client.handleMessage({
      type,
      key,
      ctrlKey: !!mods.ctrl,
      metaKey: !!mods.meta,
      altKey: !!mods.alt,
      shiftKey: !!mods.shift,
    });
    return;
  }
  reportPath("synthetic");
  const target: EventTarget = document.activeElement ?? window;
  target.dispatchEvent(
    new KeyboardEvent(type, {
      key,
      ctrlKey: !!mods.ctrl,
      metaKey: !!mods.meta,
      altKey: !!mods.alt,
      shiftKey: !!mods.shift,
      bubbles: true,
      cancelable: true,
      composed: true,
    }),
  );
}

export function sendKey(key: string, mods: Modifiers = {}, phase: "down" | "up" | "press" = "press"): void {
  if (phase !== "up") emit("keydown", key, mods);
  if (phase !== "down") emit("keyup", key, mods);
}

/** Map one character of typed text to a DOM `key` value. */
export function keyForChar(ch: string): string {
  switch (ch) {
    case "\n":
    case "\r":
      return "Enter";
    case "\t":
      return "Tab";
    case "\b":
      return "Backspace";
    default:
      return ch;
  }
}

export function sendText(text: string): void {
  // Iterate by code point so emoji and astral characters stay whole.
  let prevCR = false;
  for (const ch of text) {
    if (ch === "\n" && prevCR) {
      prevCR = false;
      continue; // treat \r\n as a single Enter
    }
    prevCR = ch === "\r";
    sendKey(keyForChar(ch));
  }
}

export function sendBackspace(count: number): void {
  const n = Math.max(0, Math.min(count | 0, 10000));
  for (let i = 0; i < n; i++) sendKey("Backspace");
}

export function sendClientMessage(m: { type: string; data?: unknown }): boolean {
  const client = getClient();
  if (!client) return false;
  client.handleMessage(m);
  return true;
}
