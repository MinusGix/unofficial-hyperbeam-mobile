// Entry point of the bridge, injected at document start into hyperbeam.com
// pages by the native shell.

import { hasNative, log, onCommand, send } from "./channel";
import { installChatReader, sendChat } from "./chat";
import { installClientHook } from "./client-hook";
import { installFocusTracking } from "./focus";
import { sendBackspace, sendClientMessage, sendKey, sendText } from "./input";
import { installLocationTracking } from "./location";
import { installHttpLog, installNetLog } from "./netlog";
import { BRIDGE_VERSION, type NativeCommand } from "./protocol";
import { takeSnapshot } from "./snapshot";
import { installVisibilityPin, setPinned } from "./visibility";

declare global {
  interface Window {
    __hbBridgeInstalled?: number;
  }
}

function handle(cmd: NativeCommand): void {
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
    case "snapshot":
      send({ type: "snapshot", id: cmd.id, data: takeSnapshot() });
      break;
  }
}

function install(): void {
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
  }
  onCommand(handle);
  send({ type: "ready", version: BRIDGE_VERSION, top, url: location.href });
  if (!hasNative) {
    // Loaded outside the app (e.g. tests); stay quiet.
    return;
  }
  log("info", `bridge v${BRIDGE_VERSION} installed in ${top ? "top" : "sub"} frame`);
}

install();
