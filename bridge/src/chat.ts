// Read the room's chat from the page and send messages into it.
//
// The Watch Party site (canary, 2026-11) renders chat as a list of rows:
//
//   div.group.relative.my-2              one message
//     span.font-medium                   author (first row of a run only)
//     div.line-clamp-1.text-xs           time, e.g. "01:39 PM"
//     div.absolute.left-4.text-xs        time on continuation rows (no author)
//     div.whitespace-pre-wrap.break-words  text
//     video/img[src*=tenor]              GIF
//
// and the composer as <form><textarea id="message">. There is no stable id
// per message, so new messages are found by diffing the rendered list. If the
// layout changes, nothing is reported (we never guess), and the snapshot shows
// what changed.

import { send } from "./channel";

export type ChatMessage = {
  key: string;
  author: string;
  text: string;
  time: string;
};

const ROW = "div.group.relative.my-2";
const BODY = ".whitespace-pre-wrap.break-words";
const MAX_INITIAL = 8;
const MAX_REMEMBERED = 400;

const seen = new Set<string>();
const seenOrder: string[] = [];
let initialSent = false;

function text(el: Element | null | undefined): string {
  return (el?.textContent ?? "").replace(/\s+/g, " ").trim();
}

function mediaLabel(row: Element): string {
  const media = row.querySelectorAll("video[src], img[src]");
  for (const m of Array.from(media)) {
    if (m.classList.contains("rounded-full")) continue; // avatar
    const src = m.getAttribute("src") ?? "";
    if (/tenor\.com|giphy\.com|\.gif(\?|$)/i.test(src)) return "GIF";
    return m.tagName === "VIDEO" ? "Video" : "Image";
  }
  return "";
}

/** Parse every rendered chat row, oldest first. */
export function readChat(root: ParentNode = document): ChatMessage[] {
  const rows = Array.from(root.querySelectorAll(ROW)).filter((r) => r.querySelector(BODY) || mediaLabel(r));
  const out: ChatMessage[] = [];
  const counts = new Map<string, number>();
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
    const base = `${author}\u0000${time}\u0000${body}`;
    const n = (counts.get(base) ?? 0) + 1;
    counts.set(base, n);
    out.push({ key: `${base}\u0000${n}`, author, text: body, time });
  }
  return out;
}

function remember(key: string): void {
  seen.add(key);
  seenOrder.push(key);
  if (seenOrder.length > MAX_REMEMBERED) seen.delete(seenOrder.shift()!);
}

/** Report messages we haven't reported before. */
export function scanChat(): void {
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

function strip(m: ChatMessage) {
  return { author: m.author, text: m.text, time: m.time };
}

export function installChatReader(): void {
  let timer: number | undefined;
  const schedule = () => {
    if (timer !== undefined) return;
    timer = window.setTimeout(() => {
      timer = undefined;
      try {
        scanChat();
      } catch {
        /* never break the page */
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

/** Put [message] in the site's chat box and submit it. */
export function sendChat(message: string): boolean {
  const box = document.querySelector<HTMLTextAreaElement>("textarea#message, textarea[name=message]");
  if (!box) return false;
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set;
  box.focus();
  setter ? setter.call(box, message) : (box.value = message);
  box.dispatchEvent(new InputEvent("input", { bubbles: true, data: message, inputType: "insertText" }));
  const enter = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
  box.dispatchEvent(new KeyboardEvent("keydown", enter));
  box.dispatchEvent(new KeyboardEvent("keypress", enter));
  box.dispatchEvent(new KeyboardEvent("keyup", enter));
  // If Enter didn't send it, submit the form the way the send button would.
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
