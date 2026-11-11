// Tell native when the page's own text fields (chat box, room name, search)
// gain or lose focus, so it can hand the keyboard back to the page instead of
// sending keys to the shared browser.

import { send } from "./channel";

function isEditable(el: Element | null): boolean {
  if (!el) return false;
  if (el instanceof HTMLTextAreaElement) return !el.readOnly && !el.disabled;
  if (el instanceof HTMLInputElement) {
    const nonText = ["button", "checkbox", "color", "file", "hidden", "image", "radio", "range", "reset", "submit"];
    return !nonText.includes(el.type) && !el.readOnly && !el.disabled;
  }
  return (el as HTMLElement).isContentEditable === true;
}

/** Elements inside a shadow root belong to the Hyperbeam client, not the page UI. */
function inShadow(el: Element): boolean {
  return el.getRootNode() instanceof ShadowRoot;
}

let last: string | null = null;

function report(el: Element | null): void {
  const editable = isEditable(el);
  const inClient = !!el && inShadow(el);
  const key = `${editable}:${inClient}`;
  if (key === last) return;
  last = key;
  send({ type: "pageFocus", editable, inClient });
}

export function installFocusTracking(): void {
  document.addEventListener(
    "focusin",
    (e) => report((e.composedPath()[0] as Element) ?? (e.target as Element)),
    true,
  );
  document.addEventListener(
    "focusout",
    () => queueMicrotask(() => report(deepActiveElement())),
    true,
  );
}

function deepActiveElement(): Element | null {
  let el: Element | null = document.activeElement;
  while (el && el.shadowRoot && el.shadowRoot.activeElement) el = el.shadowRoot.activeElement;
  return el;
}
