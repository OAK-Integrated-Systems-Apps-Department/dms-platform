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
}

export default nextConfig