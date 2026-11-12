// A trimmed copy of the Watch Party chat markup (canary, 2026-11), with
// made-up names and messages. Only the structure matters to the reader.
const ROW = 'class="group relative my-2 flex gap-4 px-4 hover:bg-black hover:bg-opacity-10"';
const head = (name, time) =>
  `<div class="-mb-2 flex flex-wrap items-baseline"><div class="peer mr-2 flex"><span data-menu-button-state="closed" class="font-medium hover:underline cursor-pointer">${name}</span></div><div class="line-clamp-1 flex-none text-xs text-black/50">${time}</div></div>`;
const avatar = `<div data-menu-button-state="closed"><img alt="" src="https://cdnstag.hyperbeam.com/user_avatar/x/64" class="inline rounded-full object-cover size-10"></div>`;

export const first = (name, time, body) =>
  `<div ${ROW}>${avatar}<div class="flex min-w-0 flex-1 flex-col gap-2">${head(name, time)}<div class="whitespace-pre-wrap break-words">${body}</div></div><button class="invisible absolute"></button></div>`;

export const gif = (name, time) =>
  `<div ${ROW}>${avatar}<div class="flex min-w-0 flex-1 flex-col gap-2">${head(name, time)}<div class="whitespace-pre-wrap break-words"></div><video autoplay loop muted class="w-full max-w-sm" src="https://media.tenor.com/abc/cat.mp4"></video></div></div>`;

export const cont = (time, body) =>
  `<div class="group relative my-2 flex gap-4 px-4 mt-0 pl-[4.5rem]"><div class="absolute left-4 top-1/2 hidden -translate-y-1/2 text-center text-xs">${time}</div><div class="flex min-w-0 flex-1 flex-col gap-2"><div class="whitespace-pre-wrap break-words">${body}</div></div></div>`;

export const page = (rows) => `
<div id="root"><div class="container mx-auto flex size-full flex-col">
  <div><p class="text-center">Only admins, moderators, and the owner can start the browser.</p></div>
  <div class="min-h-0 flex-1"><div id="list" class="flex h-full flex-col overflow-auto"><div class="flex-1"></div>${rows}</div></div>
  <div><form id="composer" class="flex items-center gap-2 px-4 py-2"><textarea id="message" name="message" placeholder="Type a message"></textarea><button type="button"></button></form></div>
</div></div>`;
