import path from "node:path"
import { fileURLToPath } from "node:url"

import { parse as parseYaml } from "yaml"
import { defineConfig, type Plugin } from "vitest/config"

const __dirname = path.dirname(fileURLToPath(import.meta.url))

/**
 * What `next.config.ts`'s turbopack `*.yaml -> yaml-loader` rule does, for
 * the test runner, which has no such rule and fails the import outright
 * ("content contains invalid JS syntax").
 *
 * It only started mattering when the OCR/review components began calling
 * `getTooltip`, which imports `config/tooltip-content.yaml`: before that,
 * nothing under test pulled the YAML in, so the gap between the two
 * toolchains was invisible.
 *
 * Parsing the real file rather than stubbing the module is deliberate --
 * `getTooltip` THROWS on an unknown key, so a test that renders a
 * component with a typo'd tooltip key should fail, and it cannot if the
 * lookup is mocked away.
 *
 * `yaml` is not declared in package.json: it arrives with `yaml-loader`,
 * which is. Adding it explicitly would be better, but `npm i -D yaml`
 * currently fails on a PRE-EXISTING ERESOLVE conflict between vitest 5,
 * vite 8 and @types/node 20 that has nothing to do with this, and forcing
 * it would rewrite the lockfile for an unrelated reason.
 */
function yamlPlugin(): Plugin {
  return {
    name: "yaml-as-json",
    transform(code, id) {
      if (!id.endsWith(".yaml") && !id.endsWith(".yml")) return null
      return { code: `export default ${JSON.stringify(parseYaml(code))}`, map: null }
    },
  }
}

// No @vitejs/plugin-react here on purpose: it currently conflicts with an
// already-installed @babel/core@8 peer elsewhere in this project's tree
// (see the npm install output when this was added), and it isn't actually
// needed for plain component tests -- Vite's default esbuild transform
// already handles .tsx via this project's own tsconfig.json `jsx:
// "react-jsx"` setting, it just skips Fast Refresh (irrelevant outside a
// dev server).
export default defineConfig({
  plugins: [yamlPlugin()],
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
