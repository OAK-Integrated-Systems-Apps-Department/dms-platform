import path from "node:path"
import { fileURLToPath } from "node:url"

import { defineConfig } from "vitest/config"

const __dirname = path.dirname(fileURLToPath(import.meta.url))

// No @vitejs/plugin-react here on purpose: it currently conflicts with an
// already-installed @babel/core@8 peer elsewhere in this project's tree
// (see the npm install output when this was added), and it isn't actually
// needed for plain component tests -- Vite's default esbuild transform
// already handles .tsx via this project's own tsconfig.json `jsx:
// "react-jsx"` setting, it just skips Fast Refresh (irrelevant outside a
// dev server).
export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "."),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    css: false,
    // Needed so @testing-library/react's own afterEach(cleanup) registration
    // (it looks for a global `afterEach`) actually runs -- without it, the
    // DOM from one test leaks into the next, which is exactly the "multiple
    // elements found" failure this surfaced with.
    globals: true,
  },
})
