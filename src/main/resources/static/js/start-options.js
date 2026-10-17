// "Start with these options" (teacher): when "Require location" (anti-cheat rule 6) is ticked, this device's
// position becomes the classroom centre. Shows the radius field only when ticked, captures the position on submit,
// then submits. No inline scripts (CSP).
(function () {
  'use strict';

  const MESSAGES = {
    denied: 'Location access is blocked for this site. Allow it in the browser (icon next to the address bar), '
      + 'or untick "Require location".',
    unavailable: 'This device couldn\'t find its location. Turn on location services, or untick "Require location".',
    timeout: 'Finding this device\'s location took too long. Try again, or untick "Require location".',
    unsupported: 'This browser can\'t share its location (it needs HTTPS). Untick "Require location" or use '
      + 'another browser.',
  };

  document.querySelectorAll('form[data-geofence-form]').forEach((form) => {
    const toggle = form.querySelector('[data-geofence-toggle]');
    const fields = form.querySelector('[data-geofence-fields]');
    const lat = form.querySelector('[data-geofence-lat]');
    const lng = form.querySelector('[data-geofence-lng]');
    const status = form.querySelector('[data-geofence-status]');
    const button = form.querySelector('button[type="submit"]');
    if (!toggle || !lat || !lng) return;

    let submitting = false;

    const say = (text) => { if (status) status.textContent = text || ''; };
    const sync = () => {
      if (fields) fields.hidden = !toggle.checked;
      if (!toggle.checked) say('');
    };
    toggle.addEventListener('change', sync);
    sync();

    const fail = (text) => {
      say(text);
      if (button) button.disabled = false;
    };

    form.addEventListener('submit', (event) => {
      if (submitting || !toggle.checked) return;
      event.preventDefault();
      if (typeof form.reportValidity === 'function' && !form.reportValidity()) return;
      if (!('geolocation' in navigator) || !window.isSecureContext) {
        fail(MESSAGES.unsupported);
        return;
      }
      if (button) button.disabled = true;
      say('Getting this device\'s location…');
      navigator.geolocation.getCurrentPosition((position) => {
        lat.value = String(position.coords.latitude);
        lng.value = String(position.coords.longitude);
        say('Location found (±' + Math.round(position.coords.accuracy) + ' m). Starting…');
        submitting = true;
        if (typeof form.requestSubmit === 'function') form.requestSubmit();
        else form.submit();
      }, (error) => {
        if (error && error.code === 1) fail(MESSAGES.denied);
        else if (error && error.code === 3) fail(MESSAGES.timeout);
        else fail(MESSAGES.unavailable);
      }, { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 });
    });

    window.addEventListener('pageshow', (event) => {
      if (!event.persisted) return;
      submitting = false;
      lat.value = lng.value = '';
      if (button) button.disabled = false;
      sync();
    });
  });
})();
