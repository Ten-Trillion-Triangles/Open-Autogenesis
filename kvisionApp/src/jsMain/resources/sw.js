// Autogenesis service worker — handles Web Push events.
//
// Served from /sw.js by the kvisionApp webpack output (the
// webpack.config.d/service-worker.js plugin copies it). Registered on app
// boot by PushNotificationService.registerServiceWorker().

self.addEventListener('push', function (event) {
    var payload = {};
    if (event.data) {
        try {
            payload = event.data.json();
        } catch (e) {
            payload = { title: 'Autogenesis', body: event.data.text() };
        }
    }
    var title = payload.title || 'Your turn';
    var body = payload.body || 'Round ' + (payload.round || '') + ' — ready when you are';
    var tag = payload.tag || 'autogenesis-turn';
    var url = payload.url || '/';
    event.waitUntil(
        self.registration.showNotification(title, {
            body: body,
            icon: '/img/AutogenesisTitle.png',
            badge: '/img/AutogenesisTitle.png',
            tag: tag,
            data: { url: url },
            requireInteraction: true,
            actions: [
                { action: 'resume', title: 'Resume turn' }
            ]
        })
    );
});

self.addEventListener('notificationclick', function (event) {
    event.notification.close();
    var targetUrl = (event.notification.data && event.notification.data.url) || '/';
    event.waitUntil(
        self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (clientList) {
            // Try to focus an existing game tab
            for (var i = 0; i < clientList.length; i++) {
                var client = clientList[i];
                if (client.url.indexOf(self.registration.scope) === 0 && 'focus' in client) {
                    return client.focus().then(function () {
                        // Tell the focused tab to resume the turn via the existing
                        // WS bridge. The tab-side handler invokes
                        // server.restoreRunningGame on the WebSocketRpcBridge,
                        // which is the same rehydration path the Resume dialog uses.
                        return client.postMessage({ type: 'autogenesis.resumeTurn' });
                    });
                }
            }
            // No existing tab — open a new one to the root URL. The user
            // can re-navigate into the game from there.
            if (self.clients.openWindow) {
                return self.clients.openWindow(targetUrl);
            }
        })
    );
});

// Re-subscribe when the browser rotates the subscription (the standard
// lifecycle event per RFC 8030). Forward the new subscription to every
// open tab so the page-side handler can re-register it with the server.
self.addEventListener('pushsubscriptionchange', function (event) {
    event.waitUntil(
        self.registration.pushManager.subscribe({ userVisibleOnly: true }).then(function (subscription) {
            return self.clients.matchAll({ includeUncontrolled: true }).then(function (clients) {
                clients.forEach(function (client) {
                    client.postMessage({
                        type: 'autogenesis.subscriptionChanged',
                        subscription: subscription.toJSON()
                    });
                });
            });
        })
    );
});