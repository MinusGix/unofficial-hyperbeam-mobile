// Bundles the bridge into a single IIFE that the Android shell injects.
import { build } from "esbuild";

const out = process.argv[2] ?? "../app/src/main/assets/bridge.js";
await build({
  entryPoints: ["src/index.ts"],
  bundle: true,
  format: "iife",
  target: ["chrome90", "safari15"],
  minify: false,
  legalComments: "none",
  outfile: out,
  banner: { js: "/* Generated from bridge/src by bridge/build.mjs. Do not edit by hand. */" },
});
console.log(`wrote ${out}`);
