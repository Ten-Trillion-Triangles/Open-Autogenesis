// Boot-widget env injection.
//
// Injects `process.env.AUTOGENESIS_BOOT_WIDGET` as a webpack DefinePlugin
// constant so that the Kotlin/JS code in Main.kt::resolveBootWidget() can
// read it as a build-time literal.
//
// Source: the `AUTOGENESIS_BOOT_WIDGET` shell env var. The build.gradle.kts
// `tasks.withType<JavaExec>().configureEach { environment(...) }` block
// forwards the `-PkvisionApp.bootWidget=<value>` Gradle property into this
// env var, so:
//
//   ./gradlew runKvisionNoHotReload -PkvisionApp.bootWidget=MapViewer
//
// sets AUTOGENESIS_BOOT_WIDGET=MapViewer, which DefinePlugin substitutes at
// compile time. In the dev-server case where DefinePlugin may not substitute
// (HMR), the existing process-polyfill.js webpack.config.d file falls back
// to `process/browser.js` which reads the same Gradle property at runtime
// via the JVM environment.
//
// Both paths converge: a single Gradle property controls the boot widget
// for both production bundles and the dev server.
//
// IMPORTANT (autogenesis-local-dev pitfall #5): webpack concatenates all
// `webpack.config.d/*.js` files into ONE module scope. The existing
// `process-polyfill.js` already declares `const webpack = require("webpack")`
// at the top level — we CANNOT declare `webpack` again as a top-level
// binding (will throw "Identifier 'webpack' has already been declared").
// And we CANNOT reference the existing `webpack` via `typeof webpack`
// because at module-eval time, the concatenated bundle's lexical declarations
// are hoisted but not initialized, so `typeof webpack` throws TDZ
// ReferenceError.
//
// Solution: use a unique binding name (`webpackModule`) with the `require`
// cache. Node's `require` cache returns the same `webpack` module instance
// to both files — they're literally the same object — so this is functionally
// equivalent to referencing the existing binding, without the lexical clash.

const webpackModule = require("webpack");

config.plugins = [
  ...(config.plugins || []),
  new webpackModule.DefinePlugin({
    "process.env.AUTOGENESIS_BOOT_WIDGET": JSON.stringify(
      process.env.AUTOGENESIS_BOOT_WIDGET || ""
    ),
  }),
];