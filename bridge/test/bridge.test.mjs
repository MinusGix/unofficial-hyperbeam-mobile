// End-to-end tests for the bridge in headless Chromium, using the real
// @hyperbeam/web loader against a fake streaming-client module.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";

const PAGE = "https://test.hyperbeam.com/room";
const SESSION = "https://abcdefghijklmnopqrstuvwxy.hyperbeam.com/embed?token=t";

const FAKE_CLIENT = `
export default function (root, url, cb, opts) {
  window.__hbEvents = [];
  window.__hbCb = cb;
  const v = document.createElement("video");
  root.appendChild(v);
  setTimeout(() => cb({ type: "connection", data: { width: 1280, height: 720, user_id: "u1", max_area: 9000000 } }), 5);
  return {
    handleMessage(m) { window.__hbEvents.push(m); },
    destroy() { window.__destroyed = true; },
  };
}
`;

let browser, bridgeJs, harnessJs;

before(async () => {
  await build({ entryPoints: ["src/index.ts"], bundle: true, format: "iife", outfile: "test/.out/bridge.js", logLevel: "silent" });
  await build({ entryPoints: ["test/harness-entry.js"], bundle: true, format: "iife", outfile: "test/.out/harness.js", logLevel: "silent" });
  bridgeJs = await readFile("test/.out/bridge.js", "utf8");
  harnessJs = await readFile("test/.out/harness.js", "utf8");
  browser = await chromium.launch();
});

after(async () => {
  await browser?.close();
});

async function openPage({ withNative = true } = {}) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.route("https://test.hyperbeam.com/**", (r) =>
    r.fulfill({ contentType: "text/html", body: `<!doctype html><html><body><div id="c"></div><script>${harnessJs}</script></body></html>` }),
  );
  await page.route("https://abcdefghijklmnopqrstuvwxy.hyperbeam.com/**", (r) =>
    r.fulfill({
      contentType: "text/javascript",
      headers: {
        "access-control-allow-origin": "https://test.hyperbeam.com",
        "access-control-allow-credentials": "true",
      },
      body: FAKE_CLIENT,
    }),
  );
  if (withNative) {
    await page.addInitScript(() => {
      window.__native = [];
      window.HbNative = {
        onmessage: null,
        postMessage(s) { window.__native.push(JSON.parse(s)); },
      };
      window.__cmd = (c) => window.HbNative.onmessage({ data: JSON.stringify(c) });
    });
  }
  await page.addInitScript(bridgeJs);
  await page.goto(PAGE);
  return { context, page };
}

async function connect(page) {
  await page.evaluate(async (url) => {
    window.hb = await window.Hyperbeam(document.getElementById("c"), url);
  }, SESSION);
}

const keys = (events) => events.filter((e) => e.type === "keydown" || e.type === "keyup").map((e) => `${e.type === "keydown" ? "+" : "-"}${e.key}`);

test("announces itself to native", async () => {
  const { context, page } = await openPage();
  const native = await page.evaluate(() => window.__native);
  assert.equal(native[0].type, "ready");
  assert.equal(native[0].top, true);
  await context.close();
});

test("hooks the real SDK's streaming client and reports connection", async () => {
  const { context, page } = await openPage();
  await connect(page);
  const native = await page.evaluate(() => window.__native);
  assert.ok(native.some((m) => m.type === "client" && m.state === "hooked"));
  const conn = native.find((m) => m.type === "client" && m.state === "connected");
  assert.equal(conn.width, 1280);
  assert.equal(conn.userId, "u1");
  // The page's own SDK handle still works and goes to the same client.
  assert.equal(await page.evaluate(() => window.hb.userId), "u1");
  await page.evaluate(() => window.hb.sendEvent({ type: "keydown", key: "x" }));
  assert.deepEqual(keys(await page.evaluate(() => window.__hbEvents)), ["+x"]);
  await context.close();
});

test("types text through the client, by code point, with Enter for newlines", async () => {
  const { context, page } = await openPage();
  await connect(page);
  await page.evaluate(() => window.__cmd({ type: "text", text: "Hi\r\n😀" }));
  assert.deepEqual(keys(await page.evaluate(() => window.__hbEvents)), ["+H", "-H", "+i", "-i", "+Enter", "-Enter", "+😀", "-😀"]);
  const native = await page.evaluate(() => window.__native);
  assert.ok(native.some((m) => m.type === "inputPath" && m.path === "client"));
  await context.close();
});

test("sends modifier shortcuts, backspaces and split key phases", async () => {
  const { context, page } = await openPage();
  await connect(page);
  await page.evaluate(() => {
    window.__cmd({ type: "key", key: "a", ctrl: true });
    window.__cmd({ type: "backspace", count: 2 });
    window.__cmd({ type: "key", key: "Shift", phase: "down" });
    window.__cmd({ type: "key", key: "Shift", phase: "up" });
  });
  const ev = await page.evaluate(() => window.__hbEvents);
  assert.deepEqual(keys(ev), ["+a", "-a", "+Backspace", "-Backspace", "+Backspace", "-Backspace", "+Shift", "-Shift"]);
  assert.equal(ev[0].ctrlKey, true);
  assert.equal(ev[2].ctrlKey, false);
  await context.close();
});

