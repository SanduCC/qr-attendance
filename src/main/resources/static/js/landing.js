// Scan landing: "Copy link" button for in-app browsers where Google sign-in is blocked.
(function () {
  'use strict';

  const status = document.getElementById('copy-status');

  function say(text) {
    if (status) status.textContent = text;
  }

  function fallbackCopy(input) {
    if (!input) return false;
    input.focus();
    input.select();
    input.setSelectionRange(0, input.value.length);
    try {
      return document.execCommand('copy');
    } catch (e) {
      return false;
    }
  }

  document.querySelectorAll('[data-copy]').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const text = btn.getAttribute('data-copy') || '';
      const input = document.getElementById(btn.getAttribute('data-copy-input') || '');
      let ok = false;
      if (navigator.clipboard && window.isSecureContext) {
        try {
          await navigator.clipboard.writeText(text);
          ok = true;
        } catch (e) {
          ok = false;
        }
      }
      if (!ok) ok = fallbackCopy(input);
      if (ok) {
        say('Link copied. Paste it into Chrome or Safari.');
      } else {
        // Leave the text selected so the user can copy it manually.
        if (input) {
          input.focus();
          input.select();
        }
        say('Press and hold the link above, then choose Copy.');
      }
    });
  });
})();
