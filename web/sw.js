// Footage Vault service worker: makes the site installable and shows push notifications.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

self.addEventListener("push", (e) => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch { d = { body: e.data && e.data.text() }; }
  e.waitUntil(self.registration.showNotification(d.title || "Footage Vault", {
    body: d.body || "",
    tag: d.tag || undefined,
    data: { url: safeUrl(d.url) },
    icon: "/static/icon-192.png",
    badge: "/static/badge-96.png",
  }));
});

function safeUrl(raw) {
  try {
    const u = new URL(String(raw || "/"), self.location.origin);
    return u.origin === self.location.origin ? u.pathname + u.search + u.hash : "/";
  } catch {
    return "/";
  }
}

self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const url = new URL(safeUrl(e.notification.data && e.notification.data.url), self.location.origin).href;
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const w of wins) {
      if ("focus" in w) { await w.navigate(url); return w.focus(); }
    }
    return self.clients.openWindow(url);
  })());
});
