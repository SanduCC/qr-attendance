// Registers the service worker that caches static assets (CSS/JS/icons) only.
(function () {
  'use strict';
  if (!('serviceWorker' in navigator)) return;
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(function () { /* ignore */ });
  });
})();
