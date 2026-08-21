import type { NextConfig } from "next"

const nextConfig: NextConfig = {
  // Enables .next/standalone output: a minimal, self-contained server
  // bundle (only the traced dependencies it actually needs) that Docker
  // can copy in without shipping the full node_modules tree.
  output: "standalone",
}

export default nextConfig
