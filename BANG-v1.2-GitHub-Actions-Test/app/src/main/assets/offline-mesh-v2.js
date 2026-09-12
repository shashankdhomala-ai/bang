(function () {
  'use strict';

  function addControls() {
    const main = document.getElementById('main');
    if (!main || document.getElementById('bang-offline-v2')) return;

    const box = document.createElement('div');
    box.id = 'bang-offline-v2';
    box.className = 'card';
    box.style.margin = '0 14px 14px';
    box.innerHTML = `
      <b>🛰️ BANG Mesh</b>
      <div class="muted small" id="bang-local-status" style="margin-top:5px">
        Mesh stays on automatically when Nearby devices permission and Bluetooth are available.
      </div>
      <div class="muted small" style="margin-top:10px">
        Nearby phones can relay encrypted A→B→C messages. Relay devices forward ciphertext and do not read the message.
      </div>
    `;
    main.appendChild(box);
  }

  const originalChatView = window.chatView;
  if (typeof originalChatView === 'function') {
    window.chatView = function () {
      originalChatView.apply(this, arguments);
      addControls();
    };
  }

  const originalCallsView = window.callsView;
  if (typeof originalCallsView === 'function') {
    window.callsView = function () {
      originalCallsView.apply(this, arguments);
      addCallRelayControls();
    };
  }

  function addCallRelayControls() {
    const main = document.getElementById('main');
    if (!main || document.getElementById('bang-call-relay-v2')) return;
    const box = document.createElement('div');
    box.id = 'bang-call-relay-v2';
    box.className = 'card';
    box.style.margin = '14px 20px';
    box.innerHTML = `
      <b>📞 Local A→B→C calling</b>
      <p class="muted small">B acts only as a transport relay. This is a prototype transport, not production VoIP.</p>
    `;
    main.appendChild(box);
  }

  window.bangMeshEvent = (function (previous) {
    return function (type, text) {
      if (previous) previous(type, text);
      const status = document.getElementById('bang-local-status');
      if (status && (type === 'status' || type === 'relay_status' || type === 'error')) status.textContent = text;
    };
  })(window.bangMeshEvent);

  const observer = new MutationObserver(addControls);
  observer.observe(document.body, { childList: true, subtree: true });
  addControls();
})();
