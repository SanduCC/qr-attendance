// Confirm page of a session with a location check (anti-cheat rule 6): on "Check in", ask the browser for the
// phone's position, put it in the hidden fields and submit. The server compares it with the classroom and forgets
// it. Only loaded on such sessions. No inline scripts (CSP).
(function () {
  'use strict';

  const form = document.getElementById('checkin-form');
  if (!form) return;
  const $ = (id) => document.getElementById(id);
  const lat = $('latitude');
  const lng = $('longitude');
  const acc = $('accuracy');
  const button = $('checkin-submit');
  const status = $('location-status');
  const errorBox = $('location-error');
  const errorText = $('location-error-text');

  const MESSAGES = {
    denied: 'Location access is blocked, and this class needs it to check you in. Allow location for this site '
      + '(iPhone: Settings > Privacy & Security > Location Services > Safari Websites; Android: tap the icon next '
      + 'to the address bar > Permissions > Location), then tap Check in again.',
    unavailable: 'Your phone couldn\'t find its location. Turn on Location (GPS), move closer to a window, '
      + 'and tap Check in again.',
    timeout: 'Finding your location took too long. Tap Check in to try again.',
    unsupported: 'This browser can\'t share your location. Open this page in Chrome or Safari and try again.',
  };

  let submitting = false;

  function say(text) {
    if (status) status.textContent = text || '';
  }

  function showError(text) {
    say('');
    if (errorText) errorText.textContent = text;
    if (errorBox) errorBox.hidden = false;
    if (button) button.disabled = false;
    submitting = false;
  }

  function send() {
    submitting = true;
    if (typeof form.requestSubmit === 'function') {
      form.requestSubmit();
    } else {
      form.submit();
    }
  }

  // Back button (page restored from the back/forward cache): start over with a fresh position.
  window.addEventListener('pageshow', (event) => {
    if (!event.persisted) return;
    submitting = false;
    lat.value = lng.value = acc.value = '';
    if (button) button.disabled = false;
    say('');
  });

  form.addEventListener('submit', (event) => {
    if (submitting) return; // our own submit, with the position filled in
    event.preventDefault();
    if (typeof form.reportValidity === 'function' && !form.reportValidity()) return;

    if (!('geolocation' in navigator) || !window.isSecureContext) {
      showError(MESSAGES.unsupported);
      return;
    }
    if (errorBox) errorBox.hidden = true;
    if (button) button.disabled = true;
    say('Getting your location…');

    navigator.geolocation.getCurrentPosition((position) => {
      const c = position.coords;
      lat.value = String(c.latitude);
      lng.value = String(c.longitude);
      acc.value = Number.isFinite(c.accuracy) ? String(Math.round(c.accuracy)) : '';
      say('Checking you in…');
      send();
    }, (error) => {
      if (error && error.code === 1) showError(MESSAGES.denied);
      else if (error && error.code === 3) showError(MESSAGES.timeout);
      else showError(MESSAGES.unavailable);
    }, { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 });
  });
})();
