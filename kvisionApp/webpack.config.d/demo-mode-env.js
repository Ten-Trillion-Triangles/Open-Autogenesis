// Demo-mode env injection.
//
// Injects `process.env.AUTOGENESIS_DEMO_MODE` as a webpack DefinePlugin
// constant so that the Kotlin/JS code in Main.kt::resolveDemoMode() can
// read it as a build-time literal.
//
// Source: the `AUTOGENESIS_DEMO_MODE` shell env var. The build.gradle.kts
// `tasks.withType<JavaExec>().configureEach { environment(...) }` block
// (in `afterEvaluate`) AND the `runKvisionNoHotReload` Exec task in the
// root build.gradle.kts both forward the `-PkvisionApp.demoMode=<value>`
// Gradle property into this env var.
//
// Both paths converge: a single Gradle property controls the demo mode
// for both production bundles and the dev server.
//
// IMPORTANT (autogenesis-local-dev pitfall #5): webpack concatenates
// all `webpack.config.d/*.js` files into ONE module scope. Three sibling
// files already declare top-level webpack bindings:
//   - process-polyfill.js        uses `const webpack = require("webpack")`
//   - boot-widget-env.js         uses `const webpackModule = require("webpack")`
//   - THIS file                  must use a UNIQUE binding name (e.g.
//                                `webpackDemoMode`) to avoid the
//                                "Identifier 'webpackModule' has already
//                                been declared" error.

const webpackDemoMode = require("webpack");

config.plugins = [
  ...(config.plugins || []),
  new webpackDemoMode.DefinePlugin({
    "process.env.AUTOGENESIS_DEMO_MODE": JSON.stringify(
      process.env.AUTOGENESIS_DEMO_MODE || ""
    ),
  }),
];
