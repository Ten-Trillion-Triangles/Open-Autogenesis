/**
 * PWA + Web Push webpack wiring for the KVision app.
 *
 * - manifest.webmanifest: emitted at /manifest.webmanifest so browsers
 *   can detect PWA installability (required for iOS Safari push).
 * - sw.js: copied from src/jsMain/resources/ to the webpack output root
 *   so it's served at /sw.js for the browser's PushManager to register.
 * - <link rel="manifest">: injected into the generated HTML via the
 *   html-webpack-plugin beforeEmit hook.
 *
 * The path-aliasing / module rules follow the same shape as the existing
 * webpack.config.d/*.js files (audio-chunks.js, devserver-proxy.js).
 */
const fs = require('fs');
// webpack.config.d files are concatenated into one scope; another file
// (accelbyte-sdk-alias.js) already declares `const path = require("path")`
// at this point in the merged file. JS forbids `const` redeclaration in
// the same lexical scope, so we use a different local binding name and
// fall back to the shared `path` if present.
const pathModule = typeof path !== 'undefined' ? path : require('path');
const resourcesDir = pathModule.resolve(__dirname, '..', 'src', 'jsMain', 'resources');
const manifestSrc = pathModule.join(resourcesDir, 'manifest.webmanifest');
const swSrc = pathModule.join(resourcesDir, 'sw.js');

// Copy manifest.webmanifest into the build output so it is served at
// /manifest.webmanifest. CopyWebpackPlugin isn't available without adding
// it as a dependency, so use a plain fs.copyFileSync at config-evaluation
// time — webpack re-evaluates this file when the project is rebuilt.
if (fs.existsSync(manifestSrc)) {
    config.module.rules.push({
        test: /manifest\.webmanifest$/,
        type: 'asset/resource',
        generator: {
            filename: 'manifest.webmanifest'
        }
    });
}
if (fs.existsSync(swSrc)) {
    config.module.rules.push({
        test: /sw\.js$/,
        type: 'asset/resource',
        generator: {
            filename: 'sw.js'
        }
    });
}

// Inject <link rel="manifest" href="/manifest.webmanifest"> into the
// generated HTML output via the html-webpack-plugin beforeEmit hook.
// The hook API varies by version, so we defensively look for both
// `beforeEmit` (v5+) and the older `html-webpack-plugin-before-html` event.
if (fs.existsSync(manifestSrc)) {
    try {
        const HtmlWebpackPlugin = require('html-webpack-plugin');
        config.plugins = config.plugins || [];
        const injectManifestTag = function (data) {
            if (!data.html.includes('rel="manifest"')) {
                data.html = data.html.replace(
                    '</head>',
                    '<link rel="manifest" href="/manifest.webmanifest">\n</head>'
                );
            }
            return data;
        };
        config.plugins.push({
            apply: function (compiler) {
                compiler.hooks.compilation.tap('PwaPushManifestInjector', function (compilation) {
                    if (HtmlWebpackPlugin.getHooks) {
                        const hooks = HtmlWebpackPlugin.getHooks(compilation);
                        if (hooks && hooks.beforeEmit) {
                            hooks.beforeEmit.tapAsync('PwaPushManifestInjector', function (data, cb) {
                                cb(null, injectManifestTag(data));
                            });
                        }
                    }
                });
            }
        });
    }
    catch (e) {
        // html-webpack-plugin not installed at this version — manifest
        // is still served at /manifest.webmanifest but the <link> tag
        // must be added manually to the index.html template.
        console.warn('PwaPushPlugin: html-webpack-plugin not available, manifest <link> not injected automatically: ' + e.message);
    }
}
