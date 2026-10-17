// Email sign-in code page (#52): "Send a new code" stays disabled until the resend cooldown is over.
// The server enforces the cooldown too; without JavaScript the button just answers "wait N seconds".
(function () {
  'use strict';

  const button = document.getElementById('resend');
  const wait = document.getElementById('resend-wait');
  if (!button) return;

  let left = parseInt(button.getAttribute('data-cooldown') || '0', 10);
  if (!(left > 0)) return;

  function render() {
    if (left > 0) {
      button.disabled = true;
      if (wait) wait.textContent = 'in ' + left + ' s';
    } else {
      button.disabled = false;
      if (wait) wait.textContent = '';
    }
  }

  render();
  const timer = setInterval(function () {
    left -= 1;
    render();
    if (left <= 0) clearInterval(timer);
  }, 1000);
})();
