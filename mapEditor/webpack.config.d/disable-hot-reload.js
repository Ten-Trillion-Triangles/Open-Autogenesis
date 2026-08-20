// disable hot reload when explicitly requested
if (process.env.KVISION_DISABLE_HOT_RELOAD === "true") {
  config.devServer = config.devServer || {}
  config.devServer.hot = false
  config.devServer.liveReload = false
}