// Projector page: rotating QR code, live attendance table, "Closes in mm:ss" countdown and a fullscreen toggle.
// No inline scripts (CSP). Student names only ever reach the page through textContent.
(function () {
  'use strict';

  const body = document.body;
  const sessionId = body.dataset.sessionId;
  if (!sessionId) return;

  const meta = (name) => {
    const el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.getAttribute('content') : null;
  };
  const csrfToken = meta('_csrf');
  const csrfHeader = meta('_csrf_header') || 'X-CSRF-TOKEN';

  const base = '/sessions/' + encodeURIComponent(sessionId);
  const $ = (id) => document.getElementById(id);
  const el = {
    qrLive: $('qr-live'),
    qrClosed: $('qr-closed'),
    qr: $('qr'),
    qrUrl: $('qr-url'),
    closesAt: $('closes-at'),
    countdownWrap: $('countdown-wrap'),
    countdown: $('countdown'),
    count: $('count'),
    expectedWrap: $('expected-wrap'),
    expected: $('expected'),
    finalCount: $('final-count'),
    finalExpectedWrap: $('final-expected-wrap'),
    finalExpected: $('final-expected'),
    over: $('over-warning'),
    conn: $('conn'),
    signedOut: $('signed-out'),
    closeForm: $('close-form'),
    fullscreenBtn: $('fullscreen-btn'),
    records: $('records'),
  };

  const RECORDS_INTERVAL_MS = 3000;
  const RETRY_MS = 3000;
  const SOON_MS = 60000;

  let open = body.dataset.open !== 'false';
  let stopped = false; // set once signed out
  let qrTimer = null;
  let recordsTimer = null;
  let countdownTimer = null;
  let lastRecordsKey = null;
  let closesAtMillis = Number(body.dataset.closesAtMillis);
  // Server clock minus local clock, so the countdown follows the server whatever the laptop's clock says.
  let clockOffset = Number(body.dataset.serverTimeMillis) - Date.now();
  if (!Number.isFinite(clockOffset)) clockOffset = 0;
  let closingPoked = false;
  const failing = { qr: false, records: false };

  class AuthError extends Error {}

  async function request(url, options) {
    const res = await fetch(url, Object.assign({
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
    }, options));
    if (res.status === 401 || res.status === 403 || res.redirected) throw new AuthError();
    return res;
  }

  async function getJson(url) {
    const sentAt = Date.now();
    const res = await request(url);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const type = res.headers.get('Content-Type') || '';
    // A 200 HTML page here means we were bounced to a login page.
    if (!type.includes('json')) throw new AuthError();
    const data = await res.json();
    if (data && Number.isFinite(data.serverTimeMillis)) {
      // Assume the server stamped the response halfway through the round trip.
      clockOffset = data.serverTimeMillis - (sentAt + Date.now()) / 2;
    }
    return data;
  }

  function setFailing(which, value) {
    failing[which] = value;
    el.conn.hidden = stopped || !(failing.qr || failing.records);
  }

  function signedOut() {
    stopped = true;
    clearTimeout(qrTimer);
    clearTimeout(recordsTimer);
    stopCountdown();
    el.conn.hidden = true;
    el.signedOut.hidden = false;
  }

  function handleError(which, err, retry) {
    if (err instanceof AuthError) {
      signedOut();
      return;
    }
    setFailing(which, true);
    if (!stopped) retry(RETRY_MS);
  }

  // ---- QR loop -------------------------------------------------------------

  function scheduleQr(ms) {
    clearTimeout(qrTimer);
    qrTimer = setTimeout(loadQr, ms);
  }

  async function loadQr() {
    if (stopped || !open) return;
    try {
      const data = await getJson(base + '/qr-token');
      setFailing('qr', false);
      if (!data.open) {
        showClosed();
        return;
      }
      if (data.svg) el.qr.innerHTML = data.svg; // server-generated SVG, no user data
      el.qrUrl.textContent = data.url || '';
      let delay = (data.windowEndsAtMillis - data.serverTimeMillis) + 250;
      if (!Number.isFinite(delay)) delay = 5000;
      scheduleQr(Math.min(10000, Math.max(1000, delay)));
    } catch (err) {
      handleError('qr', err, scheduleQr);
    }
  }

  function showClosed() {
    open = false;
    clearTimeout(qrTimer);
    stopCountdown();
    if (el.countdownWrap) el.countdownWrap.hidden = true;
    el.qr.textContent = '';
    el.qrUrl.textContent = '';
    el.qrLive.hidden = true;
    el.qrClosed.hidden = false;
    if (el.closeForm) el.closeForm.hidden = true;
  }

  // ---- Records loop --------------------------------------------------------

  function scheduleRecords(ms) {
    clearTimeout(recordsTimer);
    recordsTimer = setTimeout(loadRecords, ms);
  }

  async function loadRecords() {
    if (stopped) return;
    try {
      const data = await getJson(base + '/records');
      setFailing('records', false);
      render(data);
      if (!data.open) {
        if (open) showClosed();
        return; // closed: list is final, stop polling
      }
      scheduleRecords(RECORDS_INTERVAL_MS);
    } catch (err) {
      handleError('records', err, scheduleRecords);
    }
  }

  function render(data) {
    el.count.textContent = String(data.checkedIn ?? (data.records || []).length);
    if (data.expectedHeadcount != null) {
      el.expected.textContent = String(data.expectedHeadcount);
      el.expectedWrap.hidden = false;
    } else {
      el.expectedWrap.hidden = true;
    }
    el.over.hidden = !data.overHeadcount;
    if (data.closesAt && el.closesAt) el.closesAt.textContent = data.closesAt;
    if (el.finalCount) el.finalCount.textContent = el.count.textContent;
    if (el.finalExpectedWrap) {
      el.finalExpectedWrap.hidden = el.expectedWrap.hidden;
      el.finalExpected.textContent = el.expected.textContent;
    }
    if (Number.isFinite(data.closesAtMillis) && data.closesAtMillis !== closesAtMillis) {
      closesAtMillis = data.closesAtMillis;
      closingPoked = false;
    }
    tick();

    const records = Array.isArray(data.records) ? data.records : [];
    const key = JSON.stringify(records);
    if (key === lastRecordsKey) return; // avoid replacing buttons under the mouse
    lastRecordsKey = key;

    const rows = records.map((r, i) => row(r, i + 1));
    if (rows.length === 0) {
      const tr = document.createElement('tr');
      tr.className = 'empty-row';
      const td = cell('No check-ins yet.');
      td.colSpan = 5;
      td.className = 'muted';
      tr.appendChild(td);
      rows.push(tr);
    }
    el.records.replaceChildren(...rows);
  }

  function cell(text, className) {
    const td = document.createElement('td');
    td.textContent = text == null ? '' : String(text);
    if (className) td.className = className;
    return td;
  }

  function row(r, n) {
    const name = [r.firstName, r.lastName].filter(Boolean).join(' ');
    const tr = document.createElement('tr');
    tr.appendChild(cell(n, 'num'));
    tr.appendChild(cell(name, 'name'));
    tr.appendChild(cell(r.email, 'email'));
    tr.appendChild(cell(r.checkedInAt));
    const td = document.createElement('td');
    td.className = 'actions';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-danger btn-sm';
    btn.textContent = 'Remove';
    btn.setAttribute('aria-label', 'Remove ' + name);
    btn.addEventListener('click', () => removeRecord(r.id, name, btn));
    td.appendChild(btn);
    tr.appendChild(td);
    return tr;
  }

  async function removeRecord(recordId, name, btn) {
    if (!window.confirm('Remove ' + name + '?')) return;
    btn.disabled = true;
    const headers = { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
    if (csrfToken) headers[csrfHeader] = csrfToken;
    try {
      const res = await request(base + '/records/' + encodeURIComponent(recordId), {
        method: 'DELETE',
        headers: headers,
      });
      if (res.status !== 204 && !res.ok) throw new Error('HTTP ' + res.status);
      lastRecordsKey = null;
      clearTimeout(recordsTimer);
      loadRecords();
    } catch (err) {
      if (err instanceof AuthError) {
        signedOut();
        return;
      }
      btn.disabled = false;
      window.alert('Could not remove the entry. Please try again.');
    }
  }

  // ---- Countdown -----------------------------------------------------------

  function pad(n) {
    return (n < 10 ? '0' : '') + n;
  }

  function formatLeft(ms) {
    const total = Math.max(0, Math.ceil(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    return (h > 0 ? h + ':' + pad(m) : pad(m)) + ':' + pad(s);
  }

  function tick() {
    if (!el.countdown || !open || stopped || !Number.isFinite(closesAtMillis)) return;
    const left = closesAtMillis - (Date.now() + clockOffset);
    const text = formatLeft(left);
    if (el.countdown.textContent !== text) el.countdown.textContent = text;
    el.countdownWrap.classList.toggle('is-soon', left <= SOON_MS);
    if (left <= 0 && !closingPoked) {
      // Time's up: ask the server now instead of waiting for the next poll.
      closingPoked = true;
      scheduleRecords(500);
    }
  }

  function startCountdown() {
    stopCountdown();
    tick();
    countdownTimer = setInterval(tick, 250);
  }

  function stopCountdown() {
    clearInterval(countdownTimer);
    countdownTimer = null;
  }

  // ---- Fullscreen (button stays hidden where the API is missing, e.g. iPhone Safari) ---------------

  const root = document.documentElement;
  const fullscreenElement = () => document.fullscreenElement || document.webkitFullscreenElement || null;

  function updateFullscreenLabel() {
    el.fullscreenBtn.textContent = fullscreenElement() ? 'Exit fullscreen' : 'Fullscreen';
  }

  if (el.fullscreenBtn && (document.fullscreenEnabled || document.webkitFullscreenEnabled)) {
    el.fullscreenBtn.hidden = false;
    el.fullscreenBtn.addEventListener('click', () => {
      try {
        let result;
        if (fullscreenElement()) {
          result = (document.exitFullscreen || document.webkitExitFullscreen).call(document);
        } else {
          result = (root.requestFullscreen || root.webkitRequestFullscreen).call(root);
        }
        if (result && typeof result.catch === 'function') result.catch(() => {});
      } catch (e) {
        // Refused by the browser: nothing to do, the page works the same without fullscreen.
      }
    });
    document.addEventListener('fullscreenchange', updateFullscreenLabel);
    document.addEventListener('webkitfullscreenchange', updateFullscreenLabel);
  }

  // ---- Close session -------------------------------------------------------

  if (el.closeForm) {
    el.closeForm.addEventListener('submit', (e) => {
      if (!window.confirm('Close this session? Students will no longer be able to check in.')) {
        e.preventDefault();
      }
    });
  }

  // ---- Start ---------------------------------------------------------------

  if (open) {
    loadQr();
    startCountdown();
  } else {
    showClosed();
  }
  loadRecords();
})();
