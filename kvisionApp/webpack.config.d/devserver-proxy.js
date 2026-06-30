// Proxy configuration for local development
// Routes API calls to the game server (9080) and server-extend (7070)
// Also routes /debug to the Python controller's debug signal server (7075)
config.devServer = config.devServer || {};
config.devServer.proxy = [
  {
    context: ['/player', '/events', '/rpc'],
    target: 'http://127.0.0.1:7070',
    changeOrigin: true,
    logLevel: 'warn'
  },
  {
    context: ['/ws'],
    target: 'ws://127.0.0.1:9080',
    changeOrigin: true,
    ws: true,
    logLevel: 'warn'
  },
  {
    context: ['/debug'],
    target: 'http://127.0.0.1:7075',
    changeOrigin: true,
    logLevel: 'warn'
  }
];
