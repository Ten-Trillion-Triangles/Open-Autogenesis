const webpack = require("webpack");

config.resolve = config.resolve || {};
config.resolve.alias = {
  ...(config.resolve.alias || {}),
  "process/browser": require.resolve("process/browser.js"),
};
config.resolve.fallback = {
  ...(config.resolve.fallback || {}),
  process: require.resolve("process/browser.js"),
};

config.plugins = [
  ...(config.plugins || []),
  new webpack.ProvidePlugin({
    process: "process/browser",
  }),
];