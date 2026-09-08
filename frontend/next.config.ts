import type { NextConfig } from "next"

const nextConfig: NextConfig = {
  // Enables .next/standalone output: a minimal, self-contained server
  // bundle (only the traced dependencies it actually needs) that Docker
  // can copy in without shipping the full node_modules tree.
  output: "standalone",

  // Lets any file import *.yaml directly (see lib/tooltips.ts) instead of
  // running a separate codegen step to produce a typed .ts file.
  // yaml-loader's default output is real ES module source ending in
  // `export default {...}` (not JSON text) — so this must map to *.js,
  // not *.json, or Turbopack tries to JSON-parse JS and silently drops
  // every export.
  turbopack: {
    rules: {
      "*.yaml": {
        loaders: ["yaml-loader"],
        as: "*.js",
      },
    },
  },
}

export default nextConfig