test("forwards video pause, volume and ping to the client", async () => {
  const { context, page } = await openPage();
  await connect(page);
  await page.evaluate(() => {
    window.__cmd({ type: "videoPaused", on: true });
    window.__cmd({ type: "volume", value: 0.5 });
    window.__cmd({ type: "ping" });
  });
  const ev = await page.evaluate(() => window.__hbEvents);
  assert.deepEqual(ev, [{ type: "video_paused", data: true }, { type: "volume", data: 0.5 }, { type: "ping" }]);
  await context.close();
});

test("reports connection state, resize and destroy", async () => {
  const { context, page } = await openPage();
  await connect(page);
  await page.evaluate(() => {
    window.__hbCb({ type: "connection_state", data: { state: "reconnecting" } });
    window.__hbCb({ type: "resize", data: { width: 800, height: 600 } });
    window.hb.destroy();
  });
  const native = await page.evaluate(() => window.__native);
  assert.ok(native.some((m) => m.type === "connection" && m.state === "reconnecting"));
  assert.ok(native.some((m) => m.type === "resize" && m.width === 800));
  assert.ok(native.some((m) => m.type === "client" && m.state === "destroyed"));
  assert.equal(await page.evaluate(() => window.__destroyed), true);
  await context.close();
});

test("falls back to synthetic key events when no client is hooked", async () => {
  const { context, page } = await openPage();
  await page.evaluate(() => {
    window.__seen = [];
    window.addEventListener("keydown", (e) => window.__seen.push(e.key));
    window.__cmd({ type: "text", text: "ok" });
  });
  assert.deepEqual(await page.evaluate(() => window.__seen), ["o", "k"]);
  const native = await page.evaluate(() => window.__native);
  assert.ok(native.some((m) => m.type === "inputPath" && m.path === "synthetic"));
  await context.close();
});

test("pins visibility and swallows visibilitychange until unpinned", async () => {
  const { context, page } = await openPage();
  const r = await page.evaluate(() => {
    let fired = 0;
    document.addEventListener("visibilitychange", () => fired++);
    document.dispatchEvent(new Event("visibilitychange"));
    const whilePinned = fired;
    window.__cmd({ type: "pinVisibility", on: false });
    document.dispatchEvent(new Event("visibilitychange"));
    return { whilePinned, after: fired, state: document.visibilityState, hidden: document.hidden };
  });
  assert.deepEqual(r, { whilePinned: 0, after: 1, state: "visible", hidden: false });
  await context.close();
});

test("requestAnimationFrame still works and can be cancelled", async () => {
  const { context, page } = await openPage();
  const r = await page.evaluate(
    () =>
      new Promise((resolve) => {
        let cancelledRan = false;
        const id = requestAnimationFrame(() => (cancelledRan = true));
        cancelAnimationFrame(id);
        requestAnimationFrame((t) => setTimeout(() => resolve({ cancelledRan, gotTime: typeof t === "number" }), 50));
      }),
  );
  assert.deepEqual(r, { cancelledRan: false, gotTime: true });
  await context.close();
});

test("snapshot captures page structure and the client", async () => {
  const { context, page } = await openPage();
  await connect(page);
  await page.evaluate(() => window.__cmd({ type: "snapshot", id: 7 }));
  const snap = (await page.evaluate(() => window.__native)).find((m) => m.type === "snapshot");
  assert.equal(snap.id, 7);
  assert.equal(snap.data.url, PAGE);
  assert.equal(snap.data.client.width, 1280);
  assert.ok(snap.data.html.includes('id="c"'));
  assert.equal(snap.data.shadowRoots.length, 1);
  await context.close();
});

test("location changes in a single-page app are reported", async () => {
  const { context, page } = await openPage();
  await page.evaluate(() => history.pushState({}, "", "/room/abc"));
  await page.waitForFunction(() => window.__native.some((m) => m.type === "location" && m.url.endsWith("/room/abc")));
  await context.close();
});

test("does nothing harmful without a native host", async () => {
  const { context, page } = await openPage({ withNative: false });
  await connect(page);
  assert.equal(await page.evaluate(() => window.hb.width), 1280);
  await context.close();
});

test("reports when the page's own text fields take focus", async () => {
  const { context, page } = await openPage();
  await page.evaluate(() => {
    const i = document.createElement("input");
    i.id = "chat";
    document.body.appendChild(i);
    i.focus();
  });
  await page.evaluate(() => document.getElementById("chat").blur());
  await page.waitForFunction(() => window.__native.filter((m) => m.type === "pageFocus").length >= 2);
  const focus = (await page.evaluate(() => window.__native)).filter((m) => m.type === "pageFocus");
  assert.deepEqual(focus.slice(0, 2), [
    { type: "pageFocus", editable: true, inClient: false },
    { type: "pageFocus", editable: false, inClient: false },
  ]);
  await context.close();
});
