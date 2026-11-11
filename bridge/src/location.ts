import { send } from "./channel";

let last = "";

function report(): void {
  const url = location.href;
  if (url === last) return;
  last = url;
  send({ type: "location", url, title: document.title });
}

/** Report SPA navigations (pushState/replaceState/popstate) to native. */
export function installLocationTracking(): void {
  for (const name of ["pushState", "replaceState"] as const) {
    const orig = history[name];
    history[name] = function (this: History, ...a: Parameters<History["pushState"]>) {
      const r = orig.apply(this, a);
      queueMicrotask(report);
      return r;
    };
  }
  window.addEventListener("popstate", report);
  window.addEventListener("hashchange", report);
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", report, { once: true });
  } else {
    report();
  }
}
