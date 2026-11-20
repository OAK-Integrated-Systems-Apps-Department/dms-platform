import type { NextConfig } from "next"

import { securityHeaderRules } from "./config/security-headers"

const nextConfig: NextConfig = {

  output: "standalone",

  turbopack: {
    rules: {
      "*.yaml": {
        loaders: ["yaml-loader"],
        as: "*.js",
      },
    },
  },

  // Mirrors the turbopack rule above for `next dev --webpack` / `next build`
  // without turbopack - whichever bundler is active needs its own loader
  // wired up; turbopack.rules has no effect under webpack.
  webpack: (config) => {
    config.module.rules.push({
      test: /\.ya?ml$/,
      use: "yaml-loader",
    })
    return config
  },

  experimental: {
    turbopackFileSystemCacheForBuild: false,
  },

  // FE-H1: security headers for what this app serves (see config/security-headers.ts for what each one is for and
  // why HSTS and an enforced CSP are not here yet).
  async headers() {
    return securityHeaderRules()
  },
}

export default nextConfig