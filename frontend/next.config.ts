import type { NextConfig } from "next"

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

  experimental: {
    turbopackFileSystemCacheForBuild: false,
  },
}

export default nextConfig